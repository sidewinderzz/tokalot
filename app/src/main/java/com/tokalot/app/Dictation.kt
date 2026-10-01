package com.tokalot.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * The whole speech -> text pipeline, run on one background thread:
 *   1. speech-to-text: cloud (Groq/OpenAI) if a key is set, else on-device Whisper
 *   2. snippets swapped for placeholders
 *   3. AI cleanup if a key is set, else basic filler removal
 *   4. snippets restored, saved to history, usage counted
 * Every cloud step falls back to the local path instead of failing.
 */
class Dictation(context: Context) {
    private val app = context.applicationContext
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var whisperCtx = 0L
    private val unload = Runnable { exec.execute { releaseLocal() } }

    class Outcome(val text: String, val warning: String?, val entryId: Long? = null)

    companion object {
        private const val IDLE_UNLOAD_MS = 60_000L
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

    /**
     * onDone runs on the main thread with either an outcome or an error message.
     * sparse: barely any sound was heard, so a stock Whisper phrase ("Thank you.") is treated as silence.
     */
    fun process(samples: FloatArray, appPkg: String?, sparse: Boolean = false, onDone: (Outcome?, String?) -> Unit) {
        main.removeCallbacks(unload)
        exec.execute {
            var outcome: Outcome? = null
            var error: String? = null
            try {
                outcome = run(samples, appPkg, sparse)
            } catch (t: Throwable) {
                error = t.message ?: "Transcription failed"
            }
            main.post {
                onDone(outcome, error)
                if (!exec.isShutdown) main.postDelayed(unload, IDLE_UNLOAD_MS)
            }
            // After the text is delivered: keep the audio so it can be replayed.
            // A failed transcription still gets an entry, so the recording isn't lost.
            val keepDays = Prefs(app).audioKeepDays
            var id = outcome?.entryId
            if (id == null && error != null) {
                val now = System.currentTimeMillis()
                History.add(app, Entry(now, now, "Transcription failed: $error", "", samples.size * 1000L / Recorder.SAMPLE_RATE, false, appPkg ?: ""))
                id = now
            }
            if (id != null && keepDays > 0) {
                AudioStore.save(app, id, samples)
                History.notifyChanged()
            }
            AudioStore.prune(app, keepDays)
        }
    }

    private fun run(samples: FloatArray, appPkg: String?, sparse: Boolean): Outcome {
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

        // 1. Speech to text: chosen cloud provider, then any other cloud provider with a key, then on-device.
        var raw: String? = null
        var usedLocal = false
        for (c in sttOrder(prefs)) {
            try {
                raw = CloudStt.transcribe(c.baseUrl, prefs.key(c.service), prefs.sttModel(c), samples, hint, english = !prefs.autoLanguage)
                Usage.recordStt(app, c, seconds)
                if (c != prefs.stt) warnings += "${prefs.stt.label} unavailable, used ${c.label}"
                break
            } catch (e: Exception) {
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
            raw = transcribeLocal(samples, hint)
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
            try {
                val r = Cleanup.run(prefs, c, protectedText, map.isNotEmpty(), category, appLabel)
                Usage.recordLlm(app, c, r.inTokens, r.outTokens)
                cleaned = true
                finalText = TextTools.restore(r.text, map)
                if (c != prefs.cleanup) warnings += "${prefs.cleanup.label} unavailable, cleaned with ${c.label}"
                break
            } catch (e: Exception) {
                if (cleanupErr == null) cleanupErr = "Cleanup failed: ${e.message?.take(80)}"
            }
        }
        if (!cleaned && cleanupErr != null) warnings += cleanupErr
        if (finalText == null) finalText = TextTools.restore(TextTools.basicClean(protectedText), map)

        val now = System.currentTimeMillis()
        History.add(app, Entry(now, now, finalText, base, (seconds * 1000).toLong(), cleaned, appPkg ?: ""))
        Usage.recordDictation(app, TextTools.wordCount(finalText), usedLocal)
        Usage.recordEdits(app, AppContext.fillerCount(base), if (cleaned) AppContext.correctionCount(base) else 0)
        return Outcome(finalText, warnings.firstOrNull(), now)
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

    private fun transcribeLocal(samples: FloatArray, hint: String): String {
        if (whisperCtx == 0L) {
            whisperCtx = WhisperBridge.init(ModelManager.modelFile(app).absolutePath)
            if (whisperCtx == 0L) throw IllegalStateException("Couldn't load the on-device model")
        }
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        return WhisperBridge.transcribe(whisperCtx, samples, threads, hint)
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
