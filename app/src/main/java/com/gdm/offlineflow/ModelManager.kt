package com.gdm.offlineflow

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Owns the speech model file: where it lives and how it's fetched (once). */
object ModelManager {
    // Quantized base.en: ~60 MB, fast on phones, good accuracy for English dictation.
    // For better accuracy at ~3x the size/time, swap to ggml-small.en-q5_1.bin.
    const val NAME = "ggml-base.en-q5_1.bin"
    private const val URL_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"

    fun modelFile(ctx: Context): File {
        val dir = File(ctx.filesDir, "models").apply { mkdirs() }
        return File(dir, NAME)
    }

    fun isReady(ctx: Context): Boolean = modelFile(ctx).let { it.exists() && it.length() > 10_000_000 }

    /** Blocking. Returns null on success, or an error message. */
    fun download(ctx: Context, onProgress: (Int) -> Unit): String? {
        val target = modelFile(ctx)
        val tmp = File(target.parentFile, "$NAME.part")
        return try {
            val conn = URL(URL_BASE + NAME).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.instanceFollowRedirects = true
            if (conn.responseCode != 200) return "Server returned ${conn.responseCode}"
            val size = conn.contentLengthLong
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (size > 0) {
                            val pct = (done * 100 / size).toInt()
                            if (pct != last) { last = pct; onProgress(pct) }
                        }
                    }
                }
            }
            if (tmp.length() < 10_000_000) return "Download incomplete"
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) return "Couldn't save model file"
            null
        } catch (e: Exception) {
            tmp.delete()
            e.message ?: "Download failed"
        }
    }
}
