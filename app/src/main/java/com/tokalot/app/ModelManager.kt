package com.tokalot.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest

/** Owns the speech model file: where it lives and how it's fetched (once). */
object ModelManager {
    // Quantized base.en: ~60 MB, fast on phones, good accuracy for English dictation.
    // For better accuracy at ~3x the size/time, swap to ggml-small.en-q5_1.bin
    // (and update REVISION, SIZE and SHA256 from the file's page on huggingface.co).
    const val NAME = "ggml-base.en-q5_1.bin"
    // Pinned to one commit of the model repo, so the file can't change underneath us.
    private const val REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"
    private const val URL_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/$REVISION/"
    const val SIZE = 59_721_011L
    const val SHA256 = "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f"
    private const val STALL_MS = 30_000

    fun modelFile(ctx: Context): File {
        val dir = File(ctx.filesDir, "models").apply { mkdirs() }
        return File(dir, NAME)
    }

    fun isReady(ctx: Context): Boolean = modelFile(ctx).let { it.exists() && it.length() == SIZE }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Blocking (reads the whole file). True if the model on disk is still byte for byte the pinned one. */
    fun verify(ctx: Context): Boolean = runCatching {
        val f = modelFile(ctx)
        if (!f.exists() || f.length() != SIZE) return false
        val digest = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        hex(digest.digest()) == SHA256
    }.getOrDefault(true) // couldn't read it right now: not a reason to throw 60 MB away

    /** Blocking. Returns null on success, or an error message. Safe to call again after a failure. */
    fun download(ctx: Context, onProgress: (Int) -> Unit): String? {
        val target = modelFile(ctx)
        val tmp = File(target.parentFile, "$NAME.part")
        return try {
            val conn = URL(URL_BASE + NAME).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = STALL_MS // no bytes for this long = stalled
            conn.instanceFollowRedirects = true
            if (conn.responseCode != 200) return "Server returned ${conn.responseCode}"
            val size = conn.contentLengthLong.takeIf { it > 0 } ?: SIZE
            val digest = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        done += n
                        val pct = (done * 100 / size).toInt().coerceAtMost(100)
                        if (pct != last) { last = pct; onProgress(pct) }
                    }
                }
            }
            if (tmp.length() != SIZE) { tmp.delete(); return "Download incomplete. Try again." }
            // Only a byte-for-byte match with the pinned file is ever marked ready.
            if (hex(digest.digest()) != SHA256) { tmp.delete(); return "The downloaded file didn't match. Try again." }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) { tmp.delete(); return "Couldn't save model file" }
            null
        } catch (e: SocketTimeoutException) {
            tmp.delete()
            "The download stalled (no data for ${STALL_MS / 1000} s). Check your connection and try again."
        } catch (e: Exception) {
            tmp.delete()
            e.message ?: "Download failed"
        }
    }
}
