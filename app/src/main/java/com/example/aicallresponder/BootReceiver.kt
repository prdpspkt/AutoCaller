package com.example.aicallresponder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * On boot, if the app is Device Owner and auto-answer is enabled, silently (re)grant the runtime
 * permissions so the app is immediately armed without any user interaction after a reboot.
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
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
