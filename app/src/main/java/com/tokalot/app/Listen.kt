package com.tokalot.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Read aloud (beta): a web link, PDF, Word file or text shared to "Listen in Tokalot" becomes a note with a voice,
 * played in the same player as a voice note (speed wheel, tap a word, Timeline). The text comes out as the author
 * wrote it; only clutter is left out. The voice is Gemini's, with the Gemini key, or the phone's own when there's no
 * key, Privacy says no cloud voice, or Gemini fails. Like a shared voice note, it waits on Home until it's ready.
 *
 * It lives in the note's folder (see [NoteImport]): info.json has "listen" (what was shared) and, once done, the
 * same audio, words and segments a voice note has, plus "breaks" (where paragraphs start).
 */
object Listen {
    const val PHONE_VOICE = "phone"
    const val DEFAULT_VOICE = "Kore"
    /** Gemini's voices offered in Settings, with how Google describes each. */
    val VOICES = linkedMapOf(
        "Kore" to "firm", "Charon" to "informative", "Puck" to "upbeat", "Aoede" to "breezy",
        "Orus" to "firm, deeper", "Leda" to "youthful", "Zephyr" to "bright", "Fenrir" to "excitable",
    )
    /** Tried in order; one the account can't use (404) moves on to the next. */
    private val MODELS = listOf("gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts", "gemini-2.5-flash-preview-tts")
    private const val PIECE = 1500
    private const val GAP = 0.35 // seconds of quiet between pieces
    private val worker = Executors.newSingleThreadExecutor()
    private val running = java.util.Collections.synchronizedSet(HashSet<Long>())

    fun isRunning(id: Long) = id in running

    /** Whether this waiting item is something to read aloud (not a shared voice note). */
    fun isListen(info: NoteImport.Info) = info.o.has("listen")

    /** What a waiting item shows: "Read aloud · example.com". */
    fun label(info: NoteImport.Info): String {
        val l = info.o.optJSONObject("listen") ?: return "Read aloud"
        val what = l.optString("site").ifEmpty { ListenText.host(l.optString("url")) }.ifEmpty { l.optString("name") }
        return if (what.isEmpty()) "Read aloud" else "Read aloud · $what"
    }

    fun step(info: NoteImport.Info): String = info.o.optString("step").ifEmpty { "Getting it ready…" }

    // ---------- bringing it in ----------

    /**
     * Saves what was shared (blocking, while the sharing app's permission lasts) and starts reading it in the
     * background. Returns false when there's nothing it can read.
     */
    fun add(ctx: Context, uri: Uri?, mime: String?, name: String?, text: String?): Boolean {
        val app = ctx.applicationContext
        val link = text?.let { ListenText.loneLink(it) }
        val kind = when {
            uri != null -> kindOf(mime, name) ?: return false
            link != null -> "web"
            !text.isNullOrBlank() -> "text"
            else -> return false
        }
        val id = NoteImport.freshId(app)
        val folder = NoteImport.folder(app, id).apply { mkdirs() }
        val l = JSONObject().put("kind", kind)
        try {
            when {
                uri != null -> {
                    val file = File(folder, "source." + when (kind) { "pdf" -> "pdf"; "docx" -> "docx"; else -> "txt" })
                    val input = app.contentResolver.openInputStream(uri) ?: throw IOException("nothing to read")
                    input.use { inp -> file.outputStream().use { inp.copyTo(it) } }
                    if (file.length() == 0L) throw IOException("empty")
                    l.put("file", file.name)
                    if (!name.isNullOrBlank()) l.put("name", name)
                }
                kind == "web" -> l.put("url", link)
                else -> { File(folder, "source.txt").writeText(text!!); l.put("file", "source.txt") }
            }
        } catch (e: Exception) {
            folder.deleteRecursively()
            return false
        }
        NoteImport.update(app, id) { it.put("status", "pending").put("home", true).put("listen", l) }
        run(app, id)
        return true
    }

    /** pdf, docx or text, from what Android says the file is, or its name. Null for anything else. */
    fun kindOf(mime: String?, name: String?): String? {
        val m = mime.orEmpty().lowercase()
        val ext = name.orEmpty().substringAfterLast('.', "").lowercase()
        return when {
            m == "application/pdf" || ext == "pdf" -> "pdf"
            "wordprocessingml" in m || ext == "docx" -> "docx"
            m.startsWith("text/") || ext == "txt" || ext == "md" -> "text"
            else -> null
        }
    }

    /** Reads a waiting item aloud in the background. Also the Try again button. */
    fun run(ctx: Context, id: Long) {
        val app = ctx.applicationContext
        if (!running.add(id)) return
        NoteImport.update(app, id) { it.put("status", "pending").remove("error") }
        NoteImport.changed()
        worker.execute {
            try {
                make(app, id)
            } catch (e: Exception) {
                NoteImport.update(app, id) { it.put("status", "failed").put("error", e.message ?: "Couldn't read it aloud").remove("step") }
            } finally {
                running.remove(id)
                NoteImport.changed()
            }
        }
    }

    private fun step(ctx: Context, id: Long, s: String) {
        NoteImport.update(ctx, id) { it.put("step", s) }
        NoteImport.changed()
    }

    // ---------- making it ----------

    private fun make(app: Context, id: Long) {
        val prefs = Prefs(app)
        val folder = NoteImport.folder(app, id)
        val l = NoteImport.info(app, id)?.o?.optJSONObject("listen") ?: throw IOException("It's missing")
        step(app, id, "Reading it…")
        var title = l.optString("name").substringBeforeLast('.').replace('_', ' ').trim()
        var site = ""
        val source = l.optString("file").takeIf { it.isNotEmpty() }?.let { File(folder, it) }
        var blocks = when (l.optString("kind")) {
            "web" -> {
                val url = l.optString("url")
                val got = fetch(url)
                if (got.pdf) {
                    val f = File(folder, "source.pdf").apply { writeBytes(got.bytes) }
                    pdfBlocks(app, f).also { f.delete() }
                } else {
                    val page = ListenText.page(got.text(), got.url)
                    title = page.title
                    site = page.site
                    tidy(app, id, prefs, page.blocks)
                }
            }
            "pdf" -> pdfBlocks(app, source ?: throw IOException("The file is missing"))
            "docx" -> docxBlocks(source ?: throw IOException("The file is missing"))
            else -> ListenText.textBlocks(source?.readText() ?: "")
        }
        blocks = blocks.filter { it.isNotBlank() }
        if (blocks.isEmpty()) throw IOException("There's no text in it to read")
        if (site.isNotEmpty()) NoteImport.update(app, id) { o -> o.optJSONObject("listen")?.put("site", site) }

        val pieces = ListenText.pieces(blocks, PIECE)
        var cloud = prefs.listenCloudVoice && prefs.listenVoice != PHONE_VOICE && prefs.key(CleanupChoice.GEMINI.service).isNotEmpty()
        var voiceNote = ""
        val pcm = File(folder, "voice.pcm")
        var rate = 0
        var phone: PhoneVoice? = null
        val starts = ArrayList<Double>()
        val lengths = ArrayList<Double>()
        var t = 0.0
        try {
            pcm.outputStream().buffered().use { out ->
                for ((i, piece) in pieces.withIndex()) {
                    step(app, id, if (pieces.size == 1) "Making the voice…" else "Making the voice: part ${i + 1} of ${pieces.size}")
                    var w: ListenText.Wav? = null
                    if (cloud) w = try { gemini(prefs, piece.first) } catch (e: IOException) {
                        // From here on the phone reads it, so the voice doesn't keep switching.
                        cloud = false
                        voiceNote = "Gemini's voice didn't work (${e.message}), so the phone's voice read ${if (i == 0) "it" else "from part ${i + 1} on"}."
                        null
                    }
                    if (w == null) w = (phone ?: PhoneVoice(app).also { phone = it }).say(piece.first, File(folder, "piece.wav"))
                    var data = ListenText.mono16(w)
                    if (rate == 0) rate = w.rate
                    if (w.rate != rate) data = ListenText.resample(data, w.rate, rate)
                    out.write(data)
                    val secs = data.size / 2.0 / rate
                    starts += t; lengths += secs
                    t += secs
                    out.write(ByteArray((rate * GAP).toInt() * 2))
                    t += GAP
                }
            }
        } finally {
            phone?.close()
            File(folder, "piece.wav").delete()
        }
        step(app, id, "Saving the sound…")
        val audio = File(folder, "audio.m4a")
        try { encodeAac(pcm, rate, audio) } finally { pcm.delete() }

        val timed = ListenText.timeWords(blocks, pieces, starts, lengths)
        val text = NoteMedia.text(timed.words, timed.breaks)
        NoteImport.update(app, id) { o ->
            o.put("status", "done").remove("error"); o.remove("step")
            o.put("audio", audio.name).put("mime", "audio/mp4").put("duration", t)
            o.put("words", JSONArray().also { a -> timed.words.forEach { a.put(JSONArray().put(it.text).put(it.start).put(it.end)) } })
            o.put("segments", JSONArray().also { a -> timed.segments.forEach { a.put(JSONArray().put(it.start).put(it.end).put(it.text)) } })
            o.put("breaks", JSONArray(timed.breaks.sorted()))
            o.put("voice", if (cloud) prefs.listenVoice else PHONE_VOICE)
            if (voiceNote.isNotEmpty()) o.put("voiceNote", voiceNote)
            val from = site.ifEmpty { ListenText.host(l.optString("url")) }
            if (from.isNotEmpty()) o.put("source", from)
            if (title.isNotEmpty()) o.put("title", title.take(80))
        }
        source?.delete()
        Notes.add(app, text, id, home = true)
        if (title.isEmpty()) NoteImport.makeTitle(app, id, text)
    }

    /** The AI editor marks clutter to leave out, when AI cleanup is on and there's a key. Its failing changes nothing. */
    private fun tidy(app: Context, id: Long, prefs: Prefs, blocks: List<String>): List<String> {
        if (blocks.size < 4 || prefs.cleanup == CleanupChoice.OFF || !NoteImport.canAsk(prefs)) return blocks
        step(app, id, "Leaving out menus and ads…")
        return try {
            val cut = ListenText.removed(NoteImport.ask(prefs, ListenText.EDITOR_PROMPT, ListenText.outline(blocks), 400), blocks.size)
            blocks.filterIndexed { i, _ -> i !in cut }
        } catch (e: Exception) { blocks }
    }

    // ---------- reading files ----------

    private fun pdfBlocks(app: Context, file: File): List<String> {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(app)
        val pages = try {
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(file).use { doc ->
                val strip = com.tom_roush.pdfbox.text.PDFTextStripper()
                (1..doc.numberOfPages).map { strip.startPage = it; strip.endPage = it; strip.getText(doc) }
            }
        } catch (e: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            throw IOException("This PDF is locked with a password")
        } catch (e: OutOfMemoryError) {
            throw IOException("This PDF is too big to read on the phone")
        }
        if (pages.sumOf { it.trim().length } < 40 * pages.size.coerceAtLeast(1))
            throw IOException("This PDF is pictures of pages (scanned), with no text in it to read. Tokalot can't read those yet.")
        return ListenText.reflow(ListenText.withoutPageFurniture(pages))
    }

    private fun docxBlocks(file: File): List<String> = try {
        java.util.zip.ZipFile(file).use { z ->
            val e = z.getEntry("word/document.xml") ?: throw IOException("This isn't a Word file Tokalot can read")
            ListenText.docxBlocks(z.getInputStream(e).readBytes().toString(Charsets.UTF_8))
        }
    } catch (e: java.util.zip.ZipException) { throw IOException("This isn't a Word file Tokalot can read") }

    private class Got(val url: String, val bytes: ByteArray, val type: String) {
        val pdf get() = "pdf" in type.lowercase() || (bytes.size > 4 && String(bytes, 0, 4, Charsets.ISO_8859_1) == "%PDF")
        fun text(): String {
            val cs = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)
                ?: Regex("<meta[^>]+charset=[\"']?([\\w-]+)", RegexOption.IGNORE_CASE).find(String(bytes, 0, minOf(bytes.size, 4096), Charsets.ISO_8859_1))?.groupValues?.get(1)
            return String(bytes, runCatching { charset(cs ?: "UTF-8") }.getOrDefault(Charsets.UTF_8))
        }
    }

    /** Downloads a page (up to 15 MB), following redirects, as a phone browser would ask for it. */
    private fun fetch(start: String): Got {
        var url = start
        repeat(6) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36")
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/pdf;q=0.9,*/*;q=0.8")
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    url = URL(URL(url), conn.getHeaderField("Location") ?: throw IOException("The link went nowhere")).toString()
                    return@repeat
                }
                if (code == 401 || code == 403) throw IOException("That site doesn't let apps read its pages (HTTP $code). Try sharing the text instead.")
                if (code !in 200..299) throw IOException("The page didn't load (HTTP $code)")
                val bytes = conn.inputStream.use { limited(it, 15 shl 20) }
                return Got(url, bytes, conn.contentType.orEmpty())
            } finally { conn.disconnect() }
        }
        throw IOException("The link kept redirecting")
    }

    private fun limited(input: InputStream, max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16384)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw IOException("That's too big to read aloud")
        }
        return out.toByteArray()
    }

    // ---------- the voices ----------

    /** Which model and request shape worked last, so later pieces skip the ones that didn't. */
    @Volatile private var model = 0
    @Volatile private var oldShape = false

    /** One piece in Gemini's voice. Throws IOException when it can't, after waiting out a busy spell or two. */
    private fun gemini(prefs: Prefs, text: String): ListenText.Wav {
        val key = prefs.key(CleanupChoice.GEMINI.service)
        var busy = 0
        while (true) {
            val m = MODELS.getOrNull(model) ?: throw IOException("no voice model this key can use")
            val voice = if (oldShape) JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", prefs.listenVoice))
                else JSONObject().put("voice", prefs.listenVoice)
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text)))))
                .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", JSONObject().put("voiceConfig", voice)))
            try {
                val res = Net.postJson("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent",
                    mapOf("x-goog-api-key" to key), body, readTimeoutMs = 180_000)
                return parseGemini(res) ?: throw IOException("Gemini sent back no sound")
            } catch (e: HttpException) {
                when {
                    e.code == 404 -> model++
                    e.code == 400 && !oldShape -> oldShape = true
                    (e.code == 429 || e.code == 503) && busy < 3 -> { busy++; Thread.sleep(15_000L * busy) }
                    e.code == 429 -> throw IOException("Gemini's free voice limit is used up for now")
                    else -> throw IOException("HTTP ${e.code}")
                }
            }
        }
    }

    /** The sound in a Gemini reply: a WAV file, or bare 16-bit samples with the rate in its type. */
    fun parseGemini(res: JSONObject): ListenText.Wav? {
        val parts = res.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: return null
        for (i in 0 until parts.length()) {
            val d = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
            val bytes = Base64.decode(d.optString("data"), Base64.DEFAULT)
            if (bytes.isEmpty()) continue
            ListenText.readWav(bytes)?.let { return it }
            val rate = Regex("rate=(\\d+)").find(d.optString("mimeType"))?.groupValues?.get(1)?.toIntOrNull() ?: 24000
            return ListenText.Wav(rate, 1, 16, bytes)
        }
        return null
    }

    /** The phone's own text-to-speech voice (Speech Services by Google on most phones), writing WAV files. */
    private class PhoneVoice(ctx: Context) {
        private val waiting = ConcurrentHashMap<String, Pair<CountDownLatch, BooleanArray>>()
        private val tts: TextToSpeech

        init {
            val ready = CountDownLatch(1)
            val ok = BooleanArray(1)
            tts = TextToSpeech(ctx) { ok[0] = it == TextToSpeech.SUCCESS; ready.countDown() }
            if (!ready.await(20, TimeUnit.SECONDS) || !ok[0]) {
                runCatching { tts.shutdown() }
                throw IOException("This phone has no voice for reading aloud. Install Speech Services by Google from the Play Store, or add a Gemini key.")
            }
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) {}
                override fun onDone(id: String) { waiting.remove(id)?.first?.countDown() }
                @Deprecated("Deprecated in Java")
                override fun onError(id: String) { waiting.remove(id)?.let { it.second[0] = true; it.first.countDown() } }
                override fun onError(id: String, code: Int) { onError(id) }
            })
        }

        fun say(text: String, file: File): ListenText.Wav {
            file.delete()
            val id = "piece${System.nanoTime()}"
            val done = CountDownLatch(1)
            val failed = BooleanArray(1)
            waiting[id] = done to failed
            if (tts.synthesizeToFile(text, Bundle(), file, id) != TextToSpeech.SUCCESS) throw IOException("The phone's voice couldn't read it")
            if (!done.await(5, TimeUnit.MINUTES) || failed[0]) throw IOException("The phone's voice couldn't read it")
            return ListenText.readWav(file.readBytes()) ?: throw IOException("The phone's voice gave back no sound")
        }

        fun close() { runCatching { tts.shutdown() } }
    }

    // ---------- the sound file ----------

    /** Raw 16-bit mono samples, streamed from [pcm] into an AAC (.m4a) file, about a tenth the size. */
    private fun encodeAac(pcm: File, rate: Int, dest: File) {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 48_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            pcm.inputStream().buffered().use { input ->
                codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                val info = MediaCodec.BufferInfo()
                val chunk = ByteArray(8192)
                var sent = 0L
                var inputDone = false
                var track = -1
                var muxing = false
                while (true) {
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val buf = codec.getInputBuffer(inIdx)!!
                            buf.clear()
                            val n = input.read(chunk, 0, minOf(chunk.size, buf.remaining()) and 1.inv())
                            val ptsUs = sent / 2L * 1_000_000L / rate
                            if (n <= 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                buf.put(chunk, 0, n)
                                codec.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
                                sent += n
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            track = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxing = true
                        }
                        outIdx >= 0 -> {
                            val buf = codec.getOutputBuffer(outIdx)!!
                            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (muxing && info.size > 0 && !isConfig) {
                                buf.position(info.offset)
                                buf.limit(info.offset + info.size)
                                muxer.writeSampleData(track, buf, info)
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                    }
                }
                if (muxing) muxer.stop()
            }
        } catch (e: Exception) {
            dest.delete()
            throw IOException("Couldn't save the sound")
        } finally {
            runCatching { codec.stop() }
            codec.release()
            runCatching { muxer.release() }
        }
    }
}
