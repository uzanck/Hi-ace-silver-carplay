package com.shilapi.xcertplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Ntb16CodecTest {
    @Test
    fun hiAceNegotiatedAlignmentPlacesIpPayloadOnFourByteBoundary() {
        val frame = ByteArray(62) { it.toByte() }
        val block = Ntb16Codec.buildAligned(frame, 1, 4, 0, 4, 32764, 512)
        assertEquals(30, block[20].toInt() and 255)
        assertEquals(0, (30 + 14) % 4)
        assertArrayEquals(frame, Ntb16Codec.parse(block).single())
    }

    @Test
    fun largerNdpAlignmentIsRespected() {
        val frame = ByteArray(64)
        val block = Ntb16Codec.buildAligned(frame, 2, 8, 0, 16, 32764, 512)
        val ndp = block[10].toInt() and 255
        val offset = block[ndp + 8].toInt() and 255
        assertEquals(0, ndp % 16)
        assertEquals(0, (offset + 14) % 8)
        assertArrayEquals(frame, Ntb16Codec.parse(block).single())
    }

    @Test(expected = IllegalArgumentException::class)
    fun negotiatedMaxBlockIsEnforced() {
        Ntb16Codec.buildAligned(ByteArray(3000), 1, 4, 0, 4, 2048, 512)
    }

    @Test
    fun parseCanReadBlockFromLargerBuffer() {
        val frame = byteArrayOf(0x33, 0x33, 0, 0, 0, 1, 0x86.toByte(), 0xdd.toByte())
        val block = Ntb16Codec.build(frame, 7)
        val buffer = ByteArray(11) + block + ByteArray(3)

        val parsed = Ntb16Codec.parse(buffer, 11, block.size)

        assertArrayEquals(frame, parsed.single())
    }

    @Test
    fun largestDatagramKeepsBlockLengthWithinU16() {
        val block = Ntb16Codec.build(ByteArray(65_507), 0x1234)

        assertEquals(65_535, block.size)
        assertEquals(0xff, block[8].toInt() and 0xff)
        assertEquals(0xff, block[9].toInt() and 0xff)
    }

    @Test(expected = IllegalArgumentException::class)
    fun datagramThatWouldOverflowBlockLengthIsRejected() {
        Ntb16Codec.build(ByteArray(65_508), 0x1234)
    }
}
