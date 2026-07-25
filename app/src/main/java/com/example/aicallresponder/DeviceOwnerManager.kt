package com.example.aicallresponder

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Helpers for the Device Owner capabilities we actually use: detecting owner status and silently
 * granting the app's own runtime permissions (no user dialogs).
 *
 * IMPORTANT: Device Owner grants only *runtime* (dangerous) permissions. It cannot grant
 * signature/privileged permissions such as CAPTURE_AUDIO_OUTPUT, so it does NOT unlock direct
 * in-call audio capture/injection — that still requires a system/privileged build.
 */
class DeviceOwnerManager(private val context: Context) {

    private val dpm =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val admin = ComponentName(context, AiDeviceAdminReceiver::class.java)

    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(context.packageName)

    /** Silently sets each runtime permission to GRANTED. Returns true only if every one succeeded. */
    fun grantRuntimePermissions(permissions: List<String>): Boolean {
        if (!isDeviceOwner()) return false
        var allOk = true
        for (perm in permissions) {
            val ok = try {
                dpm.setPermissionGrantState(
                    admin,
                    context.packageName,
                    perm,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
            } catch (e: Exception) {
                Log.w(TAG, "Grant failed for $perm", e)
                false
            }
            if (!ok) allOk = false
        }
        return allOk
    }

    companion object {
        private const val TAG = "DeviceOwnerManager"

        /** Runtime permissions the app needs, filtered by API level. */
        fun requiredRuntimePermissions(): List<String> {
            val list = mutableListOf(
                android.Manifest.permission.READ_PHONE_STATE,
                android.Manifest.permission.ANSWER_PHONE_CALLS,
                android.Manifest.permission.RECORD_AUDIO
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
            return list
        }
    }
}
