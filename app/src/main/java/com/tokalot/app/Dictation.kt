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

    /** How quick the connection was when this dictation started ([Net.rtt], milliseconds), or -1 if not measured. */
    @Volatile var pingMs = -1
        private set

    /** For the progress ring: 0 while the speech is being recognised, 1 during cleanup, 2 when the text is ready. */
    @Volatile var stage = 0
        private set
    /** How long the recognised text is, once [stage] is 1 (the cleanup's wait grows with it). */
    @Volatile var stageChars = 0
        private set

    /**
     * plain: the user's own wording, cleaned up without AI. Only set when "Polish my wording" is on,
     * the AI cleanup ran, and it differs from [text]; it is what "My wording" puts back.
     */
    class Outcome(val text: String, val warning: String?, val entryId: Long? = null, val plain: String? = null)

    companion object {
        private const val IDLE_UNLOAD_MS = 60_000L
        /** From this length on, the audio is compressed to AAC before it's uploaded (see [run]). */
        private const val COMPRESS_FROM_S = 30
        /** On a slow connection ([Link.slow]) that starts here instead: the upload is the wait. */
        private const val COMPRESS_FROM_S_SLOW = 1.5
        /** The error onDone gets after cancel(). */
        const val CANCELLED = "Cancelled"
        const val TIMED_OUT = "Timed out"
    }

    /** One process() call: what cancel() and the deadline act on. */
    private inner class Job(val live: LiveStt?, val onDone: (Outcome?, String?) -> Unit) {
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
            live?.cancel()
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
        pingMs = -1
        Thread {
            if (prefs.cloudSttReady) {
                val c = prefs.stt
                val h = mapOf("Authorization" to "Bearer ${prefs.key(c.service)}")
                Net.warm("${c.baseUrl}/models", h)
                pingMs = Net.rtt("${c.baseUrl}/models", h) // on the connection just opened: the link as it is right now
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

    /**
     * For a recording that is about to start: sends it for transcription in pieces while the user talks
     * (see [LiveStt]). Null when that's switched off, or there is no cloud speech service or no AI cleanup
     * (cleanup is what smooths the joins between pieces).
     */
    fun newLive(): LiveStt? {
        val prefs = Prefs(app)
        if (!prefs.liveStt || !prefs.cloudSttReady || !prefs.cleanupReady) return null
        val c = prefs.stt
        val hint = hint(prefs)
        return LiveStt { piece, call ->
            sendPcm(prefs, c, piece, hint, piece.size * 1000L / Recorder.SAMPLE_RATE, call, small = Link.slow(app))
        }
    }

    /** The dictionary as a hint for the speech model. Its prompt holds ~224 tokens; this stays well under that (newest words win). */
    private fun hint(prefs: Prefs) = buildString {
        for (w in prefs.words.asReversed()) {
            if (length + w.length + 2 > 600) break
            if (isNotEmpty()) append(", ")
            append(w)
        }
    }

    /** Main thread. Aborts the dictation in progress, if any; its onDone gets [CANCELLED]. */
    fun cancel() { current?.halt(CANCELLED) }

    /**
     * Call on the main thread. onDone runs on the main thread with either an outcome or an error message.
     * sparse: barely any sound was heard, so a stock Whisper phrase ("Thank you.") is treated as silence.
     * retry: the failed or cancelled history entry these samples belong to; the result replaces it.
     * live: the pieces of this recording already sent off while the user was talking, if any.
     */
    fun process(
        samples: FloatArray, appPkg: String?, sparse: Boolean = false, retry: Entry? = null, live: LiveStt? = null,
        onDone: (Outcome?, String?) -> Unit,
    ) {
        main.removeCallbacks(unload)
        stage = 0
        val job = Job(live, onDone)
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

            // After the text is delivered: keep the audio so it can be replayed. (Storage may be full: never crash here.)
            runCatching {
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
    }

    private fun run(job: Job, id: Long, samples: FloatArray, appPkg: String?, sparse: Boolean, retry: Entry?): Outcome {
        val call = job.call
        val prefs = Prefs(app)
        val category = AppContext.categorize(appPkg, prefs)
        val appLabel = appPkg?.let { AppContext.label(app, it) }
        val warnings = ArrayList<String>()
        // The cleanup model still gets the full dictionary.
        val hint = hint(prefs)
        val seconds = samples.size.toDouble() / Recorder.SAMPLE_RATE
        val audioMs = (seconds * 1000).toLong()
        val began = System.nanoTime()

        // 1. Speech to text: the pieces sent while talking, if there are any; otherwise the chosen cloud
        // provider, then any other cloud provider with a key, then on-device.
        var raw: String? = null
        val live = job.live
        if (live != null) {
            raw = live.finish(samples)
            call.check()
            if (raw != null) Usage.recordStt(app, prefs.stt, seconds)
        }
        val inPieces = raw != null
        val order = if (inPieces) emptyList() else sttOrder(prefs)
        // Long recordings go up as the small AAC file (about a tenth of the WAV) so a slow
        // connection doesn't time out. Short ones stay WAV: encoding first would only add delay.
        var m4a = AudioStore.file(app, id).takeIf { it.exists() }
        val compressFrom = if (order.isNotEmpty() && Link.slow(app)) COMPRESS_FROM_S_SLOW else COMPRESS_FROM_S.toDouble()
        if (m4a == null && order.isNotEmpty() && seconds >= compressFrom && AudioStore.save(app, id, samples)) {
            m4a = AudioStore.file(app, id)
        }
        var usedLocal = false
        var offline = false // the speech service couldn't be reached at all
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
                // No answer at all (rather than an error from the service): the connection is the problem, and the
                // next provider would only wait out the same timeout. Use the phone's own model if it's there.
                if (e !is HttpException && ModelManager.isReady(app)) { offline = true; break }
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
        val sttMs = (System.nanoTime() - began) / 1_000_000
        val base = TextTools.stripNoise(raw)
        if (base.isBlank() || (sparse && TextTools.isPhantom(base))) return Outcome("", warnings.firstOrNull())

        stageChars = base.length
        stage = 1

        // 2-4. Snippets + cleanup
        val snippets = prefs.snippets
        val (protectedText, map) = TextTools.protect(base, snippets)
        val polish = prefs.polish
        var cleaned = false
        var finalText: String? = null
        var cleanupErr: String? = null
        // Quick mode: a short dictation with nothing for the AI to fix skips it and is tidied here, at once.
        val quick = prefs.quickSkip && !polish && !inPieces && prefs.cleanup != CleanupChoice.OFF &&
            TextTools.nothingToFix(base, map.isNotEmpty(), prefs.styleFor(category).name, category.name, prefs.customInstructions)
        // With the connection down, waiting on the cleanup service too would only use up the time limit: basic cleanup.
        for (c in if (offline || quick) emptyList() else cleanupOrder(prefs)) {
            call.check()
            try {
                val r = Cleanup.run(prefs, c, protectedText, map.isNotEmpty(), category, appLabel, call, polish, inPieces)
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
        val basic = TextTools.restore(TextTools.basicClean(protectedText), map)
        if (finalText == null) finalText = basic
        val plain = basic.takeIf { polish && cleaned && it != finalText }

        stage = 2
        runCatching {
            val allMs = (System.nanoTime() - began) / 1_000_000
            // What this one took feeds the ring's estimate for the next (a slow connection shows up within a few dictations).
            val kind = Link.kind(app)
            val ping = pingMs
            if (!usedLocal && !inPieces) prefs.learnRate("stt_$kind", (sttMs / seconds.coerceAtLeast(3.0)).toFloat())
            if (cleaned) prefs.learnRate("clean_$kind", (allMs - sttMs) / base.length.coerceAtLeast(40).toFloat())
            if (ping > 0) prefs.learnRate("ping_$kind", ping.toFloat())
            val link = (if (kind == "cell") " · mobile data" else " · Wi-Fi") + if (ping > 0) ", ping $ping ms" else ""
            val us = java.util.Locale.US
            val line = "%.1f s spoken · speech %.2f s".format(us, seconds, sttMs / 1000.0) +
                (if (inPieces) " (${live?.pieceCount} pieces)" else if (usedLocal) " (on device)" else "") +
                when {
                    quick -> " · cleanup skipped, nothing to fix"
                    cleaned -> " · cleanup %.2f s".format(us, (allMs - sttMs) / 1000.0)
                    prefs.cleanup == CleanupChoice.OFF -> " · cleanup off"
                    offline -> " · no connection, basic cleanup"
                    else -> " · cleanup failed after %.2f s".format(us, (allMs - sttMs) / 1000.0)
                } + " · total %.2f s".format(us, allMs / 1000.0) + link
            android.util.Log.i("Tokalot", "Dictation: $line")
            val stamp = java.text.SimpleDateFormat("HH:mm:ss", us).format(java.util.Date())
            prefs.speedLog = listOf("$stamp  $line") + prefs.speedLog
        }
        call.check() // cancelled at the last moment: don't save a transcript nobody will get
        // A history file that can't be written (storage full) must not stop the text from being typed.
        runCatching { History.put(app, Entry(id, retry?.time ?: id, finalText, base, audioMs, cleaned, appPkg ?: "")) }
        Usage.recordDictation(app, TextTools.wordCount(finalText), usedLocal)
        Usage.recordEdits(app, AppContext.fillerCount(base), if (cleaned) AppContext.correctionCount(base) else 0)
        return Outcome(finalText, warnings.firstOrNull(), id, plain)
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
        return sendPcm(prefs, c, samples, hint, audioMs, call)
    }

    /**
     * Sends the audio as FLAC (the same sound in about half the bytes); a provider that won't take it gets WAV.
     * small: the connection is slow, so try AAC first (a fifth of the FLAC).
     */
    private fun sendPcm(
        prefs: Prefs, c: SttChoice, samples: FloatArray, hint: String, audioMs: Long, call: Call, small: Boolean = false,
    ): String {
        fun send(audio: Upload) = CloudStt.transcribe(
            c.baseUrl, prefs.key(c.service), prefs.sttModel(c), audio, hint,
            english = !prefs.autoLanguage, audioMs = audioMs, call = call
        )
        if (small) {
            AudioStore.aac(app, samples)?.let { bytes ->
                try {
                    return send(Upload.m4a(bytes))
                } catch (e: HttpException) {
                    if (e.code != 400 && e.code != 415) throw e
                }
            }
        }
        if (flacRefused != c.baseUrl) {
            try {
                return send(Upload.flac(samples))
            } catch (e: HttpException) {
                if (!formatRefused(e)) throw e
                flacRefused = c.baseUrl
            }
        }
        try {
            return send(Upload.wav(samples))
        } catch (e: HttpException) {
            // The WAV was refused too, so the format wasn't the problem: try FLAC again next time.
            if (e.code == 400 || e.code == 415) flacRefused = null
            throw e
        }
    }

    @Volatile private var flacRefused: String? = null // the service that last turned FLAC down

    /** A 400 can be about anything (a retired model, a blocked account); only one about the audio file means "send WAV". */
    private fun formatRefused(e: HttpException): Boolean {
        if (e.code == 415) return true
        if (e.code != 400) return false
        val m = e.message.orEmpty().lowercase()
        return listOf("file", "format", "audio", "decod", "media").any { it in m }
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
