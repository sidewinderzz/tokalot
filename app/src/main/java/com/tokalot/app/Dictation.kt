package com.tokalot.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors

/**
 * The whole speech -> text pipeline, run on one background thread:
 *   1. speech-to-text: cloud (Groq/OpenAI) if a key is set, else on-device Whisper
 *   2. snippets swapped for placeholders
 *   3. AI cleanup if a key is set, else basic filler removal
 *   4. snippets restored, saved to history, usage counted
 * Every cloud step falls back to the local path instead of failing.
 * A dictation can be cancelled, and gives up by itself after a deadline that grows with
 * the audio length; either way the recording is kept in history so it can be retried.
 */
class Dictation(context: Context) {
    private val app = context.applicationContext
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var whisperCtx = 0L
    @Volatile private var localRunning = false
    private val unload = Runnable { exec.execute { releaseLocal() } }
    private var current: Job? = null // main thread only

    class Outcome(val text: String, val warning: String?, val entryId: Long? = null)

    companion object {
        private const val IDLE_UNLOAD_MS = 60_000L
        /** From this length on, the audio is compressed before it's uploaded (see [run]). */
        private const val COMPRESS_FROM_S = 30
        /** The error onDone gets after cancel(). */
        const val CANCELLED = "Cancelled"
        const val TIMED_OUT = "Timed out"
    }

    /** One process() call: what cancel() and the deadline act on. */
    private inner class Job(val onDone: (Outcome?, String?) -> Unit) {
        val call = Call()
        /** CANCELLED or TIMED_OUT once the job was stopped from outside. */
        @Volatile var halted: String? = null
        private var delivered = false // main thread only
        val deadline = Runnable { halt(TIMED_OUT) }

        /** Main thread. Stops the work and reports [reason] right away, without waiting for the worker. */
        fun halt(reason: String) {
            if (delivered) return
            halted = reason
            call.cancel()
            if (localRunning) WhisperBridge.setAbort(true)
            deliver(null, reason)
        }

        /** Main thread. Only the first result counts: a late worker result after a cancel is dropped. */
        fun deliver(outcome: Outcome?, error: String?) {
            if (delivered) return
            delivered = true
            main.removeCallbacks(deadline)
            if (current === this) current = null
            onDone(outcome, error)
            if (!exec.isShutdown) main.postDelayed(unload, IDLE_UNLOAD_MS)
        }
    }

    /** Opens connections to the chosen providers in the background while the user talks. */
    fun warmUp() {
        val prefs = Prefs(app)
        Thread {
            if (prefs.cloudSttReady) {
                val c = prefs.stt
                Net.warm("${c.baseUrl}/models", mapOf("Authorization" to "Bearer ${prefs.key(c.service)}"))
            }
            if (prefs.cleanupReady) {
                val c = prefs.cleanup
                val h = if (c == CleanupChoice.CLAUDE)
                    mapOf("x-api-key" to prefs.key(c.service), "anthropic-version" to "2023-06-01")
                else mapOf("Authorization" to "Bearer ${prefs.key(c.service)}")
                // Skip if it's the same host we just warmed.
                if (!(prefs.cloudSttReady && prefs.stt.baseUrl == c.baseUrl)) Net.warm("${c.baseUrl}/models", h)
            }
        }.start()
    }

    /** Main thread. Aborts the dictation in progress, if any; its onDone gets [CANCELLED]. */
    fun cancel() { current?.halt(CANCELLED) }

    /**
     * Call on the main thread. onDone runs on the main thread with either an outcome or an error message.
     * sparse: barely any sound was heard, so a stock Whisper phrase ("Thank you.") is treated as silence.
     * retry: the failed or cancelled history entry these samples belong to; the result replaces it.
     */
    fun process(
        samples: FloatArray, appPkg: String?, sparse: Boolean = false, retry: Entry? = null,
        onDone: (Outcome?, String?) -> Unit,
    ) {
        main.removeCallbacks(unload)
        val job = Job(onDone)
        current = job
        val audioMs = samples.size * 1000L / Recorder.SAMPLE_RATE
        main.postDelayed(job.deadline, Timeouts.deadlineMs(audioMs))
        exec.execute {
            val id = retry?.id ?: System.currentTimeMillis()
            var result: Outcome? = null
            var failure: String? = null
            try {
                result = run(job, id, samples, appPkg, sparse, retry)
            } catch (t: Throwable) {
                failure = t.message ?: "Transcription failed"
            }
            // A cancel or timeout wins, unless the transcript was already saved when it came.
            val halted = job.halted
            val stopped = halted != null && result?.entryId == null
            val outcome = if (stopped) null else result
            val error = if (stopped) halted else failure
            main.post { job.deliver(outcome, error) }

            // After the text is delivered: keep the audio so it can be replayed.
            val keepDays = Prefs(app).audioKeepDays
            val saved = AudioStore.exists(app, id)
            when {
                // No transcript: the recording gets an entry and is kept, whatever the retention
                // setting says, until it's retried or deleted.
                error != null -> {
                    val cancelled = error == CANCELLED
                    History.put(
                        app,
                        Entry(
                            id, retry?.time ?: id, if (cancelled) "Transcription cancelled" else "Transcription failed: $error",
                            "", audioMs, false, appPkg ?: "", if (cancelled) History.CANCELLED else History.FAILED
                        )
                    )
                    if (!saved && AudioStore.save(app, id, samples)) History.notifyChanged()
                }
                outcome?.entryId != null -> {
                    if (keepDays > 0) {
                        if (!saved && AudioStore.save(app, id, samples)) History.notifyChanged()
                    } else if (saved) {
                        AudioStore.delete(app, id) // only existed for the upload or the retry
                        History.notifyChanged()
                    }
                }
                // Nothing was said: drop the copy made for the upload.
                saved && retry == null -> AudioStore.delete(app, id)
            }
            AudioStore.prune(app, keepDays)
        }
    }

    private fun run(job: Job, id: Long, samples: FloatArray, appPkg: String?, sparse: Boolean, retry: Entry?): Outcome {
        val call = job.call
        val prefs = Prefs(app)
        val category = AppContext.categorize(appPkg, prefs)
        val appLabel = appPkg?.let { AppContext.label(app, it) }
        val warnings = ArrayList<String>()
        // Whisper's prompt holds ~224 tokens; keep the hint well under that (newest words win).
        // The cleanup model still gets the full dictionary.
        val hint = buildString {
            for (w in prefs.words.asReversed()) {
                if (length + w.length + 2 > 600) break
                if (isNotEmpty()) append(", ")
                append(w)
            }
        }
        val seconds = samples.size.toDouble() / Recorder.SAMPLE_RATE
        val audioMs = (seconds * 1000).toLong()

        // 1. Speech to text: chosen cloud provider, then any other cloud provider with a key, then on-device.
        val order = sttOrder(prefs)
        // Long recordings go up as the small AAC file (about a tenth of the WAV) so a slow
        // connection doesn't time out. Short ones stay WAV: encoding first would only add delay.
        var m4a = AudioStore.file(app, id).takeIf { it.exists() }
        if (m4a == null && order.isNotEmpty() && seconds >= COMPRESS_FROM_S && AudioStore.save(app, id, samples)) {
            m4a = AudioStore.file(app, id)
        }
        var raw: String? = null
        var usedLocal = false
        for (c in order) {
            call.check()
            try {
                raw = transcribeCloud(prefs, c, samples, m4a, hint, audioMs, call)
                Usage.recordStt(app, c, seconds)
                if (c != prefs.stt) warnings += "${prefs.stt.label} unavailable, used ${c.label}"
                break
            } catch (e: Exception) {
                call.check()
                if (warnings.isEmpty()) warnings += "${c.label} failed: ${e.message?.take(80)}"
            }
        }
        if (raw == null) {
            if (!ModelManager.isReady(app)) {
                throw IllegalStateException(
                    if (warnings.isEmpty()) "No speech model: add a Groq key or download the on-device model"
                    else warnings.first() + ", but the on-device model isn't downloaded"
                )
            }
            raw = transcribeLocal(job, samples, hint)
            usedLocal = true
        }
        val base = TextTools.stripNoise(raw)
        if (base.isBlank() || (sparse && TextTools.isPhantom(base))) return Outcome("", warnings.firstOrNull())

        // 2-4. Snippets + cleanup
        val snippets = prefs.snippets
        val (protectedText, map) = TextTools.protect(base, snippets)
        var cleaned = false
        var finalText: String? = null
        var cleanupErr: String? = null
        for (c in cleanupOrder(prefs)) {
            call.check()
            try {
                val r = Cleanup.run(prefs, c, protectedText, map.isNotEmpty(), category, appLabel, call)
                Usage.recordLlm(app, c, r.inTokens, r.outTokens)
                cleaned = true
                finalText = TextTools.restore(r.text, map)
                if (c != prefs.cleanup) warnings += "${prefs.cleanup.label} unavailable, cleaned with ${c.label}"
                break
            } catch (e: Exception) {
                call.check()
                if (cleanupErr == null) cleanupErr = "Cleanup failed: ${e.message?.take(80)}"
            }
        }
        if (!cleaned && cleanupErr != null) warnings += cleanupErr
        if (finalText == null) finalText = TextTools.restore(TextTools.basicClean(protectedText), map)

        call.check() // cancelled at the last moment: don't save a transcript nobody will get
        History.put(app, Entry(id, retry?.time ?: id, finalText, base, audioMs, cleaned, appPkg ?: ""))
        Usage.recordDictation(app, TextTools.wordCount(finalText), usedLocal)
        Usage.recordEdits(app, AppContext.fillerCount(base), if (cleaned) AppContext.correctionCount(base) else 0)
        return Outcome(finalText, warnings.firstOrNull(), id)
    }

    /** Sends the compressed file when there is one; if the provider rejects the format, sends WAV instead. */
    private fun transcribeCloud(
        prefs: Prefs, c: SttChoice, samples: FloatArray, m4a: File?, hint: String, audioMs: Long, call: Call,
    ): String {
        fun send(audio: Upload) = CloudStt.transcribe(
            c.baseUrl, prefs.key(c.service), prefs.sttModel(c), audio, hint,
            english = !prefs.autoLanguage, audioMs = audioMs, call = call
        )
        if (m4a != null) {
            try {
                return send(Upload.m4a(m4a))
            } catch (e: HttpException) {
                if (e.code != 400 && e.code != 415) throw e
            }
        }
        return send(Upload.wav(samples))
    }

    /** Chosen cloud STT first (if keyed), then the other keyed cloud options. Empty = on-device. */
    private fun sttOrder(p: Prefs): List<SttChoice> {
        if (p.stt == SttChoice.LOCAL) return emptyList()
        return (listOf(p.stt) + SttChoice.values().filter { it != p.stt })
            .filter { it != SttChoice.LOCAL && p.key(it.service).isNotEmpty() }
    }

    /** Chosen cleanup first, then any other provider with a key as backup. Empty = basic cleanup. */
    private fun cleanupOrder(p: Prefs): List<CleanupChoice> {
        if (p.cleanup == CleanupChoice.OFF) return emptyList()
        return (listOf(p.cleanup) + CleanupChoice.values().filter { it != p.cleanup })
            .filter { it != CleanupChoice.OFF && p.key(it.service).isNotEmpty() }
            .take(2) // one backup is enough; more just adds latency on a bad day
    }

    // ---------- on-device Whisper (only touched from the exec thread) ----------

    private fun transcribeLocal(job: Job, samples: FloatArray, hint: String): String {
        if (whisperCtx == 0L) {
            whisperCtx = WhisperBridge.init(ModelManager.modelFile(app).absolutePath)
            if (whisperCtx == 0L) {
                // A file Whisper can't open is useless; remove it so Settings offers the download again.
                ModelManager.modelFile(app).delete()
                throw IllegalStateException("The on-device model couldn't be loaded. Download it again in Settings.")
            }
        }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        // Order matters: halt() sets the cancel flag first and the abort flag second, so a
        // cancel that lands anywhere around these lines is caught by one of the two checks.
        localRunning = true
        try {
            WhisperBridge.setAbort(false)
            job.call.check()
            val text = WhisperBridge.transcribe(whisperCtx, samples, threads, hint)
            job.call.check()
            return text
        } finally {
            localRunning = false
        }
    }

    private fun releaseLocal() {
        if (whisperCtx != 0L) {
            WhisperBridge.free(whisperCtx)
            whisperCtx = 0L
        }
    }

    fun shutdown() {
        main.removeCallbacks(unload)
        exec.execute { releaseLocal() }
        exec.shutdown()
    }
}
