package com.shilapi.xcertplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class UsbMuxTransferFramingTest {
    private val trailer = byteArrayOf(0x3c, 0x40, 0x75, 0x6d)

    private fun version() = ByteArray(20).apply {
        this[7] = 20
        this[11] = 2
    }

    private fun tcp(size: Int = 36) = ByteArray(size).apply {
        this[3] = 6
        this[7] = size.toByte()
        this[8] = 0xfa.toByte()
        this[9] = 0xce.toByte()
        this[10] = 0xfa.toByte()
        this[11] = 0xce.toByte()
        this[28] = 0x50
    }

    @Test fun observedTransfersDoNotLeaveFourBytesInParser() {
        val framing = UsbMuxTransferFraming()
        assertArrayEquals(version(), framing.normalize(version() + trailer, true))
        assertArrayEquals(tcp(), framing.normalize(tcp() + trailer, true))
        assertArrayEquals(tcp(40), framing.normalize(tcp(40) + trailer, true))
    }

    @Test fun ordinaryHostDoesNotEnableTrailerMode() {
        val framing = UsbMuxTransferFraming()
        assertArrayEquals(version(), framing.normalize(version(), true))
        val input = tcp() + trailer
        assertArrayEquals(input, framing.normalize(input, true))
    }

    @Test fun coalescedAndFragmentedTransfersRemainIntact() {
        val framing = UsbMuxTransferFraming()
        framing.normalize(version() + trailer, true)
        val coalesced = tcp() + tcp()
        assertArrayEquals(coalesced, framing.normalize(coalesced, true))
        val partial = tcp().copyOf(24)
        assertArrayEquals(partial, framing.normalize(partial, true))
        val continuation = tcp() + trailer
        assertArrayEquals(continuation, framing.normalize(continuation, false))
    }

    @Test fun invalidMagicAndTcpHeaderRemainIntact() {
        val framing = UsbMuxTransferFraming()
        framing.normalize(version() + trailer, true)
        val invalidMagic = tcp().apply { this[8] = 0 } + trailer
        val invalidHeader = tcp().apply { this[28] = 0 } + trailer
        assertArrayEquals(invalidMagic, framing.normalize(invalidMagic, true))
        assertArrayEquals(invalidHeader, framing.normalize(invalidHeader, true))
    }
}
