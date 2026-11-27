package com.shilapi.xcertplay.transport

/**
 * Compatibility mode for USB hosts that return a complete mux frame plus four trailer bytes.
 * Enable only on the observed 24-byte version-2 reply declaring exactly 20 bytes.
 * Never scan arbitrary prefixes or trim a fragmented or coalesced transfer.
 */
internal class UsbMuxTransferFraming {
    private var trailerMode = false

    fun normalize(bytes: ByteArray, atFrameBoundary: Boolean): ByteArray {
        if (!atFrameBoundary || bytes.size < 20) return bytes
        val protocol = u32(bytes, 0)
        val length = u32(bytes, 4)
        if (length !in 16..65_536 || bytes.size != length + 4) return bytes

        if (protocol == 0 && length == 20 && u32(bytes, 8) == 2) {
            trailerMode = true
            return bytes.copyOf(length)
        }
        if (!trailerMode) return bytes

        // These magic words identify the negotiated v2 framing, not the trailer.
        val magic = u32(bytes, 8)
        if (magic != 0xfaceface.toInt() && magic != 0xfeedface.toInt()) return bytes
        when (protocol) {
            6 -> {
                if (length < 36) return bytes
                val tcpHeader = ((bytes[28].toInt() ushr 4) and 15) * 4
                if (tcpHeader !in 20..(length - 16)) return bytes
            }
            1, 2 -> if (length < 17) return bytes
            else -> return bytes
        }
        return bytes.copyOf(length)
    }

    private fun u32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 255) shl 24) or
            ((bytes[offset + 1].toInt() and 255) shl 16) or
            ((bytes[offset + 2].toInt() and 255) shl 8) or
            (bytes[offset + 3].toInt() and 255)
}
