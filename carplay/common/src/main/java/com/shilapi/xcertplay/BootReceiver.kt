package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.hardware.usb.UsbManager

/** Starts the CarPlay host after boot when the user has enabled the startup option. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val enabled = AirPlayPersistence.loadAutoStartOnBoot(context)
        val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val appleAttached = usb.deviceList.values.any { it.vendorId == 0x05ac }
        Log.i(TAG, "Boot received: autoStart=$enabled appleAttached=$appleAttached")
        if (!enabled && !appleAttached) return
        val pending = goAsync()
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                launchHost(context)
            } finally {
                pending.finish()
            }
        }, 2500L)
    }

    private fun launchHost(context: Context) {

        val launch = Intent(context, CarPlayHostActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        try {
            Log.i(TAG, "Requesting CarPlay host after boot")
            context.startActivity(launch)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Boot auto-start could not launch CarPlayHostActivity", error)
        }
    }

    private companion object {
        const val TAG = "xcertplay-boot"
    }
}
