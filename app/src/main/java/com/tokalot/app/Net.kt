package com.tokalot.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Thrown inside the pipeline once its [Call] has been cancelled. */
class CancelledException : IOException("Cancelled")

/** A non-2xx answer, with the status code kept so callers can react to it. */
class HttpException(val code: Int, message: String) : IOException(message)

/**
 * The network side of one dictation. Requests register here while they run, so a tap on
 * the button (or the deadline) can abort them from another thread.
 */
class Call {
    @Volatile var cancelled = false
        private set
    private val open = HashSet<HttpURLConnection>()

    fun check() { if (cancelled) throw CancelledException() }

    /** Where the speech step's time went, in milliseconds, for the timings list: getting the audio ready,
     *  connecting and sending it, and waiting for the answer. */
    @Volatile var prepMs = 0L
    @Volatile var sendMs = 0L
    @Volatile var waitMs = 0L

    /** Runs [block] with [conn] registered; a failure caused by cancel() surfaces as CancelledException. */
    fun <T> track(conn: HttpURLConnection, block: () -> T): T {
        synchronized(open) {
            check()
            open.add(conn)
        }
        try {
            return block()
        } catch (e: Exception) {
            if (cancelled) throw CancelledException()
            throw e
        } finally {
            synchronized(open) { open.remove(conn) }
        }
    }

    fun cancel() {
        val conns = synchronized(open) { cancelled = true; open.toList() }
        // Closing a TLS socket writes to the network, which Android forbids on the main thread.
        if (conns.isNotEmpty()) Thread { conns.forEach { c -> runCatching { c.disconnect() } } }.start()
    }
}

/** What kind of connection the phone is on right now. */
object Link {
    /**
     * True on mobile data, or on any connection Android rates under 2 Mbit/s up. There the upload is what
     * the user waits for, so audio goes as AAC (about 3 KB a second) rather than FLAC (about 16 KB a second).
     * On good Wi-Fi the upload is instant either way and FLAC needs no encoding time. Unknown counts as fast.
     */
    /** "cell" on mobile data, otherwise "wifi". Speeds are remembered separately for each. */
    fun kind(ctx: android.content.Context): String = runCatching {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        if (caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) "cell" else "wifi"
    }.getOrDefault("wifi")

    fun slow(ctx: android.content.Context): Boolean = runCatching {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        caps != null && (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) ||
            caps.linkUpstreamBandwidthKbps in 1 until 2000)
    }.getOrDefault(false)
}

/** How long things may take, scaled by how much audio there is. */
object Timeouts {
    /** The whole dictation: 30 s plus twice the audio length, capped at 10 minutes. */
    fun deadlineMs(audioMs: Long): Long = (30_000L + 2 * audioMs.coerceAtLeast(0)).coerceAtMost(600_000L)

    /** Waiting for the transcript once the audio is uploaded: 20 s plus half the audio length, capped at 2 minutes. */
    fun sttReadMs(audioMs: Long): Int = (20_000L + audioMs.coerceAtLeast(0) / 2).coerceAtMost(120_000L).toInt()
}

/** Small HTTP helpers. No libraries needed: HttpURLConnection + org.json are built into Android. */
object Net {
    fun postJson(
        url: String, headers: Map<String, String>, body: JSONObject, readTimeoutMs: Int = 15000, call: Call? = null,
    ): JSONObject {
        val bytes = body.toString().toByteArray()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = readTimeoutMs
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        val send = {
            conn.outputStream.use { it.write(bytes) }
            readResponse(conn)
        }
        return if (call != null) call.track(conn, send) else send()
    }

    fun readResponse(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw HttpException(code, "HTTP $code: ${errorMessage(text)}")
        return JSONObject(text)
    }

    /** Pulls the human-readable message out of the usual API error shapes. */
    private fun errorMessage(body: String): String = try {
        val o = JSONObject(body)
        val e = o.opt("error")
        when (e) {
            is JSONObject -> e.optString("message", body)
            is String -> e
            else -> o.optString("message", body)
        }
    } catch (_: Exception) {
        if (body.startsWith("[")) runCatching { JSONArray(body).getJSONObject(0).toString() }.getOrDefault(body) else body.take(200)
    }

    /**
     * Makes a tiny authenticated request so the TLS connection is already open (and pooled)
     * by the time the real upload happens. Saves a few hundred ms per dictation.
     */
    /**
     * How long one small request to the service takes right now, in milliseconds (-1 if it failed).
     * Asked on a connection that is already open, so it measures the link as it is at this moment:
     * a few dozen milliseconds on good Wi-Fi, a second or more in a bad service zone. HEAD, so no body comes back.
     */
    fun rtt(url: String, headers: Map<String, String>): Int = try {
        val t = System.nanoTime()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "HEAD"
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.responseCode
        runCatching { conn.inputStream.close() }
        ((System.nanoTime() - t) / 1_000_000).toInt()
    } catch (_: Exception) {
        -1
    }

    fun warm(url: String, headers: Map<String, String>) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = conn.responseCode
            // Reading the body to the end lets the socket go back into the keep-alive pool.
            (if (code in 200..299) conn.inputStream else conn.errorStream)?.use { it.readBytes() }
        } catch (_: Exception) {}
    }

    /** 16 kHz mono float samples -> 16-bit PCM WAV bytes. */
    fun wav(samples: FloatArray, rate: Int = Recorder.SAMPLE_RATE): ByteArray {
        val dataLen = samples.size * 2
        val b = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + dataLen).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(dataLen)
        for (s in samples) b.putShort((s.coerceIn(-1f, 1f) * 32767).toInt().toShort())
        return b.array()
    }
}

/** The audio part of a transcription request: WAV bytes, or the saved .m4a file. */
class Upload(val fileName: String, val mime: String, val length: Long, val write: (OutputStream) -> Unit) {
    companion object {
        fun wav(samples: FloatArray): Upload {
            val bytes = Net.wav(samples)
            return Upload("audio.wav", "audio/wav", bytes.size.toLong()) { it.write(bytes) }
        }

        /** Lossless, about half the size of the WAV, and quick enough to make for any length. */
        fun flac(samples: FloatArray): Upload {
            val bytes = Flac.encode(samples)
            return Upload("audio.flac", "audio/flac", bytes.size.toLong()) { it.write(bytes) }
        }

        /** The AAC file AudioStore writes: about a tenth of the WAV, so long recordings upload far faster. */
        fun m4a(file: File): Upload = m4a(file.readBytes()) // ~3 KB per second of audio; read once so the length can't drift

        fun m4a(bytes: ByteArray) = Upload("audio.m4a", "audio/mp4", bytes.size.toLong()) { it.write(bytes) }
    }
}

/** Cloud speech-to-text through the OpenAI-compatible endpoint (Groq and OpenAI both speak it). */
object CloudStt {
    /** The multipart text before the audio bytes. Split out so the request length is known up front. */
    fun head(boundary: String, model: String, prompt: String, english: Boolean, audio: Upload, format: String = "json"): String {
        fun field(name: String, value: String) =
            "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n"
        return buildString {
            append(field("model", model))
            if (english) append(field("language", "en")) // omitted = the model detects the language
            append(field("response_format", format))
            if (prompt.isNotBlank()) append(field("prompt", prompt))
            append("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${audio.fileName}\"\r\n")
            append("Content-Type: ${audio.mime}\r\n\r\n")
        }
    }

    fun transcribe(
        baseUrl: String, key: String, model: String, audio: Upload, prompt: String,
        english: Boolean = true, audioMs: Long = 0, call: Call? = null,
    ): String {
        val boundary = "----tokalot${System.nanoTime()}"
        // Whisper models also say how likely each stretch was to be silence, which tells a stray "Thank you." from a real one.
        val verbose = model.startsWith("whisper")
        val head = head(boundary, model, prompt, english, audio, if (verbose) "verbose_json" else "json").toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()

        val conn = URL("$baseUrl/audio/transcriptions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 8000
        conn.readTimeout = Timeouts.sttReadMs(audioMs)
        // Known length = streamed straight to the socket instead of buffered in memory first.
        conn.setFixedLengthStreamingMode(head.size + audio.length + tail.size)
        conn.setRequestProperty("Authorization", "Bearer $key")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        val send = {
            val t0 = System.nanoTime()
            conn.outputStream.use { out ->
                out.write(head)
                audio.write(out)
                out.write(tail)
            }
            val t1 = System.nanoTime()
            val reply = Net.readResponse(conn)
            call?.let { it.sendMs += (t1 - t0) / 1_000_000; it.waitMs += (System.nanoTime() - t1) / 1_000_000 }
            text(reply)
        }
        return if (call != null) call.track(conn, send) else send()
    }

    /**
     * The transcript, or "" when it's one of the phrases Whisper invents for noise ("Thank you.") and Whisper
     * itself rated every part of the audio as probably not speech. A "thank you" that was said comes through.
     */
    fun text(reply: JSONObject): String {
        val text = reply.optString("text")
        val segments = reply.optJSONArray("segments") ?: return text
        if (segments.length() == 0 || !TextTools.isPhantom(text)) return text
        val silent = (0 until segments.length()).all { (segments.optJSONObject(it)?.optDouble("no_speech_prob", 0.0) ?: 0.0) >= NO_SPEECH }
        return if (silent) "" else text
    }

    /** Whisper's own cut-off for "this was silence" is 0.6; a stock phrase on top of it needs less. */
    const val NO_SPEECH = 0.5
}
