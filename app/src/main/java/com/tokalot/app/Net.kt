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

        /** The AAC file AudioStore writes: about a tenth of the WAV, so long recordings upload far faster. */
        fun m4a(file: File): Upload {
            val bytes = file.readBytes() // ~3 KB per second of audio; read once so the length can't drift
            return Upload("audio.m4a", "audio/mp4", bytes.size.toLong()) { it.write(bytes) }
        }
    }
}

/** Cloud speech-to-text through the OpenAI-compatible endpoint (Groq and OpenAI both speak it). */
object CloudStt {
    /** The multipart text before the audio bytes. Split out so the request length is known up front. */
    fun head(boundary: String, model: String, prompt: String, english: Boolean, audio: Upload): String {
        fun field(name: String, value: String) =
            "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n"
        return buildString {
            append(field("model", model))
            if (english) append(field("language", "en")) // omitted = the model detects the language
            append(field("response_format", "json"))
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
        val head = head(boundary, model, prompt, english, audio).toByteArray()
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
            conn.outputStream.use { out ->
                out.write(head)
                audio.write(out)
                out.write(tail)
            }
            Net.readResponse(conn).optString("text")
        }
        return if (call != null) call.track(conn, send) else send()
    }
}
