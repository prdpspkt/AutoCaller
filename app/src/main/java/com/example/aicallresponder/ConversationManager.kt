package com.example.aicallresponder

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Orchestrates one call's conversation:
 *   greeting -> listen -> DeepSeek -> speak -> listen -> ... -> hang up / go idle.
 *
 * Half-duplex by nature: we never listen while TTS is speaking, otherwise the recognizer just
 * hears our own voice off the loudspeaker.
 */
class ConversationManager(
    private val tts: SpeechEngine,
    private val stt: SttEngine,
    private val llm: DeepSeekClient,
    private val systemPrompt: String,
    private val greeting: String,
    private val greetingAudioPath: String,
    private val onEnded: () -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val history = mutableListOf<DeepSeekClient.Turn>()
    private var active = false
    private var consecutiveSilence = 0

    fun start() {
        active = true
        stt.prepare() // warm up (e.g. load the Whisper model) while we greet
        tts.initTts { ok ->
            if (!active) return@initTts
            if (!ok) { finish(); return@initTts }
            deliverGreeting { if (active) listen() }
        }
    }

    private fun deliverGreeting(onDone: () -> Unit) {
        if (greetingAudioPath.isNotBlank()) {
            tts.playAudioFile(greetingAudioPath) { onDone() }
        } else {
            tts.speak(greeting, "greeting", onDone)
        }
    }

    private fun listen() {
        if (!active) return
        stt.listen(
            onResult = { text ->
                if (!active) return@listen
                if (text.isBlank()) { handleSilence(); return@listen }
                consecutiveSilence = 0
                respondTo(text)
            },
            onError = { code ->
                if (!active) return@listen
                Log.w(TAG, "STT error $code")
                handleSilence()
            }
        )
    }

    private fun respondTo(userText: String) {
        history.add(DeepSeekClient.Turn("user", userText))
        scope.launch {
            val reply = try {
                llm.reply(systemPrompt, history)
            } catch (e: Exception) {
                Log.e(TAG, "DeepSeek call failed", e)
                "माफ गर्नुहोस्, मैले बुझिनँ। कृपया फेरि भन्नुहोस्।"
            }
            if (!active) return@launch
            history.add(DeepSeekClient.Turn("assistant", reply))
            tts.speak(reply, "reply-${history.size}") { if (active) listen() }
        }
    }

    private fun handleSilence() {
        consecutiveSilence++
        if (consecutiveSilence >= MAX_SILENCE) {
            active = false
            tts.speak("मैले केही सुनिनँ, त्यसैले अहिलेलाई राख्दैछु। धन्यवाद।", "bye") { finish() }
        } else {
            listen()
        }
    }

    private fun finish() {
        active = false
        onEnded()
    }

    /** Called by the service when the call ends. */
    fun stop() {
        active = false
        stt.stopListening()
        scope.cancel()
    }

    companion object {
        private const val TAG = "ConversationManager"
        private const val MAX_SILENCE = 3
    }
}
