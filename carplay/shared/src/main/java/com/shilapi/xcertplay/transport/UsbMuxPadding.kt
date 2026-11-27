package com.shilapi.xcertplay.transport

/** Bounded compatibility recovery for zero-word prefixes seen on the HiAce USB host.
 * A complete TCP header must be structurally valid before any prefix is removed.
 * Bytes after the declared frame are never consumed by this helper.
 */
internal object UsbMuxPadding {
    const val NEED_MORE = -1

    fun tcpPrefix(bytes: ByteArray): Int {
        if (bytes.size < 8) return 0
        // Never reinterpret a normally framed packet.
        if (u32(bytes, 4) in 16..65_536) return 0
        for (prefix in listOf(4, 8)) {
            if (bytes.size < prefix || (0 until prefix).any { bytes[it] != 0.toByte() }) break
            if (bytes.size < prefix + 8) return NEED_MORE
            if (u32(bytes, prefix) != 6) continue
            val length = u32(bytes, prefix + 4)
            if (length !in 36..65_536) return 0
            if (bytes.size < prefix + 36) return NEED_MORE
            val tcpHeader = ((bytes[prefix + 16 + 12].toInt() ushr 4) and 15) * 4
            return if (tcpHeader in 20..(length - 16)) prefix else 0
        }
        return 0
    }

    private fun u32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 255) shl 24) or
            ((bytes[offset + 1].toInt() and 255) shl 16) or
            ((bytes[offset + 2].toInt() and 255) shl 8) or
            (bytes[offset + 3].toInt() and 255)
}
