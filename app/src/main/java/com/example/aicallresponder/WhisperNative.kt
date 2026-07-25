package com.example.aicallresponder

/**
 * JNI bridge to whisper.cpp (see app/src/main/cpp). The native library is built from the
 * whisper.cpp sources you add as a git submodule — see the README.
 */
object WhisperNative {

    @Volatile private var loaded = false

    /** Returns true if libwhisperjni loaded. If false, Whisper STT is unavailable on this build. */
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("whisperjni")
            loaded = true
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Loads a ggml model file. Returns a native context pointer, or 0 on failure. */
    external fun initContext(modelPath: String): Long

    external fun freeContext(ptr: Long)

    /** Transcribes 16 kHz mono float PCM in [-1,1]. [lang] is a 2-letter code, e.g. "ne". */
    external fun transcribe(ptr: Long, audio: FloatArray, lang: String, threads: Int): String
}
