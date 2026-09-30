package com.tokalot.app

/** Thin JNI wrapper around whisper.cpp. All calls must come from one background thread. */
object WhisperBridge {
    init {
        System.loadLibrary("offlineflow")
    }

    external fun init(modelPath: String): Long
    external fun transcribe(ctx: Long, samples: FloatArray, threads: Int, prompt: String): String
    external fun free(ctx: Long)
}
