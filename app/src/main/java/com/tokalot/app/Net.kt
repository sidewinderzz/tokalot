package com.tokalot.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Small HTTP helpers. No libraries needed: HttpURLConnection + org.json are built into Android. */
object Net {
    fun postJson(url: String, headers: Map<String, String>, body: JSONObject, readTimeoutMs: Int = 15000): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = readTimeoutMs
        conn.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        return readResponse(conn)
    }

    fun readResponse(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw IOException("HTTP $code: ${errorMessage(text)}")
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

/** Cloud speech-to-text through the OpenAI-compatible endpoint (Groq and OpenAI both speak it). */
object CloudStt {
    fun transcribe(baseUrl: String, key: String, model: String, samples: FloatArray, prompt: String, english: Boolean = true): String {
        val boundary = "----tokalot${System.nanoTime()}"
        val conn = URL("$baseUrl/audio/transcriptions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5000
        conn.readTimeout = 20000
        conn.setRequestProperty("Authorization", "Bearer $key")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.outputStream.use { out ->
            fun field(name: String, value: String) =
                out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n".toByteArray())
            field("model", model)
            if (english) field("language", "en") // omitted = the model detects the language
            field("response_format", "json")
            if (prompt.isNotBlank()) field("prompt", prompt)
            out.write(
                "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray()
            )
            out.write(Net.wav(samples))
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        return Net.readResponse(conn).optString("text")
    }
}
