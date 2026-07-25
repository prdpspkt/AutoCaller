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
 * Persistent foreground service that MONITORS for incoming calls and handles them.
 *
 * Why persistent: on Android 12+/14 you cannot *start* a microphone foreground service from the
 * background (e.g. from a PHONE_STATE broadcast at call time). So instead the user starts this
 * service from the foreground when they enable auto-answer; it then stays alive with an ongoing
 * notification and detects ringing via TelephonyCallback — which works even when the app is closed.
 *
 * On ringing it answers, forces the loudspeaker, and runs the TTS <-> STT <-> DeepSeek loop; when the
 * call ends it returns to idle monitoring (it does NOT stop until the user disables the feature).
 */
class CallHandlerService : Service() {

    private lateinit var audioManager: AudioManager
    private lateinit var telephonyManager: TelephonyManager

    private var speech: SpeechEngine? = null
    private var stt: SttEngine? = null
    private var conversation: ConversationManager? = null

    private var monitoring = false
    private var callActive = false

    private val main = Handler(Looper.getMainLooper())

    private var telephonyCallback: TelephonyCallback? = null
    @Suppress("DEPRECATION")
    private var phoneStateListener: PhoneStateListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means the system restarted us (START_STICKY) — resume monitoring.
        when (intent?.action ?: ACTION_START) {
            ACTION_START -> startMonitoring()
            ACTION_STOP -> {
                stopMonitoring()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        if (!monitoring) {
            monitoring = true
            registerCallStateListener()
        }
        // (Re)assert the foreground notification.
        showForeground(if (callActive) "Handling the current call…" else "Listening for incoming calls")
    }

    private fun stopMonitoring() {
        tearDownCall()
        unregisterCallStateListener()
        monitoring = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // --- Call flow -----------------------------------------------------------------------------

    private fun handleState(state: Int) {
        when (state) {
            TelephonyManager.CALL_STATE_RINGING -> onRinging()
            TelephonyManager.CALL_STATE_OFFHOOK -> onCallActive()
            TelephonyManager.CALL_STATE_IDLE -> if (callActive) endCallHandling()
        }
    }

    private fun onRinging() {
        if (callActive) return
        val prefs = Prefs(this)
        if (!prefs.enabled) return
        if (prefs.apiKey.isBlank()) {
            Log.w(TAG, "Ringing but no DeepSeek API key set — not answering.")
            return
        }
        answerCall()
    }

    private fun answerCall() {
        if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "ANSWER_PHONE_CALLS not granted — cannot auto-answer.")
            return
        }
        try {
            val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecom.acceptRingingCall()
            Log.d(TAG, "acceptRingingCall() issued")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to answer call", e)
        }
    }

    private fun onCallActive() {
        if (callActive) return
        callActive = true
        Log.d(TAG, "Call active — routing to speaker and starting conversation")
        showForeground("Handling the current call…")
        routeToSpeaker()

        val prefs = Prefs(this)
        val locale = Locale.forLanguageTag(prefs.language)
        val ttsEngine = SpeechEngine(this, locale).also { speech = it }
        val sttEngine = buildStt(prefs).also { stt = it }
        val llm = DeepSeekClient(prefs.apiKey, prefs.model)

        conversation = ConversationManager(
            tts = ttsEngine,
            stt = sttEngine,
            llm = llm,
            systemPrompt = prefs.systemPrompt,
            greeting = prefs.greeting,
            greetingAudioPath = prefs.greetingAudioPath,
            onEnded = {
                Log.d(TAG, "Conversation ended by assistant — hanging up")
                hangUp()
            }
        )
        main.postDelayed({ conversation?.start() }, 800)
    }

    /** Called when the call ends: tear down the conversation but keep monitoring for the next call. */
    private fun endCallHandling() {
        Log.d(TAG, "Call ended — returning to idle monitoring")
        tearDownCall()
        showForeground("Listening for incoming calls")
    }

    private fun tearDownCall() {
        conversation?.stop()
        conversation = null
        stt?.release()
        stt = null
        speech?.shutdown()
        speech = null
        restoreAudio()
        callActive = false
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            val telecom = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            @Suppress("MissingPermission")
            telecom.endCall()
        } catch (e: Exception) {
            Log.e(TAG, "hangUp failed", e)
        }
    }

    // --- Audio routing -------------------------------------------------------------------------

    private fun routeToSpeaker() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val speaker = audioManager.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                if (speaker != null) audioManager.setCommunicationDevice(speaker)
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

    private fun unregisterCallStateListener() {
        telephonyCallback?.let { telephonyManager.unregisterTelephonyCallback(it) }
        telephonyCallback = null
        @Suppress("DEPRECATION")
        phoneStateListener?.let { telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE) }
        phoneStateListener = null
    }

    // --- Foreground notification ---------------------------------------------------------------

    private fun showForeground(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Call handling", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AI Call Responder")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
        try {
            startForeground(NOTIF_ID, notification)
        } catch (e: Exception) {
            // Android 14+ refuses a microphone-typed FGS if RECORD_AUDIO isn't granted / app not eligible.
            Log.e(TAG, "startForeground failed — RECORD_AUDIO granted and app in foreground?", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tearDownCall()
        unregisterCallStateListener()
    }

    companion object {
        private const val TAG = "CallHandlerService"
        private const val CHANNEL_ID = "call_handling"
        private const val NOTIF_ID = 42

        const val ACTION_START = "com.example.aicallresponder.START_MONITOR"
        const val ACTION_STOP = "com.example.aicallresponder.STOP_MONITOR"

        /** Start the persistent monitor. Must be called while the app is in the foreground. */
        fun start(context: Context) {
            val i = Intent(context, CallHandlerService::class.java).apply { action = ACTION_START }
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, CallHandlerService::class.java).apply { action = ACTION_STOP }
            context.startService(i)
        }
    }
}
