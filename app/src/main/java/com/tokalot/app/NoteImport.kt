package com.tokalot.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Voice notes and text shared into Tokalot from another app (WhatsApp's share button, say), and what a note can
 * carry besides its text: the audio, the time of every word, its title, summaries and index.
 *
 * Each such note has a folder, notes-media/<note id>/, holding info.json and, for a voice note, audio.<ext> as it
 * was shared. A voice note gets its folder first and becomes a note once it's transcribed, so one that fails stays
 * on the Notes page with a Try again button. Only the note's text syncs; the folder stays on this phone (and in backups).
 */
object NoteImport {
    const val DIR = "notes-media"
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val worker = Executors.newSingleThreadExecutor()
    /** Notes being transcribed right now. One that says "pending" but isn't here was stopped (the app was closed). */
    private val running = java.util.Collections.synchronizedSet(HashSet<Long>())

    /** What's known about one note beyond its text. Read with [info]; changed with [update]. */
    class Info(val id: Long, val o: JSONObject) {
        val status get() = o.optString("status") // pending, failed, done; "" for a text note's summaries only
        val error get() = o.optString("error")
        val audio: String? get() = o.optString("audio").takeIf { it.isNotEmpty() }
        val duration get() = o.optDouble("duration", 0.0)
        /** The app it was shared from ("WhatsApp"), if known. */
        val source: String? get() = o.optString("source").takeIf { it.isNotEmpty() }
        /** Where the recording has no voice, once it's been listened for (see [findSilence]). */
        val silence: List<ClosedFloatingPointRange<Double>>? get() = o.optJSONArray("silence")?.let { a ->
            List(a.length()) { a.getJSONArray(it).let { r -> r.getDouble(0)..r.getDouble(1) } }
        }
        val words: List<NoteMedia.Word> get() = o.optJSONArray("words")?.let { a ->
            List(a.length()) { a.getJSONArray(it).let { w -> NoteMedia.Word(w.getString(0), w.getDouble(1), w.getDouble(2)) } }
        } ?: emptyList()
        val segments: List<NoteMedia.Segment> get() = o.optJSONArray("segments")?.let { a ->
            List(a.length()) { a.getJSONArray(it).let { s -> NoteMedia.Segment(s.getDouble(0), s.getDouble(1), s.getString(2)) } }
        } ?: emptyList()
        /** The note's short title, made by the AI or typed in Rename. */
        val title: String? get() = o.optString("title").trim().takeIf { it.isNotEmpty() }
        /** Rename saved an empty title: this note is left without one and isn't titled again. */
        val titleOff get() = o.optBoolean("titleOff", false)
        fun summary(level: Int): String? = o.optJSONObject("summaries")?.optString("$level")?.takeIf { it.isNotEmpty() }
        val index: List<NoteMedia.Point>? get() = o.optJSONArray("index")?.let { a ->
            List(a.length()) { a.getJSONArray(it).let { p -> NoteMedia.Point(p.getDouble(0), p.getString(1), p.getString(2)) } }
        }
    }

    fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, DIR)
    private fun folder(ctx: Context, id: Long) = File(dir(ctx), "$id")
    private fun infoFile(ctx: Context, id: Long) = File(folder(ctx, id), "info.json")
    fun audioFile(ctx: Context, id: Long): File? = info(ctx, id)?.audio?.let { File(folder(ctx, id), it) }?.takeIf { it.exists() }

    @Synchronized
    fun info(ctx: Context, id: Long): Info? = runCatching { Info(id, JSONObject(infoFile(ctx, id).readText())) }.getOrNull()

    /** Changes a note's info.json (made if it isn't there yet) through a side file, so a crash can't half-write it. */
    @Synchronized
    fun update(ctx: Context, id: Long, change: (JSONObject) -> Unit) {
        val f = infoFile(ctx, id)
        f.parentFile?.mkdirs()
        val o = runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject())
        change(o)
        val tmp = File(f.parentFile, "info.tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(f)) { f.writeText(o.toString()); tmp.delete() }
    }

    fun deleteMedia(ctx: Context, id: Long) { folder(ctx, id).deleteRecursively() }

    /** Shared voice notes that aren't notes yet: being transcribed, or failed. Newest first. */
    fun waiting(ctx: Context): List<Info> =
        ids(ctx).mapNotNull { info(ctx, it) }.filter { it.status == "pending" || it.status == "failed" }.sortedByDescending { it.id }

    fun isRunning(id: Long) = id in running

    private fun ids(ctx: Context) = dir(ctx).listFiles()?.mapNotNull { it.name.toLongOrNull() } ?: emptyList()

    /** Removes the folders of notes deleted on another device. Ones still waiting to become a note are kept. */
    fun prune(ctx: Context, noteIds: Set<Long>) {
        for (id in ids(ctx)) {
            if (id in noteIds || id in running) continue
            val s = info(ctx, id)?.status
            if (s == "pending" || s == "failed") continue
            deleteMedia(ctx, id)
        }
    }

    // ---------- bringing things in ----------

    /** Shared text is saved as a note as it is. */
    fun addText(ctx: Context, text: String) {
        Notes.add(ctx, text.trim())
    }

    /**
     * Copies a shared recording into the app (blocking, so the sharing app's permission to read it is still
     * there) and starts transcribing it in the background. Returns false if it couldn't be read.
     */
    fun addAudio(ctx: Context, uri: Uri, mime: String?, source: String? = null, caption: String? = null): Boolean {
        val app = ctx.applicationContext
        val id = freshId(app)
        val ext = extension(mime, uri.lastPathSegment)
        val folder = folder(app, id).apply { mkdirs() }
        val file = File(folder, "audio.$ext")
        try {
            val input = app.contentResolver.openInputStream(uri) ?: throw IOException("nothing to read")
            input.use { inp -> file.outputStream().use { inp.copyTo(it) } }
            if (file.length() == 0L) throw IOException("empty")
        } catch (e: Exception) {
            folder.deleteRecursively()
            return false
        }
        update(app, id) {
            it.put("status", "pending").put("audio", file.name).put("mime", mime ?: "").put("duration", durationOf(file))
            if (source != null) it.put("source", source)
            if (caption != null) it.put("caption", caption)
        }
        transcribe(app, id)
        return true
    }

    /** A note id nobody has: the current time, nudged on if two arrive in the same millisecond. */
    @Synchronized
    private fun freshId(ctx: Context): Long {
        val taken = Notes.all(ctx).map { it.id }.toSet()
        var id = System.currentTimeMillis()
        while (id in taken || folder(ctx, id).exists()) id++
        return id
    }

    /** Transcribes a waiting voice note in the background; on success it becomes a note. Also the Try again button. */
    fun transcribe(ctx: Context, id: Long) {
        val app = ctx.applicationContext
        if (!running.add(id)) return
        update(app, id) { it.put("status", "pending").remove("error") }
        changed()
        worker.execute {
            try {
                val file = audioFile(app, id) ?: throw IOException("The recording is missing")
                val (segments, timed) = cloudTranscribe(Prefs(app), file, info(app, id)?.o?.optString("mime"), (info(app, id)?.duration ?: 0.0))
                val words = NoteMedia.align(segments, timed)
                val text = NoteMedia.text(words).ifBlank { throw IOException("No speech in this recording") }
                update(app, id) { o ->
                    o.put("status", "done").remove("error")
                    o.remove("silence")
                    o.put("words", JSONArray().also { a -> words.forEach { a.put(JSONArray().put(it.text).put(it.start).put(it.end)) } })
                    o.put("segments", JSONArray().also { a -> segments.forEach { a.put(JSONArray().put(it.start).put(it.end).put(it.text.trim())) } })
                    if (o.optDouble("duration", 0.0) <= 0 && words.isNotEmpty()) o.put("duration", words.last().end)
                }
                Notes.add(app, text, id)
                makeTitle(app, id, text)
            } catch (e: Exception) {
                update(app, id) { it.put("status", "failed").put("error", e.message ?: "Couldn't transcribe it") }
            } finally {
                running.remove(id)
                changed()
            }
        }
    }

    private fun changed() = main.post { Notes.onChange?.invoke() }

    /**
     * Listens through a recording for where someone is talking (blocking: call it off the main thread) and saves the
     * stretches with no voice and no transcribed word, which Skip silence jumps over. Done once per recording.
     */
    fun findSilence(ctx: Context, id: Long): List<ClosedFloatingPointRange<Double>> {
        info(ctx, id)?.silence?.let { return it }
        val inf = info(ctx, id) ?: return emptyList()
        val file = audioFile(ctx, id) ?: return emptyList()
        val loudness = decodeLoudness(file) ?: return emptyList()
        val cuts = NoteMedia.silenceCuts(NoteMedia.speechFrames(loudness.toFloatArray()), inf.words, inf.duration)
        update(ctx, id) { o ->
            o.put("silence", JSONArray().also { a -> cuts.forEach { a.put(JSONArray().put(it.start).put(it.endInclusive)) } })
        }
        return cuts
    }

    /** The recording's loudness every [NoteMedia.FRAME] seconds, decoded with the phone's own codecs. Null if it can't be read. */
    private fun decodeLoudness(file: File): List<Float>? {
        val ex = android.media.MediaExtractor()
        var codec: android.media.MediaCodec? = null
        return try {
            ex.setDataSource(file.absolutePath)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return null
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            var rate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
            val c = android.media.MediaCodec.createDecoderByType(format.getString(android.media.MediaFormat.KEY_MIME)!!)
            codec = c
            c.configure(format, null, null, 0)
            c.start()
            val out = ArrayList<Float>()
            val state = FloatArray(4)
            val info = android.media.MediaCodec.BufferInfo()
            var inputDone = false
            var mono = ShortArray(0)
            while (true) {
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = c.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) { c.queueInputBuffer(i, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { c.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                if (o == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    rate = c.outputFormat.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                    channels = c.outputFormat.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    val buf = c.getOutputBuffer(o)!!.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer()
                    val frames = buf.remaining() / channels.coerceAtLeast(1)
                    if (mono.size < frames) mono = ShortArray(frames)
                    for (f in 0 until frames) {
                        var sum = 0
                        for (ch in 0 until channels) sum += buf.get(f * channels + ch)
                        mono[f] = (sum / channels.coerceAtLeast(1)).toShort()
                    }
                    NoteMedia.frameLoudness(mono, frames, rate, state, out)
                    c.releaseOutputBuffer(o, false)
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            out
        } catch (e: Exception) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            ex.release()
        }
    }

    private fun durationOf(file: File): Double = runCatching {
        MediaMetadataRetriever().run {
            try {
                setDataSource(file.absolutePath)
                (extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000.0
            } finally { release() }
        }
    }.getOrDefault(0.0)

    /** The file name ending the provider will accept for this kind of audio. WhatsApp's .opus is Ogg inside. */
    fun extension(mime: String?, name: String?): String {
        val m = mime.orEmpty().lowercase()
        return when {
            "ogg" in m || "opus" in m -> "ogg"
            "mp4" in m || "m4a" in m || "aac" in m -> "m4a"
            "mpeg" in m || "mp3" in m -> "mp3"
            "wav" in m -> "wav"
            "webm" in m -> "webm"
            "flac" in m -> "flac"
            else -> when (val e = name.orEmpty().substringAfterLast('.', "").lowercase()) {
                "opus", "oga" -> "ogg"
                "ogg", "m4a", "mp3", "wav", "webm", "flac", "mp4" -> e
                else -> "ogg"
            }
        }
    }

    // ---------- the cloud calls ----------

    /**
     * Whisper with a time for every word, from Groq (the same Whisper model, with word times in its reply) or else
     * OpenAI's whisper-1 (its newer models don't give word times). Needs one of those keys.
     */
    private fun cloudTranscribe(prefs: Prefs, file: File, mime: String?, duration: Double): Pair<List<NoteMedia.Segment>, List<NoteMedia.Word>> {
        val order = listOf(SttChoice.GROQ to prefs.sttModel(SttChoice.GROQ).takeIf { it.startsWith("whisper") }.orEmpty().ifEmpty { SttChoice.GROQ.model },
            SttChoice.OPENAI to "whisper-1")
            .filter { prefs.key(it.first.service).isNotEmpty() }
        if (order.isEmpty()) throw IOException("Add a Groq key in Settings to transcribe voice notes")
        var last: Exception? = null
        for ((c, model) in order) {
            try {
                return parseTimed(send(c.baseUrl, prefs.key(c.service), model, file, mime, prefs.autoLanguage, duration))
            } catch (e: Exception) { last = e }
        }
        throw last ?: IOException("Couldn't transcribe it")
    }

    private fun send(baseUrl: String, key: String, model: String, file: File, mime: String?, autoLanguage: Boolean, duration: Double): JSONObject {
        val boundary = "----tokalot${System.nanoTime()}"
        fun field(name: String, value: String) = "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n"
        val head = buildString {
            append(field("model", model))
            if (!autoLanguage) append(field("language", "en"))
            append(field("response_format", "verbose_json"))
            append(field("timestamp_granularities[]", "word"))
            append(field("timestamp_granularities[]", "segment"))
            append("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n")
            append("Content-Type: ${mime?.takeIf { it.startsWith("audio/") } ?: "audio/ogg"}\r\n\r\n")
        }.toByteArray()
        val tail = "\r\n--$boundary--\r\n".toByteArray()
        val conn = URL("$baseUrl/audio/transcriptions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 8000
        conn.readTimeout = Timeouts.sttReadMs((duration * 1000).toLong())
        conn.setFixedLengthStreamingMode(head.size + file.length() + tail.size)
        conn.setRequestProperty("Authorization", "Bearer $key")
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.outputStream.use { out ->
            out.write(head)
            file.inputStream().use { it.copyTo(out) }
            out.write(tail)
        }
        return Net.readResponse(conn)
    }

    /** The segments and timed words of a verbose_json reply. With no segments, the whole text is one. */
    fun parseTimed(reply: JSONObject): Pair<List<NoteMedia.Segment>, List<NoteMedia.Word>> {
        val segs = reply.optJSONArray("segments")
        val segments = if (segs != null && segs.length() > 0) List(segs.length()) {
            val s = segs.getJSONObject(it)
            NoteMedia.Segment(s.optDouble("start", 0.0), s.optDouble("end", 0.0), s.optString("text"))
        } else listOf(NoteMedia.Segment(0.0, reply.optDouble("duration", 0.0), reply.optString("text")))
        val ws = reply.optJSONArray("words")
        val words = if (ws == null) emptyList() else List(ws.length()) {
            val w = ws.getJSONObject(it)
            NoteMedia.Word(w.optString("word").trim(), w.optDouble("start", 0.0), w.optDouble("end", 0.0))
        }
        return segments.filter { it.text.isNotBlank() } to words
    }

    /** Notes being titled right now, so opening one while it's transcribed doesn't ask twice. */
    private val titling = java.util.Collections.synchronizedSet(HashSet<Long>())

    /** Whether [ask] has a provider to try. */
    fun canAsk(prefs: Prefs) = CleanupChoice.values().any { it != CleanupChoice.OFF && prefs.key(it.service).isNotEmpty() }

    /**
     * Gives a note without a title a short one from the AI and returns it (blocking). Skipped quietly, returning null,
     * for a short note, one whose title was cleared in Rename, with no AI key, or when the AI fails.
     */
    fun makeTitle(ctx: Context, id: Long, text: String): String? {
        val prefs = Prefs(ctx.applicationContext)
        val inf = info(ctx, id)
        if (inf?.title != null || inf?.titleOff == true || text.trim().length < 40 || !canAsk(prefs) || !titling.add(id)) return null
        return try {
            val title = NoteMedia.cleanTitle(ask(prefs, NoteMedia.titleSystem(), NoteMedia.titleUser(text), NoteMedia.TITLE_MAX_TOKENS)) ?: return null
            if (Notes.all(ctx).none { it.id == id }) return null // deleted meanwhile
            update(ctx, id) { it.put("title", title) }
            title
        } catch (e: Exception) {
            null
        } finally { titling.remove(id) }
    }

    /**
     * Asks the cleanup AI for a summary, index or title: the provider picked for cleanup first, then any other with a key.
     * Blocking; throws when none has a key or all fail.
     */
    fun ask(prefs: Prefs, system: String, user: String, maxTokens: Int): String {
        val order = (listOf(prefs.cleanup) + CleanupChoice.values().filter { it != prefs.cleanup })
            .filter { it != CleanupChoice.OFF && prefs.key(it.service).isNotEmpty() }
        if (order.isEmpty()) throw IOException("Add a Groq key in Settings to summarize notes")
        var last: Exception? = null
        for (c in order.take(2)) {
            try {
                return chat(prefs, c, system, user, maxTokens).trim().ifEmpty { throw IOException("The AI sent back nothing") }
            } catch (e: Exception) { last = e }
        }
        throw last ?: IOException("Couldn't reach the AI")
    }

    private fun chat(prefs: Prefs, c: CleanupChoice, system: String, user: String, maxTokens: Int): String {
        val key = prefs.key(c.service)
        val model = prefs.cleanupModel(c)
        val messages = JSONArray()
        if (c == CleanupChoice.CLAUDE) {
            val body = JSONObject().put("model", model).put("max_tokens", maxTokens).put("system", system)
                .put("messages", messages.put(JSONObject().put("role", "user").put("content", user)))
            val res = Net.postJson("${c.baseUrl}/messages", mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"), body, readTimeoutMs = 60_000)
            val parts = res.getJSONArray("content")
            return buildString { for (i in 0 until parts.length()) parts.getJSONObject(i).takeIf { it.optString("type") == "text" }?.let { append(it.optString("text")) } }
        }
        messages.put(JSONObject().put("role", "system").put("content", system)).put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject().put("model", model).put("messages", messages)
        // GPT-OSS thinks before it answers; its thinking counts against the cap, so it gets room and is kept short.
        // Gemini's OpenAI-style endpoint only knows the older max_tokens.
        val cap = if (c == CleanupChoice.GEMINI) "max_tokens" else "max_completion_tokens"
        if (model.contains("gpt-oss")) body.put("reasoning_effort", "low").put(cap, maxTokens + 2000)
        else body.put(cap, maxTokens)
        val res = Net.postJson("${c.baseUrl}/chat/completions", mapOf("Authorization" to "Bearer $key"), body, readTimeoutMs = 60_000)
        return res.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
    }
}
