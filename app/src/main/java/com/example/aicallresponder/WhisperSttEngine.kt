package com.example.aicallresponder

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

/**
 * Fully on-device Nepali STT using whisper.cpp.
 *
 * We capture the mic ourselves with [AudioRecord] (16 kHz mono) using energy-based endpointing,
 * then run whisper.cpp on the buffered utterance. Capturing the mic directly (rather than via
 * SpeechRecognizer) is also what makes this more likely to work while a call is active.
 *
 * VOICE_COMMUNICATION + AEC/NS are enabled to fight the speakerphone echo of our own TTS.
 */
class WhisperSttEngine(
    private val context: Context,
    private val modelPath: String,
    private val whisperLang: String
) : SttEngine {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val main = Handler(Looper.getMainLooper())

    private var ctxPtr: Long = 0L
    @Volatile private var ready = false
    @Volatile private var loading = false
    @Volatile private var cancelled = false

    private var recordJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    override fun prepare() {
        if (ready || loading) return
        if (!WhisperNative.ensureLoaded()) {
            Log.e(TAG, "libwhisperjni not loaded — did you add the whisper.cpp submodule?")
            return
        }
        loading = true
        scope.launch {
            val file = File(modelPath)
            if (!file.exists()) {
                Log.e(TAG, "Whisper model not found at $modelPath")
                loading = false
                return@launch
            }
            ctxPtr = try {
                WhisperNative.initContext(file.absolutePath)
            } catch (t: Throwable) {
                Log.e(TAG, "initContext failed", t); 0L
            }
            ready = ctxPtr != 0L
            loading = false
            Log.d(TAG, "Whisper model load ready=$ready")
        }
    }

    override fun listen(onResult: (String) -> Unit, onError: (Int) -> Unit) {
        cancelled = false
        recordJob = scope.launch {
            // Wait for the model to finish loading (first call after the greeting).
            val startWait = System.currentTimeMillis()
            while (!ready) {
                if (cancelled) return@launch
                if (!loading) {
                    // Not loading and not ready => lib/model missing or load failed. Fail fast so
                    // the caller falls back / retries instead of blocking for the full timeout.
                    main.post { onError(ERR_NOT_READY) }
                    return@launch
                }
                if (System.currentTimeMillis() - startWait > MODEL_WAIT_MS) {
                    main.post { onError(ERR_NOT_READY) }
                    return@launch
                }
                delay(150)
            }

            val pcm = try {
                recordUtterance()
            } catch (e: Exception) {
                Log.e(TAG, "recording failed", e)
                null
            }
            if (cancelled) return@launch
            if (pcm == null || pcm.isEmpty()) {
                main.post { onResult("") }   // silence -> treated as "caller said nothing"
                return@launch
            }

            val floats = pcmToFloat(pcm)
            val text = try {
                WhisperNative.transcribe(ctxPtr, floats, whisperLang, threadCount())
            } catch (t: Throwable) {
                Log.e(TAG, "transcribe failed", t); ""
            }
            if (cancelled) return@launch
            main.post { onResult(clean(text)) }
        }
    }

    private suspend fun recordUtterance(): ShortArray = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) return@withContext ShortArray(0)
        val bufSize = maxOf(minBuf, SAMPLE_RATE) // ~1s buffer

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, CHANNEL, ENCODING, bufSize
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "AudioRecord denied — RECORD_AUDIO missing?", e)
            return@withContext ShortArray(0)
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return@withContext ShortArray(0)
        }
        audioRecord = recorder
        enableAudioFx(recorder.audioSessionId)

        recorder.startRecording()
        val out = ArrayList<Short>()
        val frame = ShortArray(FRAME_SAMPLES)
        var speechStarted = false
        var silenceMs = 0
        var speechMs = 0
        var waitedMs = 0
        try {
            while (!cancelled) {
                val read = recorder.read(frame, 0, frame.size)
                if (read <= 0) continue
                val level = rms(frame, read)
                val frameMs = read * 1000 / SAMPLE_RATE
                val isSpeech = level > SPEECH_THRESHOLD
                if (!speechStarted) {
                    waitedMs += frameMs
                    if (isSpeech) {
                        speechStarted = true
                        for (i in 0 until read) out.add(frame[i])
                    } else if (waitedMs >= MAX_INITIAL_SILENCE_MS) {
                        break // caller never spoke
                    }
                } else {
                    for (i in 0 until read) out.add(frame[i])
                    speechMs += frameMs
                    silenceMs = if (isSpeech) 0 else silenceMs + frameMs
                    if (silenceMs >= END_SILENCE_MS) break
                    if (speechMs >= MAX_UTTERANCE_MS) break
                }
            }
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            releaseAudioFx()
            recorder.release()
            audioRecord = null
        }
        out.toShortArray()
    }

    private fun enableAudioFx(sessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sessionId)?.apply { setEnabled(true) }
            }
            if (NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(sessionId)?.apply { setEnabled(true) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio fx setup failed", e)
        }
    }

    private fun releaseAudioFx() {
        try { aec?.release() } catch (_: Exception) {}
        try { ns?.release() } catch (_: Exception) {}
        aec = null
        ns = null
    }

    private fun rms(buf: ShortArray, len: Int): Double {
        var sum = 0.0
        for (i in 0 until len) { val v = buf[i].toDouble(); sum += v * v }
        return sqrt(sum / len)
    }

    private fun pcmToFloat(pcm: ShortArray): FloatArray {
        val out = FloatArray(pcm.size)
        for (i in pcm.indices) out[i] = pcm[i] / 32768f
        return out
    }

    private fun threadCount(): Int =
        Runtime.getRuntime().availableProcessors().coerceIn(2, 4)

    private fun clean(text: String): String {
        val t = text.trim()
        // whisper sometimes emits bracketed non-speech markers on silence.
        if (t.isEmpty() || t == "[BLANK_AUDIO]" || t.startsWith("(") && t.endsWith(")")) return ""
        return t
    }

    override fun stopListening() {
        cancelled = true
        recordJob?.cancel()
        try { audioRecord?.stop() } catch (_: Exception) {}
    }

    override fun release() {
        cancelled = true
        recordJob?.cancel()
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        if (ctxPtr != 0L) {
            try { WhisperNative.freeContext(ctxPtr) } catch (_: Throwable) {}
            ctxPtr = 0L
        }
        ready = false
        scope.cancel()
    }

    companion object {
        private const val TAG = "WhisperSttEngine"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_SAMPLES = 320          // 20 ms frames
        private const val SPEECH_THRESHOLD = 700.0     // RMS; tune per device/mic
        private const val END_SILENCE_MS = 900         // trailing silence => utterance done
        private const val MAX_UTTERANCE_MS = 12_000
        private const val MAX_INITIAL_SILENCE_MS = 6_000
        private const val MODEL_WAIT_MS = 20_000L
        private const val ERR_NOT_READY = 999
    }
}
