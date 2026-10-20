package com.tokalot.app

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest

/**
 * The on-device speech models to choose from. All are whisper.cpp files from one pinned commit, so a
 * file can't change underneath us; SIZE and SHA256 come from each file's page on huggingface.co.
 * Bigger is more accurate (most of all with background noise) and slower.
 */
enum class LocalModel(
    val id: String, val file: String, val size: Long, val sha256: String,
    val label: String, val note: String,
) {
    BASE(
        "base", "ggml-base.en-q5_1.bin", 59_721_011L,
        "4baf70dd0d7c4247ba2b81fafd9c01005ac77c2f9ef064e00dcf195d0e2fdd2f",
        "Standard (60 MB)", "Fast on any phone. Fine for clear speech in a quiet place.",
    ),
    SMALL(
        "small", "ggml-small.en-q5_1.bin", 190_098_681L,
        "bfdff4894dcb76bbf647d56263ea2a96645423f1669176f4844a1bf8e478ad30",
        "Better (190 MB)", "Noticeably fewer mistakes, more so with background noise. About 3x slower.",
    ),
    TURBO(
        "turbo", "ggml-large-v3-turbo-q5_0.bin", 574_041_195L,
        "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2",
        "Best (574 MB)", "The same model as Groq Whisper, compressed. Close to cloud accuracy. Needs a recent high-end phone.",
    );

    /** Bigger models get more of the phone's cores; the small one doesn't need them and saves battery. */
    fun threads(cores: Int): Int = if (this == BASE) cores.coerceIn(2, 4) else (cores - 2).coerceIn(2, 6)

    companion object {
        fun of(id: String?): LocalModel = values().firstOrNull { it.id == id } ?: BASE
    }
}

/** Owns the speech model files: where they live and how they're fetched (once). */
object ModelManager {
    // Pinned to one commit of the model repo, so the files can't change underneath us.
    private const val REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"
    private const val URL_BASE = "https://huggingface.co/ggerganov/whisper.cpp/resolve/$REVISION/"
    private const val STALL_MS = 30_000

    fun modelFile(ctx: Context, m: LocalModel): File {
        val dir = File(ctx.filesDir, "models").apply { mkdirs() }
        return File(dir, m.file)
    }

    fun isDownloaded(ctx: Context, m: LocalModel): Boolean = modelFile(ctx, m).let { it.exists() && it.length() == m.size }

    /**
     * The model a dictation uses: the one picked in Settings if it's downloaded, otherwise the best one that is
     * (so picking a bigger model doesn't stop offline working while it downloads). Null if none is.
     */
    fun active(ctx: Context): LocalModel? {
        val chosen = Prefs(ctx).localModel
        if (isDownloaded(ctx, chosen)) return chosen
        return LocalModel.values().reversed().firstOrNull { isDownloaded(ctx, it) }
    }

    fun isReady(ctx: Context): Boolean = active(ctx) != null

    fun delete(ctx: Context, m: LocalModel) { modelFile(ctx, m).delete() }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /** Blocking (reads the whole file). True if the model on disk is still byte for byte the pinned one. */
    fun verify(ctx: Context, m: LocalModel): Boolean = runCatching {
        val f = modelFile(ctx, m)
        if (!f.exists() || f.length() != m.size) return false
        val digest = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        hex(digest.digest()) == m.sha256
    }.getOrDefault(true) // couldn't read it right now: not a reason to throw the file away

    /** Blocking. Returns null on success, or an error message. Safe to call again after a failure. */
    fun download(ctx: Context, m: LocalModel, onProgress: (Int) -> Unit): String? {
        val target = modelFile(ctx, m)
        val tmp = File(target.parentFile, "${m.file}.part")
        return try {
            val conn = URL(URL_BASE + m.file).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = STALL_MS // no bytes for this long = stalled
            conn.instanceFollowRedirects = true
            if (conn.responseCode != 200) return "Server returned ${conn.responseCode}"
            val size = conn.contentLengthLong.takeIf { it > 0 } ?: m.size
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
            if (tmp.length() != m.size) { tmp.delete(); return "Download incomplete. Try again." }
            // Only a byte-for-byte match with the pinned file is ever marked ready.
            if (hex(digest.digest()) != m.sha256) { tmp.delete(); return "The downloaded file didn't match. Try again." }
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
