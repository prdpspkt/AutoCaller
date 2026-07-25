package com.example.aicallresponder

/**
 * Speech-to-text abstraction so the conversation loop doesn't care whether recognition is done by
 * Google's on-device recognizer or by on-device Whisper.
 *
 * Contract: [listen] performs a single utterance capture and delivers exactly one callback —
 * [onResult] with the transcript (possibly empty = caller said nothing) or [onError].
 * All callbacks are delivered on the main thread.
 */
interface SttEngine {

    /** Optional warm-up (e.g. load a model). Safe to call more than once. */
    fun prepare() {}

    fun listen(onResult: (String) -> Unit, onError: (Int) -> Unit)

    fun stopListening()

    fun release()
}
