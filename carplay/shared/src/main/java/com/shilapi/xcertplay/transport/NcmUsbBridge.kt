package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbConstants
import android.util.Log
import java.io.Closeable
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A blocking NCM data pipe that moves Ethernet frames as NTB16 blocks over bulk endpoints.
 *
 * The caller opens the USB connection while the CarPlay configuration is already active; this
 * bridge claims only the NCM control/data interfaces and owns the connection thereafter. All
 * calls may block and must run away from the Android main thread.
 */
class NcmUsbBridge internal constructor(
    private val connection: UsbDeviceConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val statusEndpoint: UsbEndpoint?,
    private val claimedInterfaces: List<UsbInterface>,
    descriptorHostMac: ByteArray?,
    private val controlId: Int,
    private val inputSize: Int,
    private val outputSize: Int,
    private val divisor: Int,
    private val remainder: Int,
    private val alignment: Int,
) : Closeable {
    private val descriptorMac = descriptorHostMac?.copyOf()
    val hostMac: ByteArray? get() = descriptorMac?.copyOf()
    private val stateLock = Any()
    private val readLock = Any()
    private val writeLock = Any()
    private var closed = false
    private var failure: IphoneUsbException? = null
    private var sequence = 0
    private var loggedWriteTimeout = false
    private var readDiagnostics = 0
    private var readTimeoutDiagnostics = 0
    private val frames = ArrayDeque<ByteArray>()
    private var queuedBytes = 0
    private var buffered = ByteArray(0)
    private var bufferedSize = 0
    private val readBuffer = ByteArray(inputSize + 4)
    private val refreshedFilter = AtomicBoolean(false)
    // Keep all NCM endpoint transfers idle during USBMUX/iAP2 bring-up on this USB host.
    private val dataStart = CountDownLatch(1)
    private val loggedDeferred = AtomicBoolean(false)
    private var writeDiagnostics = 0
    private val statusRunning = AtomicBoolean(statusEndpoint != null)
    private val statusThread = statusEndpoint?.let { endpoint ->
        Thread({ drainStatus(endpoint) }, "ncm-status-in").apply {
            isDaemon = true
            start()
        }
    }

    /** Wraps one Ethernet frame in one NTB16 block and writes it to bulk OUT. */
    fun send(frame: ByteArray, timeoutMillis: Int) = synchronized(writeLock) {
        checkOpen()
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        if (!awaitDataStart(timeoutMillis.toLong())) return@synchronized
        checkOpen()
        val sequence = synchronized(stateLock) {
            checkOpenLocked()
            this.sequence.also { this.sequence = (this.sequence + 1) and 0xffff }
        }
        val block = Ntb16Codec.buildAligned(frame, sequence, divisor, remainder, alignment, outputSize, outEndpoint.maxPacketSize.coerceAtLeast(1))
        val transferred = connection.bulkTransfer(outEndpoint, block, block.size, timeoutMillis)
        if (writeDiagnostics < 12) {
            writeDiagnostics++
            Log.i(IphoneCarPlayConfiguration.TAG, "HiAce NCM bulk OUT bytes=${block.size} result=$transferred")
        }
        // Before StartCarPlaySession the phone keeps the NCM data path NAKed. Android reports the
        // resulting timeout as -1; it is not a detach and later packets must be allowed to retry.
        if (transferred <= 0) {
            if (!loggedWriteTimeout) {
                loggedWriteTimeout = true
                Log.i(IphoneCarPlayConfiguration.TAG, "ncm bulk-out not ready; retaining bridge for retry")
            }
            return@synchronized
        }
        if (transferred != block.size) {
            throw IphoneUsbException.DeviceUnavailable(
                "NCM write transferred $transferred of ${block.size} bytes",
            )
        }
        if (loggedWriteTimeout) {
            loggedWriteTimeout = false
            Log.i(IphoneCarPlayConfiguration.TAG, "ncm bulk-out became ready")
        }
    }

    /** Reapply the standard filter once after the start-session request is queued.
     * Do not reset the alternate setting here: it could interrupt an active USB session.
     */
    fun refreshAfterCarPlayStart() {
        if (!refreshedFilter.compareAndSet(false, true)) return
        checkOpen()
        try {
            val result = connection.controlTransfer(0x21, 0x43, 0x000e, controlId, null, 0, USB_CONTROL_TIMEOUT_MILLIS)
            Log.i(IphoneCarPlayConfiguration.TAG, "HiAce NCM post-start filter result=$result")
        } finally {
            dataStart.countDown()
            Log.i(IphoneCarPlayConfiguration.TAG, "HiAce NCM data gate opened after CarPlayStartSession queued")
        }
    }

    private fun awaitDataStart(timeoutMillis: Long): Boolean {
        if (dataStart.count == 0L) return true
        if (loggedDeferred.compareAndSet(false, true)) {
            Log.i(IphoneCarPlayConfiguration.TAG, "HiAce NCM data gate closed pending CarPlayStartSession")
        }
        return try {
            dataStart.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /**
     * Returns the next complete Ethernet frame, or null when [timeoutMillis] elapses without one.
     * USB reads may split or coalesce NTB blocks; this method reassembles whole blocks internally.
     */
    fun recv(timeoutMillis: Long): ByteArray? {
        require(timeoutMillis > 0) { "timeoutMillis must be positive" }
        synchronized(readLock) {
            checkOpen()
            if (frames.isNotEmpty()) return pollFrame()

            val deadline = System.nanoTime() + timeoutMillis * NANOS_PER_MILLISECOND
            if (!awaitDataStart(timeoutMillis)) return null
            checkOpen()
            while (true) {
                drainFrames()
                if (frames.isNotEmpty()) return pollFrame()
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) return null
                val chunkLength =
                    readChunk((remainingNanos + NANOS_PER_MILLISECOND - 1) / NANOS_PER_MILLISECOND)
                        ?: continue
                appendBuffered(readBuffer, chunkLength)
            }
        }
    }

    override fun close() {
        statusRunning.set(false)
        synchronized(stateLock) {
            if (closed) return
            closed = true
        }
        dataStart.countDown()
        statusThread?.let { thread ->
            try {
                thread.join(STATUS_READ_TIMEOUT_MILLIS + 250L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        for (usbInterface in claimedInterfaces.asReversed()) {
            try {
                connection.releaseInterface(usbInterface)
            } catch (_: RuntimeException) {
                // Best-effort release; the connection close below is authoritative.
            }
        }
        connection.close()
    }

    private fun drainStatus(endpoint: UsbEndpoint) {
        val buffer = ByteArray(endpoint.maxPacketSize.coerceAtLeast(64))
        var loggedFirst = false
        while (statusRunning.get()) {
            if (!awaitDataStart(STATUS_READ_TIMEOUT_MILLIS.toLong())) {
                if (Thread.currentThread().isInterrupted) return
                continue
            }
            if (!statusRunning.get()) return
            val transferred = try {
                connection.bulkTransfer(endpoint, buffer, buffer.size, STATUS_READ_TIMEOUT_MILLIS)
            } catch (_: RuntimeException) {
                return
            }
            if (transferred <= 0) continue
            if (!loggedFirst) {
                loggedFirst = true
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm status notification bytes=$transferred data=${buffer.copyOf(transferred).hex(32)}",
                )
            }
        }
    }

    private fun ByteArray.hex(limit: Int): String =
        take(limit).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun drainFrames() {
        while (true) {
            if (bufferedSize < 12) return
            if (readU32(buffered, 0) != Ntb16Codec.NTH16_SIG) {
                throw failSession("NCM read buffer does not begin with an NTB16 header")
            }
            val blockLength = readU16(buffered, 8)
            if (blockLength < 28) throw failSession("Invalid NTB16 block length $blockLength")
            val padded = blockLength % inEndpoint.maxPacketSize.coerceAtLeast(1) == 0
            val wireLength = blockLength + if (padded) 1 else 0
            if (bufferedSize < wireLength) return
            if (padded && buffered[blockLength].toInt() != 0) {
                throw failSession("Invalid NTB16 short-packet pad")
            }
            for (frame in Ntb16Codec.parse(buffered, 0, blockLength)) enqueueFrame(frame)
            val remaining = bufferedSize - wireLength
            buffered.copyInto(buffered, 0, wireLength, bufferedSize)
            bufferedSize = remaining
        }
    }

    private fun appendBuffered(source: ByteArray, length: Int) {
        val required = bufferedSize + length
        if (required > buffered.size) {
            val capacity = maxOf(required, maxOf(READ_CHUNK_BYTES, buffered.size * 2))
            val grown = ByteArray(capacity)
            buffered.copyInto(grown, 0, 0, bufferedSize)
            buffered = grown
        }
        source.copyInto(buffered, bufferedSize, 0, length)
        bufferedSize += length
    }

    private fun enqueueFrame(frame: ByteArray) {
        if (frames.size >= MAX_QUEUED_FRAMES || queuedBytes + frame.size > MAX_QUEUED_BYTES) {
            throw failSession("NCM frame queue exceeded its bounds")
        }
        frames.addLast(frame)
        queuedBytes += frame.size
    }

    private fun pollFrame(): ByteArray {
        val frame = frames.removeFirst()
        queuedBytes -= frame.size
        return frame
    }

    private fun readChunk(timeoutMillis: Long): Int? {
        checkOpen()
        val transferred = try {
            connection.bulkTransfer(inEndpoint, readBuffer, readBuffer.size, timeoutMillis.toInt())
        } catch (error: RuntimeException) {
            throw failSession("NCM read failed", error)
        }
        // Android reports both an ordinary bulk-IN timeout and a NAK as a negative result. Keep
        // polling: USBMUX owns authoritative detach/failure detection for the same phone.
        if (transferred <= 0) {
            if (readTimeoutDiagnostics < 5) {
                readTimeoutDiagnostics++
                Log.i(IphoneCarPlayConfiguration.TAG, "HiAce NCM bulk IN no data result=$transferred endpoint=0x${inEndpoint.address.toString(16)}")
            }
            return null
        }
        if (transferred > readBuffer.size) throw failSession("NCM invalid transfer length $transferred")
        if (readDiagnostics < 20) {
            readDiagnostics++
            val declared = if (transferred >= 12 && readU32(readBuffer, 0) == Ntb16Codec.NTH16_SIG) readU16(readBuffer, 8) else -1
            Log.i(IphoneCarPlayConfiguration.TAG, "HiAce NCM bulk IN bytes=$transferred declared=$declared buffered=$bufferedSize")
        }
        return transferred
    }

    private fun failSession(message: String, cause: Throwable? = null): IphoneUsbException.DeviceUnavailable {
        val error = IphoneUsbException.DeviceUnavailable(message, cause)
        synchronized(stateLock) {
            if (failure == null) failure = error
        }
        return error
    }

    private fun checkOpen() {
        synchronized(stateLock) { checkOpenLocked() }
    }

    private fun checkOpenLocked() {
        failure?.let { throw it }
        if (closed) throw IphoneUsbException.DeviceUnavailable("NCM bridge is closed")
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)

    companion object {
        private const val READ_CHUNK_BYTES = 32 * 1024
        private const val USB_PACKET_SIZE = 512
        private const val STATUS_READ_TIMEOUT_MILLIS = 1_000
        private const val MAX_QUEUED_FRAMES = 256
        private const val MAX_QUEUED_BYTES = 1 shl 20
        private const val NANOS_PER_MILLISECOND = 1_000_000L

        /** Claims and activates the NCM control/data interfaces; owns the connection on success. */
        fun open(connection: UsbDeviceConnection, function: NcmFunctionDiscovery.NcmFunction): NcmUsbBridge {
            val claimed = ArrayList<UsbInterface>(2)
            try {
                val descriptorHostMac = readNcmHostMac(connection, function.control.id)
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm descriptor hostMac=${descriptorHostMac?.macString() ?: "unavailable"}",
                )
                // Apple's Ethernet function exposes control and data as alternate settings of the
                // same interface id, so it must be claimed once and switched with setInterface.
                val sameInterface = function.control.id == function.data.id
                val first = if (sameInterface) function.data else function.control
                val firstClaimed = connection.claimInterface(first, true)
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "claim iface=${first.id}/${first.alternateSetting} class=${first.interfaceClass}" +
                        " subclass=${first.interfaceSubclass} proto=${first.interfaceProtocol} ok=$firstClaimed",
                )
                if (!firstClaimed) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "Android could not claim the NCM interface ${first.id}",
                    )
                }
                claimed.add(first)
                if (!sameInterface) {
                    val dataClaimed = connection.claimInterface(function.data, true)
                    Log.i(
                        IphoneCarPlayConfiguration.TAG,
                        "claim iface=${function.data.id}/${function.data.alternateSetting}" +
                            " class=${function.data.interfaceClass} ok=$dataClaimed",
                    )
                    if (!dataClaimed) {
                        throw IphoneUsbException.DeviceUnavailable(
                            "Android could not claim the NCM data interface ${function.data.id}",
                        )
                    }
                    claimed.add(function.data)
                }
                // SET_NTB_FORMAT is only permitted while the data interface is inactive.
                val inactive = function.inactiveData
                val inactiveSelected = inactive != null && connection.setInterface(inactive)
                Log.i(IphoneCarPlayConfiguration.TAG, "HiAce NCM data alt0 selected=$inactiveSelected")
                // Read standard NCM parameters for this claimed control interface. Some Apple
                // functions do not implement all CDC requests; do not fail an otherwise usable link.
                val parameters = ByteArray(28)
                val parameterBytes = connection.controlTransfer(
                    0xa1, 0x80, 0, function.control.id,
                    parameters, parameters.size, USB_CONTROL_TIMEOUT_MILLIS,
                )
                Log.i(IphoneCarPlayConfiguration.TAG, "HiAce NCM GET_NTB_PARAMETERS result=$parameterBytes" +
                    if (parameterBytes >= 28) {
                        val fields = java.nio.ByteBuffer.wrap(parameters).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        " formats=${fields.getShort(2).toInt() and 0xffff} inMax=${fields.getInt(4).toUInt()}" +
                            " outMax=${fields.getInt(16).toUInt()} outDivisor=${fields.getShort(20).toInt() and 0xffff}" +
                            " outRemainder=${fields.getShort(22).toInt() and 0xffff} outAlignment=${fields.getShort(24).toInt() and 0xffff}"
                    } else "")
                val fields = java.nio.ByteBuffer.wrap(parameters).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val validParameters = parameterBytes >= 28 && (fields.getShort(0).toInt() and 0xffff) >= 28
                val formats = if (validParameters) fields.getShort(2).toInt() and 0xffff else 1
                val inputSize = if (validParameters) fields.getInt(4).takeIf { it in 2048..65535 }?.coerceAtMost(32764) ?: 16384 else 16384
                val outputSize = if (validParameters) fields.getInt(16).takeIf { it in 2048..65535 } ?: 16384 else 16384
                fun validPower(value: Int): Int = if (value in 4..1024 && value and (value - 1) == 0) value else 4
                val divisor = if (validParameters) validPower(fields.getShort(20).toInt() and 0xffff) else 4
                val alignment = if (validParameters) validPower(fields.getShort(24).toInt() and 0xffff) else 4
                val remainder = if (validParameters) (fields.getShort(22).toInt() and 0xffff).takeIf { it < divisor } ?: 0 else 2
                if (inactiveSelected && formats and 1 != 0) {
                    val formatResult = connection.controlTransfer(0x21, 0x84, 0, function.control.id, null, 0, USB_CONTROL_TIMEOUT_MILLIS)
                    Log.i(IphoneCarPlayConfiguration.TAG, "HiAce NCM SET_NTB_FORMAT NTB16 result=$formatResult")
                    val sizeBytes = java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(inputSize).array()
                    val sizeResult = connection.controlTransfer(0x21, 0x86, 0, function.control.id, sizeBytes, sizeBytes.size, USB_CONTROL_TIMEOUT_MILLIS)
                    Log.i(IphoneCarPlayConfiguration.TAG, "HiAce NCM SET_NTB_INPUT_SIZE size=$inputSize result=$sizeResult")
                } else {
                    Log.i(IphoneCarPlayConfiguration.TAG, "HiAce NCM explicit format skipped; inactive setting or NTB16 unavailable")
                }
                Log.i(IphoneCarPlayConfiguration.TAG, "HiAce NCM negotiated in=$inputSize out=$outputSize divisor=$divisor remainder=$remainder alignment=$alignment")
                val altSelected = connection.setInterface(function.data)
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "setInterface iface=${function.data.id}/${function.data.alternateSetting} ok=$altSelected",
                )
                if (!altSelected) {
                    throw IphoneUsbException.DeviceUnavailable(
                        "Android could not select the NCM data alternate setting",
                    )
                }
                Log.i(
                    IphoneCarPlayConfiguration.TAG,
                    "ncm status endpoint=${function.statusIn?.address?.let { "0x${it.toString(16)}" } ?: "none"}",
                )
                // Directed, broadcast and all-multicast are needed for IPv6 neighbor discovery.
                // A standard CDC request scoped only to the NCM control interface; no vendor commands.
                val filterResult = connection.controlTransfer(
                    0x21, 0x43, 0x000e, function.control.id,
                    null, 0, USB_CONTROL_TIMEOUT_MILLIS,
                )
                Log.i(IphoneCarPlayConfiguration.TAG, "HiAce NCM SET_ETHERNET_PACKET_FILTER result=$filterResult iface=${function.control.id}")
                return NcmUsbBridge(
                    connection,
                    function.bulkOut,
                    function.bulkIn,
                    function.statusIn,
                    claimed,
                    descriptorHostMac,
                    function.control.id, inputSize, outputSize, divisor, remainder, alignment,
                )
            } catch (error: Throwable) {
                for (usbInterface in claimed.asReversed()) {
                    try {
                        connection.releaseInterface(usbInterface)
                    } catch (_: RuntimeException) {
                        // The connection close below is authoritative.
                    }
                }
                connection.close()
                if (error is IphoneUsbException) throw error
                throw IphoneUsbException.DeviceUnavailable("Android NCM open failed", error)
            }
        }

        private fun readNcmHostMac(connection: UsbDeviceConnection, controlInterfaceId: Int): ByteArray? {
            val index = ethernetMacStringIndex(connection.rawDescriptors, controlInterfaceId) ?: return null
            val buffer = ByteArray(256)
            val length = connection.controlTransfer(
                UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD,
                USB_REQUEST_GET_DESCRIPTOR,
                (USB_STRING_DESCRIPTOR_TYPE shl 8) or index,
                USB_ENGLISH_US,
                buffer,
                buffer.size,
                USB_CONTROL_TIMEOUT_MILLIS,
            )
            if (length < 4 || (buffer[1].toInt() and 0xff) != USB_STRING_DESCRIPTOR_TYPE) return null
            val descriptorLength = (buffer[0].toInt() and 0xff).coerceAtMost(length)
            if (descriptorLength < 4) return null
            val value = buffer.copyOfRange(2, descriptorLength).toString(Charsets.UTF_16LE)
            val hex = value.filter { it.digitToIntOrNull(16) != null }
            if (hex.length != 12) return null
            return ByteArray(6) { offset -> hex.substring(offset * 2, offset * 2 + 2).toInt(16).toByte() }
        }

        private fun ethernetMacStringIndex(raw: ByteArray, controlInterfaceId: Int): Int? {
            var offset = 0
            var currentInterface = -1
            while (offset + 2 <= raw.size) {
                val length = raw[offset].toInt() and 0xff
                val type = raw[offset + 1].toInt() and 0xff
                if (length < 2 || offset + length > raw.size) return null
                if (type == USB_INTERFACE_DESCRIPTOR_TYPE && length >= 9) {
                    currentInterface = raw[offset + 2].toInt() and 0xff
                } else if (
                    type == CDC_FUNCTIONAL_DESCRIPTOR_TYPE &&
                    length >= 4 &&
                    currentInterface == controlInterfaceId &&
                    (raw[offset + 2].toInt() and 0xff) == CDC_ETHERNET_SUBTYPE
                ) {
                    return (raw[offset + 3].toInt() and 0xff).takeIf { it != 0 }
                }
                offset += length
            }
            return null
        }

        private fun ByteArray.macString(): String =
            joinToString(":") { byte -> "%02x".format(byte.toInt() and 0xff) }

        private const val USB_INTERFACE_DESCRIPTOR_TYPE = 0x04
        private const val USB_REQUEST_GET_DESCRIPTOR = 0x06
        private const val USB_STRING_DESCRIPTOR_TYPE = 0x03
        private const val CDC_FUNCTIONAL_DESCRIPTOR_TYPE = 0x24
        private const val CDC_ETHERNET_SUBTYPE = 0x0f
        private const val USB_ENGLISH_US = 0x0409
        private const val USB_CONTROL_TIMEOUT_MILLIS = 1_000
    }
}
