package com.example.aicallresponder

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import java.util.Locale

/**
 * Foreground service that answers the ringing call, forces the loudspeaker, and drives the
 * TTS <-> STT <-> Claude conversation until the call ends.
 */
class CallHandlerService : Service() {

    private lateinit var audioManager: AudioManager
    private lateinit var telephonyManager: TelephonyManager

    private var speech: SpeechEngine? = null
    private var stt: SttEngine? = null
    private var conversation: ConversationManager? = null

    private var callActive = false
    private var handled = false

    private val main = Handler(Looper.getMainLooper())

    // API 31+ call-state listener
    private var telephonyCallback: TelephonyCallback? = null
    // API < 31 fallback
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        startForegroundWithNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HANDLE_CALL -> beginHandling()
            ACTION_CALL_ENDED -> onCallEnded()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun beginHandling() {
        if (handled) return
        handled = true
        registerCallStateListener()
        answerCall()
    }

    private fun answerCall() {
        if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "ANSWER_PHONE_CALLS not granted — cannot auto-answer.")
            stopSelf()
            return
        }
        try {
            val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.acceptRingingCall()
            Log.d(TAG, "acceptRingingCall() issued")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to answer call", e)
            stopSelf()
        }
    }

    private fun onCallActive() {
        if (callActive) return
        callActive = true
        Log.d(TAG, "Call active — routing to speaker and starting conversation")

        routeToSpeaker()

        val prefs = Prefs(this)
        val locale = Locale.forLanguageTag(prefs.language)
        val ttsEngine = SpeechEngine(this, locale).also { speech = it }
        val sttEngine = buildStt(prefs).also { stt = it }
        val claude = ClaudeClient(prefs.apiKey, prefs.model)

        conversation = ConversationManager(
            tts = ttsEngine,
            stt = sttEngine,
            claude = claude,
            systemPrompt = prefs.systemPrompt,
            greeting = prefs.greeting,
            greetingAudioPath = prefs.greetingAudioPath,
            onEnded = {
                // Assistant decided to stop (e.g. long silence). With ANSWER_PHONE_CALLS granted
                // we can actually drop the call via TelecomManager.endCall().
                Log.d(TAG, "Conversation ended by assistant — hanging up")
                hangUp()
            }
        )
        // Give the audio route a moment to settle before greeting.
        main.postDelayed({ conversation?.start() }, 800)
    }

    private fun buildStt(prefs: Prefs): SttEngine {
        val whisperSelected = prefs.sttEngine.equals("whisper", ignoreCase = true)
        return if (whisperSelected && prefs.whisperModelPath.isNotBlank()) {
            WhisperSttEngine(this, prefs.whisperModelPath, prefs.language.substringBefore('-'))
        } else {
            if (whisperSelected) {
                Log.w(TAG, "Whisper selected but no model path set — falling back to Google STT.")
            }
            GoogleSttEngine(this, prefs.language)
        }
    }

    /** Ends the active call. Works on API 28+ when ANSWER_PHONE_CALLS is granted. */
    private fun hangUp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.w(TAG, "endCall() needs API 28+; cannot hang up programmatically here.")
            return
        }
        if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Cannot hang up: ANSWER_PHONE_CALLS not granted.")
            return
        }
        try {
            val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            @Suppress("MissingPermission")
            val ended = telecom.endCall()
            Log.d(TAG, "endCall() -> $ended")
        } catch (e: Exception) {
            Log.e(TAG, "hangUp failed", e)
        }
    }

    private fun onCallEnded() {
        Log.d(TAG, "Call ended — tearing down")
        conversation?.stop()
        conversation = null
        stt?.release()
        stt = null
        speech?.shutdown()
        speech = null
        restoreAudio()
        unregisterCallStateListener()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- Audio routing -------------------------------------------------------------------------

    private fun routeToSpeaker() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val speaker = audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) {
                    audioManager.setCommunicationDevice(speaker)
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to route to speaker", e)
        }
    }

    private fun restoreAudio() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            }
        } catch (_: Exception) {}
    }

    // --- Call-state listening ------------------------------------------------------------------

    private fun registerCallStateListener() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) = handleState(state)
            }
            telephonyCallback = cb
            telephonyManager.registerTelephonyCallback(mainExecutor, cb)
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Deprecated in Java")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) = handleState(state)
            }
            phoneStateListener = listener
            @Suppress("DEPRECATION")
            telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        }
    }

    private fun handleState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_OFFHOOK -> onCallActive()
            TelephonyManager.CALL_STATE_IDLE -> if (callActive || handled) onCallEnded()
        }
    }

    private fun unregisterCallStateListener() {
        telephonyCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
        telephonyCallback = null
        @Suppress("DEPRECATION")
        phoneStateListener?.let { telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE) }
        phoneStateListener = null
    }

    // --- Foreground notification ---------------------------------------------------------------

    private fun startForegroundWithNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Call handling",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Call Responder")
            .setContentText("Handling the current call…")
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
        try {
            startForeground(NOTIF_ID, notification)
        } catch (e: Exception) {
            // Android 14+ refuses a microphone-typed FGS if RECORD_AUDIO isn't granted yet.
            Log.e(TAG, "startForeground failed — is RECORD_AUDIO granted?", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        conversation?.stop()
        stt?.release()
        speech?.shutdown()
        unregisterCallStateListener()
    }

    companion object {
        private const val TAG = "CallHandlerService"
        private const val CHANNEL_ID = "call_handling"
        private const val NOTIF_ID = 42

        const val ACTION_HANDLE_CALL = "com.example.aicallresponder.HANDLE_CALL"
        const val ACTION_CALL_ENDED = "com.example.aicallresponder.CALL_ENDED"
    }
}
