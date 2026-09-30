package com.gdm.offlineflow

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * Loads the model lazily on first use and frees it after a minute of inactivity,
 * so the ~100 MB of model memory isn't held (and nothing runs) while you're not dictating.
 */
class Transcriber(private val appContext: Context) {
    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var ctx = 0L
    private val unload = Runnable { exec.execute { release() } }

    companion object {
        private const val IDLE_UNLOAD_MS = 60_000L
        private val NOISE = Regex("""\[[^\]]*]|\([^)]*\)|\*[^*]*\*""")      // [BLANK_AUDIO], (music), *laughs*
        private val FILLERS = Regex("""(?i)\b(?:um+|uh+|erm?|hmm+)\b[,.]?\s*""")
        private val SPACES = Regex("""\s+""")

        fun clean(raw: String): String =
            raw.replace(NOISE, " ").replace(FILLERS, "").replace(SPACES, " ").trim()
    }

    /** Callback runs on the main thread: (text, error). Exactly one is non-null. */
    fun transcribe(samples: FloatArray, onDone: (String?, String?) -> Unit) {
        main.removeCallbacks(unload)
        exec.execute {
            var text: String? = null
            var err: String? = null
            try {
                if (ctx == 0L) {
                    val f = ModelManager.modelFile(appContext)
                    if (!f.exists()) err = "Model not downloaded yet"
                    else {
                        ctx = WhisperBridge.init(f.absolutePath)
                        if (ctx == 0L) err = "Couldn't load model"
                    }
                }
                if (err == null) {
                    val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
                    text = clean(WhisperBridge.transcribe(ctx, samples, threads))
                }
            } catch (t: Throwable) {
                err = t.message ?: "Transcription failed"
            }
            main.post {
                onDone(text, err)
                main.postDelayed(unload, IDLE_UNLOAD_MS)
            }
        }
    }

    private fun release() {
        if (ctx != 0L) {
            WhisperBridge.free(ctx)
            ctx = 0L
        }
    }

    fun shutdown() {
        main.removeCallbacks(unload)
        exec.execute { release() }
        exec.shutdown()
    }
}
