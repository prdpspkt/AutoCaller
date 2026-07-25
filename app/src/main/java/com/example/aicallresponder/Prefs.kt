package com.example.aicallresponder

import android.content.Context

/**
 * Thin wrapper over SharedPreferences for the app's settings.
 *
 * NOTE: the DeepSeek API key is stored on-device in plain SharedPreferences. That is fine for a
 * personal build, but a key shipped inside an APK can be extracted. For anything public, put the
 * key behind your own backend proxy instead of calling DeepSeek directly from the phone.
 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("ai_call_responder", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_ENABLED, v).apply()

    var apiKey: String
        get() = sp.getString(KEY_API_KEY, "") ?: ""
        set(v) = sp.edit().putString(KEY_API_KEY, v.trim()).apply()

    var model: String
        get() = sp.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) = sp.edit().putString(KEY_MODEL, v.trim()).apply()

    var greeting: String
        get() = sp.getString(KEY_GREETING, DEFAULT_GREETING) ?: DEFAULT_GREETING
        set(v) = sp.edit().putString(KEY_GREETING, v).apply()

    var systemPrompt: String
        get() = sp.getString(KEY_SYSTEM, DEFAULT_SYSTEM) ?: DEFAULT_SYSTEM
        set(v) = sp.edit().putString(KEY_SYSTEM, v).apply()

    /** Optional absolute path to a pre-recorded greeting (wav/mp3/m4a). Blank = use TTS. */
    var greetingAudioPath: String
        get() = sp.getString(KEY_GREETING_AUDIO, "") ?: ""
        set(v) = sp.edit().putString(KEY_GREETING_AUDIO, v.trim()).apply()

    /** BCP-47 language tag used for TTS + speech recognition, e.g. "ne-NP", "en-US", "hi-IN". */
    var language: String
        get() = sp.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
        set(v) = sp.edit().putString(KEY_LANGUAGE, v.trim()).apply()

    /** STT backend: "whisper" (on-device) or "google" (SpeechRecognizer). */
    var sttEngine: String
        get() = sp.getString(KEY_STT_ENGINE, DEFAULT_STT_ENGINE) ?: DEFAULT_STT_ENGINE
        set(v) = sp.edit().putString(KEY_STT_ENGINE, v.trim().lowercase()).apply()

    /** Absolute path to the whisper.cpp ggml model (.bin). Required when sttEngine = "whisper". */
    var whisperModelPath: String
        get() = sp.getString(KEY_WHISPER_MODEL, "") ?: ""
        set(v) = sp.edit().putString(KEY_WHISPER_MODEL, v.trim()).apply()

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_GREETING = "greeting"
        private const val KEY_SYSTEM = "system_prompt"
        private const val KEY_GREETING_AUDIO = "greeting_audio_path"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_STT_ENGINE = "stt_engine"
        private const val KEY_WHISPER_MODEL = "whisper_model_path"

        // deepseek-v4-flash (non-thinking) is the low-latency choice, which matters inside a call.
        const val DEFAULT_MODEL = "deepseek-v4-flash"

        // Default to Nepali. Change in the app for other languages (BCP-47 tags).
        const val DEFAULT_LANGUAGE = "ne-NP"

        // On-device Whisper by default (falls back to Google if no model path is set).
        const val DEFAULT_STT_ENGINE = "whisper"

        const val DEFAULT_GREETING =
            "नमस्ते! तपाईंले स्वचालित सहायकलाई सम्पर्क गर्नुभयो। म तपाईंलाई कसरी मद्दत गर्न सक्छु?"

        const val DEFAULT_SYSTEM =
            "You are a helpful phone assistant answering a call on behalf of the phone's owner. " +
            "Reply in Nepali (नेपाली) by default; if the caller clearly speaks another language, " +
            "reply in that language instead. Keep every reply short and conversational — one or two " +
            "sentences — since it will be read aloud over a phone call. Ask clarifying questions when " +
            "needed. If the caller wants to leave a message, acknowledge it and say you'll pass it along."
    }
}
