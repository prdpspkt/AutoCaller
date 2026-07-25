package com.example.aicallresponder

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * Speech OUTPUT: TextToSpeech + MediaPlayer (pre-recorded greeting). Speech INPUT lives in the
 * [SttEngine] implementations instead.
 *
 * Audio is routed to STREAM_VOICE_CALL so that, with speakerphone on, it plays out the loudspeaker
 * and the caller hears it. All calls must happen on the main thread.
 */
class SpeechEngine(
    private val context: Context,
    private val ttsLocale: Locale
) {

    private var tts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var ttsReady = false

    fun initTts(onReady: (Boolean) -> Unit) {
        tts = TextToSpeech(context) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                val res = tts?.setLanguage(ttsLocale)
                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                    // e.g. no Nepali voice data on this device — fall back so we still speak something.
                    Log.w(TAG, "TTS locale $ttsLocale unavailable ($res); falling back to default.")
                    tts?.setLanguage(Locale.getDefault())
                }
            } else {
                Log.e(TAG, "TTS init failed: $status")
            }
            main.post { onReady(ttsReady) }
        }
    }

    /** Speak [text]; [onDone] fires on the main thread when playback finishes (or errors out). */
    fun speak(text: String, utteranceId: String, onDone: () -> Unit) {
        val engine = tts
        if (!ttsReady || engine == null || text.isBlank()) {
            main.post(onDone)
            return
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { main.post(onDone) }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { main.post(onDone) }
        })
        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_VOICE_CALL)
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
    }

    /** Play a pre-recorded greeting file. Falls back to [onDone] immediately if it can't play. */
    fun playAudioFile(path: String, onDone: () -> Unit) {
        val file = File(path)
        if (!file.exists()) {
            main.post(onDone)
            return
        }
        try {
            releasePlayer()
            mediaPlayer = MediaPlayer().apply {
                setAudioStreamType(AudioManager.STREAM_VOICE_CALL)
                setDataSource(file.absolutePath)
                setOnCompletionListener { main.post(onDone) }
                setOnErrorListener { _, _, _ -> main.post(onDone); true }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "playAudioFile failed", e)
            main.post(onDone)
        }
    }

    private fun releasePlayer() {
        mediaPlayer?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        mediaPlayer = null
    }

    fun shutdown() {
        releasePlayer()
        tts?.let {
            it.stop()
            it.shutdown()
        }
        tts = null
        ttsReady = false
    }

    companion object {
        private const val TAG = "SpeechEngine"
    }
}
