package com.tokalot.app

/** Thin JNI wrapper around whisper.cpp. Calls on one context must all come from one background thread. */
object WhisperBridge {
    /** True when the fast build (ARMv8.2 dot product + half precision) was loaded, for the timings line. */
    var fast = false
        private set

    init {
        fast = cpuHasFastMaths() && runCatching { System.loadLibrary("offlineflow_fast") }.isSuccess
        if (!fast) System.loadLibrary("offlineflow")
    }

    /**
     * Whether every core has the dot-product and half-precision instructions the fast build uses ("asimddp" and
     * "asimdhp" in /proc/cpuinfo). Nearly every phone from 2018 on does; without them the fast build would crash.
     */
    private fun cpuHasFastMaths(): Boolean = runCatching {
        val features = java.io.File("/proc/cpuinfo").readLines().filter { it.startsWith("Features") }
        features.isNotEmpty() && features.all { line ->
            val f = line.substringAfter(':').trim().split(Regex("\\s+")).toSet()
            "asimddp" in f && "asimdhp" in f
        }
    }.getOrDefault(false)

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
