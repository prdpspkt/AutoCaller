package com.example.aicallresponder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

/**
 * Listens for PHONE_STATE broadcasts. When a call is RINGING and the feature is enabled, it kicks
 * off the foreground service that answers and handles the call.
 */
class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
        Log.d(TAG, "Phone state changed: $state")

        val prefs = Prefs(context)
        if (!prefs.enabled) return

        // Only react to RINGING. The service self-detects OFFHOOK/IDLE via its own call-state
        // listener, which avoids the Android 8+ background-service-start restriction.
        if (state == TelephonyManager.EXTRA_STATE_RINGING) {
            if (prefs.apiKey.isBlank()) {
                Log.w(TAG, "Ringing but no API key configured — skipping.")
                return
            }
            val svc = Intent(context, CallHandlerService::class.java).apply {
                action = CallHandlerService.ACTION_HANDLE_CALL
            }
            context.startForegroundService(svc) // minSdk 26 => always available
        }
    }

    companion object {
        private const val TAG = "CallReceiver"
    }
}
