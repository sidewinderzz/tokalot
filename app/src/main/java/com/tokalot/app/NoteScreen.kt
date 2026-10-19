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

    private var silenceCuts: List<ClosedFloatingPointRange<Double>> = emptyList()
    private var folded = true
    private var summaryShown = false
    private var timelineShown = false

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
        val voice = file != null && words.isNotEmpty()
        silenceCuts = info?.silence.orEmpty()
        if (voice && info?.silence == null) Thread {
            val found = runCatching { NoteImport.findSilence(this, id) }.getOrDefault(emptyList())
            runOnUiThread { if (!released) { silenceCuts = found; recut() } }
        }.start()
        recut()

        // A note from a recording reads like any other note: its text first, with the player in a slim bar below.
        val stamp = java.text.SimpleDateFormat("EEE, MMM d · h:mm a", java.util.Locale.US).format(java.util.Date(note.time)) +
            (info?.source?.let { " · from $it" } ?: "")
        val more = iconButton(R.drawable.ic_more, "More", 22, C.SUB) {}
        col.addView(row(text(stamp, 14f, C.SUB).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }, more).apply { gravity = Gravity.CENTER_VERTICAL }, lp().margins(this, t = 4, b = 8))

        val body = if (voice) text(spanned(), 17f).apply {
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = 0
            setLineSpacing(0f, 1.25f)
            transcript = this
        } else text(note.text, 17f).apply { setTextIsSelectable(true); setLineSpacing(0f, 1.2f) }
        col.addView(card(20).apply { addView(body) }, lp())
        val foldLink = link("", 14f) {}
        col.addView(foldLink, lp().margins(this, l = 4))

        // Summary and Timeline: two small buttons under the text, each opening below it. While one is open a long
        // text folds up to a few lines, so the summary or timeline is in view.
        val summary = summaryCard(note.text, voice)
        val segments = info?.segments.orEmpty()
        val timeline = if (voice && segments.size >= 2) indexCard(segments) else null
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun layout() {
            summary.visibility = if (summaryShown) View.VISIBLE else View.GONE
            timeline?.visibility = if (timelineShown) View.VISIBLE else View.GONE
            val long = body.lineCount > 6 || note.text.length > 400
            val fold = (summaryShown || timelineShown) && long
            body.maxLines = if (fold && folded) 5 else Int.MAX_VALUE
            body.ellipsize = if (fold && folded) android.text.TextUtils.TruncateAt.END else null
            foldLink.visibility = if (fold) View.VISIBLE else View.GONE
            foldLink.text = if (folded) "Show the whole text" else "Fold it up"
            buttons.removeAllViews()
            buttons.addView(pill("Summary", filled = summaryShown) {
                summaryShown = !summaryShown; folded = true; layout(); if (summaryShown) summaryOpened?.invoke()
            })
            if (timeline != null) {
                buttons.addView(spacer(wDp = 8))
                buttons.addView(pill("Timeline", filled = timelineShown) {
                    timelineShown = !timelineShown; folded = true; layout(); if (timelineShown) indexOpened?.invoke()
                })
            }
        }
        foldLink.setOnClickListener { folded = !folded; layout() }
        col.addView(buttons, lp().margins(this, t = 4, b = 12))
        col.addView(summary, lp().margins(this, b = 12))
        timeline?.let { col.addView(it, lp().margins(this, b = 12)) }
        layout()

        // The less used settings and actions sit behind the "..." button.
        more.setOnClickListener {
            val items = mutableListOf<Pair<String, () -> Unit>>(
                "Summary length: ${NoteMedia.LEVEL_NAMES[summaryLevel() - 1]}" to {
                    choose("Summary length", NoteMedia.LEVEL_NAMES.toList(), summaryLevel() - 1) { i ->
                        prefs.noteSummaryLevel = i + 1
                        summaryLevelChanged?.invoke()
                    }
                    Unit
                },
            )
            if (voice) {
                items += "Skip silence: ${if (prefs.noteSkipSilence) "on" else "off"}" to {
                    prefs.noteSkipSilence = !prefs.noteSkipSilence; recut()
                }
                items += "Skip ums: ${if (prefs.noteSkipUms) "on" else "off"}" to {
                    prefs.noteSkipUms = !prefs.noteSkipUms; recut()
                }
            }
            items += "Copy text" to { copy(note.text) }
            items += "Share" to {
                startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, note.text), "Share note"))
            }
            items += "Delete note" to {
                confirm(if (file != null) "Delete this note and its recording? The text stays in history." else "Delete this note? It stays in history.", "Delete") {
                    release()
                    if (runCatching { Notes.delete(this, id) }.isFailure) toast("Couldn't delete the note")
                    @Suppress("DEPRECATION") onBackPressed()
                }
                Unit
            }
            popupMenu(more, items)
        }

        if (!voice) return scroll(col)
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll(col), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(View(this@with).apply { setBackgroundColor(C.LINE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            addView(playerBar(file!!), lp())
        }
    }

    private fun summaryLevel() = a.prefs.noteSummaryLevel

    /** What playback jumps over: stretches with no voice (heard in the recording itself) and "um"s, as switched on. */
    private fun recut() {
        cuts = NoteMedia.merge((if (a.prefs.noteSkipSilence) silenceCuts else emptyList()) +
            (if (a.prefs.noteSkipUms) NoteMedia.fillerCuts(words) else emptyList()))
    }

    // ---------- the player ----------

    private val speeds = NOTE_SPEEDS
    private var speedLabelView: TextView? = null

    private fun speed() = a.prefs.noteSpeed

    private fun speedLabel() = speed().let { if (it == it.toInt().toFloat()) "${it.toInt()}×" else "$it×" }

    /** The slim bar under the note: play, the position, the time and the speed. */
    private fun playerBar(file: java.io.File): View = with(a) {
        playButton = iconButton(R.drawable.ic_play, "Play", 26) { toggle(file) }
        clock = text("0:00 / ${NoteMedia.clock(duration)}", 13f, C.SUB).apply { fontFeatureSettings = "tnum" }
        seek = SeekBar(this).apply {
            max = (duration * 1000).toInt().coerceAtLeast(1)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) player?.let { seekingTo = p / 1000.0; it.seekTo(p.toLong(), MediaPlayer.SEEK_CLOSEST); show(p) }
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        val speedPill = pill(speedLabel(), desc = "Playback speed") {
            val next = speeds[(speeds.indexOf(speed()) + 1) % speeds.size]
            a.prefs.noteSpeed = next
            speedLabelView?.text = speedLabel()
            applySpeed()
        }
        speedLabelView = speedPill.getChildAt(0) as? TextView
        row(playButton!!, seek!!.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) },
            clock!!, spacer(wDp = 6), speedPill).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.CARD)
            setPadding(dp(12), dp(6), dp(16), dp(8))
        }
    }

    /** Only while playing: setting a speed on a paused player starts it. */
    private fun applySpeed() {
        val p = player ?: return
        if (p.isPlaying) runCatching { p.playbackParams = p.playbackParams.setSpeed(speed()) }
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
                setOnSeekCompleteListener { seekingTo = null }
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
            applySpeed()
            playButton?.setImageResource(R.drawable.ic_pause)
            playButton?.contentDescription = "Pause"
            main.removeCallbacksAndMessages(null) // one ticker at a time
            tick()
        }
    }

    private fun jump(t: Double) {
        val file = NoteImport.audioFile(a, id) ?: return
        val p = prepare(file) ?: return
        seekingTo = t
        p.seekTo((t * 1000).toLong(), MediaPlayer.SEEK_CLOSEST)
        if (!p.isPlaying) toggle(file) else show((t * 1000).toInt())
    }

    /** About 20 times a second while playing: jumps over a cut, moves the highlight and the bar. */
    private fun tick() {
        if (released) return
        val p = player ?: return
        if (!p.isPlaying) return
        val now = p.currentPosition / 1000.0
        // A jump takes a moment to land; until it does, the old position would ask for the same jump again.
        if (seekingTo == null) NoteMedia.skipTo(cuts, now)?.let { to ->
            if (to >= duration - 0.05) { p.pause(); p.seekTo(0); seekingTo = null; playButton?.setImageResource(R.drawable.ic_play); show(0); return }
            seekingTo = to
            p.seekTo((to * 1000).toLong(), MediaPlayer.SEEK_CLOSEST)
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
    private var seekingTo: Double? = null

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
        val lengthLabel = text("", 13f, C.SUB)
        c.addView(lengthLabel, lp().margins(this, t = 2, b = 8))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var level = summaryLevel()
        var busy = false
        fun fill() {
            lengthLabel.text = "${NoteMedia.LEVEL_NAMES[level - 1]} · change the length in the ⋮ menu"
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
        // A new length from the menu shows that summary, making it first if the summary is open.
        summaryLevelChanged = {
            level = summaryLevel()
            if (summaryShown && !busy && NoteImport.info(this, id)?.summary(level) == null) summarize(level) else fill()
        }
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
        c.addView(body)
        c
    }

    private var runner: ((Int) -> Unit)? = null
    private var summaryOpened: (() -> Unit)? = null
    private var indexOpened: (() -> Unit)? = null
    private var summaryLevelChanged: (() -> Unit)? = null
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
