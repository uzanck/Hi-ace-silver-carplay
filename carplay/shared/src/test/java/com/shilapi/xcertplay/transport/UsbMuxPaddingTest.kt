package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbMuxPaddingTest {
    private fun tcpFrame() = ByteArray(36).apply {
        this[3] = 6
        this[7] = 36
        this[28] = 0x50
    }

    @Test fun normalFrameIsUntouched() {
        assertEquals(0, UsbMuxPadding.tcpPrefix(tcpFrame()))
    }

    @Test fun observed44ByteShapeAllowsTrailingData() {
        assertEquals(4, UsbMuxPadding.tcpPrefix(ByteArray(4) + tcpFrame() + ByteArray(4)))
    }

    @Test fun coalescedFramesAreAllowed() {
        assertEquals(4, UsbMuxPadding.tcpPrefix(ByteArray(4) + tcpFrame() + tcpFrame()))
    }

    @Test fun twoZeroWordsCanPrecedeNextFrame() {
        assertEquals(8, UsbMuxPadding.tcpPrefix(ByteArray(8) + tcpFrame()))
    }

    @Test fun fragmentedHeaderWaits() {
        assertEquals(UsbMuxPadding.NEED_MORE, UsbMuxPadding.tcpPrefix(ByteArray(4) + tcpFrame().copyOf(16)))
    }

    @Test fun invalidTcpHeaderIsRejected() {
        val malformed = tcpFrame().apply { this[28] = 0 }
        assertEquals(0, UsbMuxPadding.tcpPrefix(ByteArray(4) + malformed))
    }

    @Test fun nonzeroPrefixIsRejected() {
        assertEquals(0, UsbMuxPadding.tcpPrefix(byteArrayOf(0, 0, 0, 1) + tcpFrame()))
    }
}
