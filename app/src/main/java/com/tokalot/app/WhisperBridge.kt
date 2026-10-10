package com.tokalot.app

/** Thin JNI wrapper around whisper.cpp. Calls on one context must all come from one background thread. */
object WhisperBridge {
    init {
        System.loadLibrary("offlineflow")
    }

    external fun init(modelPath: String): Long

    /**
     * The text as UTF-8 bytes. [abort] is a flag from [newAbort]. Throws IllegalStateException if Whisper
     * fails (or was aborted), so an error is never mistaken for silence.
     */
    external fun transcribe(ctx: Long, abort: Long, samples: FloatArray, threads: Int, prompt: String): ByteArray
    external fun free(ctx: Long)

    /**
     * A new cancel flag for [transcribe]. A few bytes of native memory that are never freed, so a late
     * [setAbort] can't touch freed memory: take one per [Dictation], not one per call.
     */
    external fun newAbort(): Long

    /** The one call that's safe from any thread. While set, transcribe() with that flag gives up early. */
    external fun setAbort(abort: Long, on: Boolean)
}
