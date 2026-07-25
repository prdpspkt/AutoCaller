package com.example.aicallresponder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * On boot, if auto-answer is enabled, (re)start the persistent monitor service so calls are handled
 * without the user having to open the app. If the app is Device Owner it also silently re-grants the
 * runtime permissions first. Starting the mic foreground service from boot works for Device Owner
 * (exempt from background-start limits); on a normal install Android may block it until the app is
 * opened once.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = Prefs(context)
        if (!prefs.enabled) return

        val owner = DeviceOwnerManager(context)
        if (owner.isDeviceOwner()) {
            val ok = owner.grantRuntimePermissions(DeviceOwnerManager.requiredRuntimePermissions())
            Log.d(TAG, "Boot: re-granted permissions (allOk=$ok)")
        }

        try {
            CallHandlerService.start(context)
            Log.d(TAG, "Boot: started call monitor")
        } catch (e: Exception) {
            Log.w(TAG, "Boot: could not start monitor (needs app opened once on non-owner devices)", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
