package com.shilapi.xcertplay.transport

/** Reads only the granted NCM IN endpoint using the existing Android USB connection. */
internal object NcmUsbNative {
    init { System.loadLibrary("xcertplay_i2c") }
    external fun bulkRead(fd: Int, endpoint: Int, buffer: ByteArray, timeoutMillis: Int): Int
}
