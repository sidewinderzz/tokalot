package com.tokalot.app

import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/**
 * One note, opened from the Notes page. A shared voice note gets a player whose transcript follows along (tap a
 * word to jump there) and can skip silence and "um"s, plus an index of its points. Every note can be summarized at
 * five levels of detail. Summaries and the index are saved with the note, so they're only made once.
 */
class NoteScreen(private val a: MainActivity, private val id: Long) {
    private val main = Handler(Looper.getMainLooper())
    private var released = false
    private var player: MediaPlayer? = null
    private var words: List<NoteMedia.Word> = emptyList()
    private var cuts: List<ClosedFloatingPointRange<Double>> = emptyList()
    private var span: SpannableString? = null
    private var starts = IntArray(0)
    private var lit = -1
    private var transcript: TextView? = null
    private var playButton: android.widget.ImageView? = null
    private var seek: SeekBar? = null
    private var clock: TextView? = null
    private var duration = 0.0
    private val opts by lazy { a.getSharedPreferences("note_player", 0) }

    fun build(): View = with(a) {
        val col = column()
        val note = Notes.all(this).firstOrNull { it.id == id }
        if (note == null) {
            col.addView(text("This note was deleted.", 16f, C.SUB), lp().margins(this, t = 12))
            return scroll(col)
        }
        val info = NoteImport.info(this, id)
        val file = NoteImport.audioFile(this, id)
        words = info?.words.orEmpty()
        duration = info?.duration ?: 0.0
        val stamp = java.text.SimpleDateFormat("EEE, MMM d · h:mm a", java.util.Locale.US).format(java.util.Date(note.time))
        col.addView(heading(if (file != null) "Voice note" else "Note", 34f), lp().margins(this, t = 8))
        col.addView(text(if (file != null && duration > 0) "$stamp · ${NoteMedia.clock(duration)}" else stamp, 14f, C.SUB), lp().margins(this, b = 14))

        if (file != null && words.isNotEmpty()) {
            col.addView(playerCard(file), lp().margins(this, b = 12))
            col.addView(card(20).apply {
                transcript = text(spanned(), 17f).apply {
                    movementMethod = LinkMovementMethod.getInstance()
                    highlightColor = 0
                    setLineSpacing(0f, 1.25f)
                }
                addView(transcript)
            }, lp().margins(this, b = 12))
        } else {
            col.addView(card(20).apply {
                addView(text(note.text, 17f).apply { setTextIsSelectable(true); setLineSpacing(0f, 1.2f) })
            }, lp().margins(this, b = 12))
        }

        // The summary and timeline stay out of the way: a small button each under the note, opened on a tap.
        val summary = summaryCard(note.text, file != null).apply { visibility = View.GONE }
        val segments = info?.segments.orEmpty()
        val timeline = if (file != null && segments.size >= 2) indexCard(segments).apply { visibility = View.GONE } else null
        fun toggle(v: View, open: () -> Unit) {
            v.visibility = if (v.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            if (v.visibility == View.VISIBLE) open()
        }
        val actions = mutableListOf<View>(pill("Summary") { toggle(summary) { summaryOpened?.invoke() } })
        if (timeline != null) actions += listOf(spacer(wDp = 8), pill("Timeline") { toggle(timeline) { indexOpened?.invoke() } })
        actions += listOf(
            weightSpacer(),
            iconButton(R.drawable.ic_copy, "Copy note", 20, C.SUB) { copy(note.text) },
            iconButton(R.drawable.ic_share, "Share note", 20, C.SUB) {
                startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, note.text), "Share note"))
            },
            iconButton(R.drawable.ic_delete, "Delete note", 20, C.SUB) {
                confirm(if (file != null) "Delete this note and its recording? The text stays in history." else "Delete this note? It stays in history.", "Delete") {
                    release()
                    if (runCatching { Notes.delete(this, id) }.isFailure) toast("Couldn't delete the note")
                    @Suppress("DEPRECATION") onBackPressed()
                }
            },
        )
        col.addView(row(*actions.toTypedArray()).apply { gravity = Gravity.CENTER_VERTICAL }, lp().margins(this, b = 12))
        col.addView(summary, lp().margins(this, b = 12))
        timeline?.let { col.addView(it, lp().margins(this, b = 12)) }
        scroll(col)
    }

    // ---------- the player ----------

    private fun playerCard(file: java.io.File): View = with(a) {
        val c = card(16)
        playButton = iconButton(R.drawable.ic_play, "Play", 26) { toggle(file) }
        clock = text("0:00 / ${NoteMedia.clock(duration)}", 14f, C.SUB).apply { fontFeatureSettings = "tnum" }
        seek = SeekBar(this).apply {
            max = (duration * 1000).toInt().coerceAtLeast(1)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) player?.seekTo(p) }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        c.addView(row(playButton!!, seek!!.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }, clock!!).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        val skipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val saved = text("", 13f, C.SUB)
        fun redraw() {
            skipRow.removeAllViews()
            val silence = opts.getBoolean("silence", true)
            val ums = opts.getBoolean("ums", true)
            cuts = NoteMedia.merge((if (silence) NoteMedia.gapCuts(words, duration) else emptyList()) +
                (if (ums) NoteMedia.fillerCuts(words) else emptyList()))
            skipRow.addView(pill("Skip silence", filled = silence) { opts.edit().putBoolean("silence", !silence).apply(); redraw() })
            skipRow.addView(spacer(wDp = 8))
            skipRow.addView(pill("Skip ums", filled = ums) { opts.edit().putBoolean("ums", !ums).apply(); redraw() })
            skipRow.addView(spacer(wDp = 10))
            val total = NoteMedia.total(cuts)
            saved.text = if (total >= 1) "Saves ${NoteMedia.clock(total)}" else ""
            skipRow.addView(saved)
        }
        redraw()
        c.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(skipRow) }, lp().margins(this, t = 6))
        c
    }

    /** The transcript with every word tappable. The tap jumps there and plays. */
    private fun spanned(): SpannableString {
        val text = NoteMedia.text(words)
        val s = SpannableString(text)
        starts = IntArray(words.size)
        var at = 0
        words.forEachIndexed { i, w ->
            val from = text.indexOf(w.text, at).takeIf { it >= 0 } ?: at
            starts[i] = from
            val to = (from + w.text.length).coerceAtMost(text.length)
            s.setSpan(object : ClickableSpan() {
                override fun onClick(v: View) { jump(w.start) }
                override fun updateDrawState(ds: TextPaint) { ds.color = C.TEXT; ds.isUnderlineText = false }
            }, from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            at = to
        }
        span = s
        return s
    }

    private fun prepare(file: java.io.File): MediaPlayer? {
        player?.let { return it }
        return try {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener { playButton?.setImageResource(R.drawable.ic_play); show(0) }
                if (this@NoteScreen.duration <= 0) this@NoteScreen.duration = getDuration() / 1000.0
            }.also { player = it }
        } catch (e: Exception) {
            a.toast("This phone can't play that recording")
            null
        }
    }

    private fun toggle(file: java.io.File) {
        val p = prepare(file) ?: return
        if (p.isPlaying) pause() else {
            p.start()
            playButton?.setImageResource(R.drawable.ic_pause)
            playButton?.contentDescription = "Pause"
            main.removeCallbacksAndMessages(null) // one ticker at a time
            tick()
        }
    }

    private fun jump(t: Double) {
        val file = NoteImport.audioFile(a, id) ?: return
        val p = prepare(file) ?: return
        p.seekTo((t * 1000).toInt())
        if (!p.isPlaying) toggle(file) else show(p.currentPosition)
    }

    /** About 20 times a second while playing: jumps over a cut, moves the highlight and the bar. */
    private fun tick() {
        if (released) return
        val p = player ?: return
        if (!p.isPlaying) return
        val ms = p.currentPosition
        NoteMedia.skipTo(cuts, ms / 1000.0)?.let { to ->
            if (to >= duration - 0.05) { p.pause(); p.seekTo(0); playButton?.setImageResource(R.drawable.ic_play); show(0); return }
            p.seekTo((to * 1000).toInt())
        }
        show(p.currentPosition)
        main.postDelayed({ tick() }, 50)
    }

    private fun show(ms: Int) {
        seek?.progress = ms
        clock?.text = "${NoteMedia.clock(ms / 1000.0)} / ${NoteMedia.clock(duration)}"
        val i = NoteMedia.wordAt(words, ms / 1000.0 + 0.02)
        val s = span ?: return
        if (i == lit) return
        lit = i
        if (i >= 0 && i < starts.size) {
            val color = (C.visible(a.prefs.accent) and 0x00FFFFFF) or 0x55000000
            val hl = BackgroundColorSpan(color)
            s.setSpan(hl, starts[i], (starts[i] + words[i].text.length).coerceAtMost(s.length), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            current?.let { s.removeSpan(it) }
            current = hl
        } else current?.let { s.removeSpan(it); current = null }
        transcript?.text = s
    }

    private var current: BackgroundColorSpan? = null

    fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
        playButton?.setImageResource(R.drawable.ic_play)
        playButton?.contentDescription = "Play"
    }

    /** The screen is going away: stop the sound and any pending updates. */
    fun release() {
        released = true
        main.removeCallbacksAndMessages(null)
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    // ---------- summaries ----------

    private fun summaryCard(noteText: String, voice: Boolean): View = with(a) {
        val c = card(18)
        c.addView(text("Summary", 16f, bold = true))
        val levels = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var level = opts.getInt("level", 2)
        var busy = false
        fun fill() {
            levels.removeAllViews()
            NoteMedia.LEVEL_NAMES.forEachIndexed { i, name ->
                if (i > 0) levels.addView(spacer(wDp = 6))
                levels.addView(pill(name, filled = level == i + 1) {
                    level = i + 1
                    opts.edit().putInt("level", level).apply()
                    if (!busy && NoteImport.info(this, id)?.summary(level) == null) summarize(level) else fill()
                })
            }
            body.removeAllViews()
            val saved = NoteImport.info(this, id)?.summary(level)
            when {
                busy -> body.addView(text("Summarizing…", 15f, C.SUB))
                saved != null -> {
                    body.addView(text(saved, 16f).apply { setTextIsSelectable(true); setLineSpacing(0f, 1.2f) })
                    body.addView(row(weightSpacer(),
                        iconButton(R.drawable.ic_copy, "Copy summary", 20, C.SUB) { copy(saved) },
                        pill("Redo") { summarize(level) },
                    ))
                }
                else -> body.addView(row(pill("Summarize", filled = true) { summarize(level) }))
            }
        }
        // Opening it asks for a summary at the chosen length if there isn't one yet.
        summaryOpened = { if (!busy && NoteImport.info(this, id)?.summary(level) == null) summarize(level) }
        // Kept here so fill() and the call share the same busy flag.
        runner = { lv ->
            busy = true
            fill()
            Thread {
                val result = runCatching {
                    NoteImport.ask(prefs, NoteMedia.summarySystem(lv, voice), NoteMedia.summaryUser(noteText), NoteMedia.summaryMaxTokens(lv))
                }
                result.getOrNull()?.let { s ->
                    NoteImport.update(this, id) { o -> (o.optJSONObject("summaries") ?: org.json.JSONObject().also { o.put("summaries", it) }).put("$lv", s) }
                }
                runOnUiThread {
                    if (released) return@runOnUiThread
                    busy = false
                    result.exceptionOrNull()?.let { toast("Couldn't summarize: ${it.message}") }
                    fill()
                }
            }.start()
        }
        fill()
        c.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(levels) }, lp().margins(this, t = 6, b = 6))
        c.addView(body)
        c
    }

    private var runner: ((Int) -> Unit)? = null
    private var summaryOpened: (() -> Unit)? = null
    private var indexOpened: (() -> Unit)? = null
    private fun summarize(level: Int) { runner?.invoke(level) }

    // ---------- the index ----------

    private fun indexCard(segments: List<NoteMedia.Segment>): View = with(a) {
        val c = card(18)
        c.addView(text("Timeline", 16f, bold = true))
        c.addView(text("Every point in the order it was said. Tap one to hear it.", 14f, C.SUB), lp().margins(this, t = 2, b = 8))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(body)
        fun fill(busy: Boolean) {
            body.removeAllViews()
            val points = NoteImport.info(this, id)?.index
            when {
                busy -> body.addView(text("Making the timeline…", 15f, C.SUB))
                points == null -> body.addView(row(pill("Make the timeline", filled = true) { make(segments, ::fill) }))
                points.isEmpty() -> body.addView(text("Nothing to index.", 15f, C.SUB))
                else -> {
                    var heading = ""
                    for (p in points) {
                        if (p.topic.isNotEmpty() && !p.topic.equals(heading, true)) {
                            heading = p.topic
                            body.addView(text(p.topic.uppercase(java.util.Locale.US), 12f, C.SUB, bold = true).apply { letterSpacing = 0.08f },
                                lp().margins(this, t = 10, b = 2))
                        }
                        body.addView(LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            minimumHeight = dp(TOUCH_DP)
                            gravity = Gravity.CENTER_VERTICAL
                            addView(text(NoteMedia.clock(p.time), 14f, C.LINK).apply { fontFeatureSettings = "tnum" },
                                LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
                            addView(text(p.text, 15f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                            contentDescription = "${NoteMedia.clock(p.time)}, ${p.text}"
                            setOnClickListener { jump(p.time) }
                        })
                    }
                    body.addView(row(weightSpacer(), pill("Redo") { make(segments, ::fill) }))
                }
            }
        }
        fill(false)
        var making = false
        indexOpened = {
            if (!making && NoteImport.info(this, id)?.index == null) {
                making = true
                make(segments) { b -> if (!b) making = false; fill(b) }
            }
        }
        c
    }

    private fun make(segments: List<NoteMedia.Segment>, fill: (Boolean) -> Unit) = with(a) {
        fill(true)
        Thread {
            val result = runCatching {
                NoteMedia.parseIndex(NoteImport.ask(prefs, NoteMedia.INDEX_SYSTEM, NoteMedia.indexUser(segments), 2400), segments)
            }
            result.getOrNull()?.let { points ->
                NoteImport.update(this, id) { o ->
                    o.put("index", org.json.JSONArray().also { arr -> points.forEach { arr.put(org.json.JSONArray().put(it.time).put(it.topic).put(it.text)) } })
                }
            }
            runOnUiThread {
                if (released) return@runOnUiThread
                result.exceptionOrNull()?.let { toast("Couldn't make the timeline: ${it.message}") }
                fill(false)
            }
        }.start()
    }
}
