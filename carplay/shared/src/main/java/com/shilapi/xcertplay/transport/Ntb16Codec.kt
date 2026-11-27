package com.shilapi.xcertplay.transport

/**
 * Minimal NTB16 codec for the iPhone NCM data link.
 *
 * Only the 16-bit transfer-block layout used by LIVI's `iap2-usbmux/src/ntb.rs` is implemented:
 * one NTH16 header, one NDP16 table with one datagram, and the USB short-packet pad byte. There
 * is no NTB32, alignment, CRC, or NCM control-plane handling here.
 */
object Ntb16Codec {
    const val NTH16_SIG = 0x484d434e
    const val NDP16_SIG = 0x304d434e

    private const val NTH_LENGTH = 12
    private const val NDP_LENGTH = 16
    private const val DATAGRAM_INDEX = 28
    private const val USB_PACKET_SIZE = 512

    /** Wraps one Ethernet frame in one NTB16 block, padding an exact 512-byte boundary. */
    fun build(frame: ByteArray, sequence: Int): ByteArray =
        buildAligned(frame, sequence, 4, 2, 4, 65_535, USB_PACKET_SIZE)

    /** NCM remainder applies to the IP payload, after the 14-byte Ethernet header. */
    fun buildAligned(
        frame: ByteArray, sequence: Int, divisor: Int, payloadRemainder: Int,
        ndpAlignment: Int, maxBlock: Int, packetSize: Int,
    ): ByteArray {
        require(frame.isNotEmpty()) { "frame must not be empty" }
        require(frame.size <= MAX_DATAGRAM_BYTES) { "frame exceeds the NTB16 length field" }
        require(sequence in 0..0xffff) { "sequence must fit in u16" }

        require(divisor in 4..1024 && divisor and (divisor - 1) == 0)
        require(ndpAlignment in 4..1024 && ndpAlignment and (ndpAlignment - 1) == 0)
        require(payloadRemainder in 0 until divisor)
        require(maxBlock in 28..65_535 && packetSize > 0)
        val ndpIndex = (NTH_LENGTH + ndpAlignment - 1) and (ndpAlignment - 1).inv()
        val base = ndpIndex + NDP_LENGTH
        val remainder = (payloadRemainder - 14) and (divisor - 1)
        val datagramIndex = base + ((remainder - base) and (divisor - 1))
        val blockLength = datagramIndex + frame.size
        require(blockLength <= maxBlock) { "NTB exceeds negotiated output size" }
        val block = ByteArray(blockLength)
        putU32(block, 0, NTH16_SIG)
        putU16(block, 4, NTH_LENGTH)
        putU16(block, 6, sequence)
        putU16(block, 8, blockLength)
        putU16(block, 10, ndpIndex)

        putU32(block, ndpIndex, NDP16_SIG)
        putU16(block, ndpIndex + 4, NDP_LENGTH)
        putU16(block, ndpIndex + 6, 0)
        putU16(block, ndpIndex + 8, datagramIndex)
        putU16(block, ndpIndex + 10, frame.size)
        putU16(block, ndpIndex + 12, 0)
        putU16(block, ndpIndex + 14, 0)

        frame.copyInto(block, datagramIndex)
        // Never exceed the negotiated block limit to add a short-packet byte.
        return if (block.size % packetSize == 0 && block.size < maxBlock) block + byteArrayOf(0) else block
    }

    /**
     * Extracts the Ethernet frames carried by one NTB16 block.
     *
     * Wire garbage returns an empty list instead of throwing; malformed entries are skipped where
     * a bounds check fails. A step guard stops a malformed NDP pointer chain from cycling.
     */
    fun parse(block: ByteArray): List<ByteArray> = parse(block, 0, block.size)

    /** Parses one NTB16 block from [offset] without copying the containing buffer. */
    fun parse(block: ByteArray, offset: Int, length: Int): List<ByteArray> {
        require(offset >= 0 && length >= 0 && offset <= block.size - length) {
            "NTB16 range is outside the source buffer"
        }
        val end = offset + length
        if (length < NTH_LENGTH || readU32(block, offset) != NTH16_SIG) return emptyList()

        val datagrams = ArrayList<ByteArray>(1)
        var ndpOffset = offset + readU16(block, offset + 10)
        var hops = 0
        val maxHops = maxOf(1, length / 4)
        while (ndpOffset != 0 && hops < maxHops) {
            if (ndpOffset < offset || ndpOffset + 12 > end) break
            if (readU32(block, ndpOffset) and 0x00ffffff != NDP16_SIG and 0x00ffffff) break
            val ndpLength = readU16(block, ndpOffset + 4)
            val nextNdp = readU16(block, ndpOffset + 6)
            var entry = ndpOffset + 8
            val ndpEnd = minOf(ndpOffset + ndpLength, end)
            while (entry + 4 <= ndpEnd) {
                val datagramIndex = readU16(block, entry)
                val datagramLength = readU16(block, entry + 2)
                if (datagramIndex == 0 || datagramLength == 0) break
                val datagramStart = offset + datagramIndex
                val datagramEnd = datagramStart + datagramLength
                if (datagramStart >= offset && datagramStart <= end && datagramEnd <= end) {
                    datagrams.add(block.copyOfRange(datagramStart, datagramEnd))
                }
                entry += 4
            }
            ndpOffset = if (nextNdp == 0) 0 else offset + nextNdp
            hops++
        }
        return datagrams
    }

    private fun putU16(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
    }

    private fun putU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = value.toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = (value ushr 16).toByte()
        target[offset + 3] = (value ushr 24).toByte()
    }

    private fun readU16(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or
            ((source[offset + 1].toInt() and 0xff) shl 8) or
            ((source[offset + 2].toInt() and 0xff) shl 16) or
            ((source[offset + 3].toInt() and 0xff) shl 24)

    private const val MAX_DATAGRAM_BYTES = 0xffff - DATAGRAM_INDEX
}
