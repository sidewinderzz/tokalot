package com.tokalot.app

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.graphics.Typeface
import android.graphics.drawable.InsetDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

/**
 * One note, opened from the Notes page. A shared voice note gets a player whose transcript follows along (tap a
 * word to jump there) and can skip silence and "um"s, plus an index of its points. Every note can be summarized at
 * five levels of detail and gets a short title from the AI. Summaries, the index and the title are saved with the
 * note, so they're only made once.
 */
class NoteScreen(private val a: MainActivity, private val id: Long, private val find: String = "") {
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
    private var elapsed: TextView? = null
    private var remaining: TextView? = null
    private var duration = 0.0

    private var silenceCuts: List<ClosedFloatingPointRange<Double>> = emptyList()
    private var summaryShown = false
    private var timelineShown = false

    /** The page scrolls itself to keep the lit word in view, until the user scrolls it by hand. */
    private var scroller: ScrollView? = null
    private var following = true
    private var backChip: View? = null
    private var hint: View? = null

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

        // The title heads the page. A note without one gets one from the AI in the background; failing is quiet.
        val titleView = heading("", 28f)
        fun showTitle(t: String?) { titleView.text = t.orEmpty(); titleView.visibility = if (t == null) View.GONE else View.VISIBLE }
        showTitle(info?.title)
        col.addView(titleView, lp().margins(this, t = 4))
        if (info?.title == null && info?.titleOff != true) Thread {
            NoteImport.makeTitle(this, id, note.text)?.let { t -> runOnUiThread { if (!released) showTitle(t) } }
        }.start()

        // A note from a recording reads like any other note: its text first, with the player in a slim bar below.
        val stamp = java.text.SimpleDateFormat("EEE, MMM d · h:mm a", java.util.Locale.US).format(java.util.Date(note.time)) +
            (info?.source?.let { " · from $it" } ?: "")
        val more = iconButton(R.drawable.ic_more, "More", 22, C.SUB) {}
        col.addView(row(text(stamp, 14f, C.SUB).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }, more).apply { gravity = Gravity.CENTER_VERTICAL }, lp().margins(this, t = 4, b = 8))

        // Its tags: tap one for every note with it, "+ Tag" to add or remove some.
        val tagRow = FlowRow(this, dp(6))
        fun showTags(tags: List<String>) {
            tagRow.removeAllViews()
            tags.forEach { t -> tagRow.addView(tagChip(t) { showTag(t) }.apply { contentDescription = "Tag $t. Show notes with it" }) }
            tagRow.addView(tagChip("+ Tag", dashed = true) { editTags(note.id) { showTags(it) } }.apply { contentDescription = "Add or remove tags" })
        }
        showTags(note.tags)
        col.addView(tagRow, lp().margins(this, t = -4, b = 4))

        val body = if (voice) text(spanned(), 17f).apply {
            movementMethod = LinkMovementMethod.getInstance()
            highlightColor = 0
            setLineSpacing(0f, 1.25f)
            transcript = this
            readAsOneBlock(this)
        } else text(marked(note.text), 17f).apply { setTextIsSelectable(true); setLineSpacing(0f, 1.2f) }
        // Summary and Timeline: two small buttons above the text, each opening right under them. They're reached
        // for instead of reading, so they come first and the text follows.
        val summary = summaryCard(note.text, voice)
        val segments = info?.segments.orEmpty()
        val timeline = if (voice && segments.size >= 2) indexCard(segments) else null
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        fun layout() {
            summary.visibility = if (summaryShown) View.VISIBLE else View.GONE
            timeline?.visibility = if (timelineShown) View.VISIBLE else View.GONE
            buttons.removeAllViews()
            buttons.addView(quietPill("≡", null, "Summary", summaryShown) {
                summaryShown = !summaryShown; layout(); if (summaryShown) summaryOpened?.invoke()
            })
            if (timeline != null) {
                buttons.addView(spacer(wDp = 8))
                buttons.addView(quietPill(null, R.drawable.ic_clock, "Timeline", timelineShown) {
                    timelineShown = !timelineShown; layout(); if (timelineShown) indexOpened?.invoke()
                })
            }
        }
        col.addView(buttons, lp().margins(this, b = 8))
        col.addView(summary, lp().margins(this, b = 12))
        timeline?.let { col.addView(it, lp().margins(this, b = 12)) }
        layout()
        // Once, until the first word tap or the ✕: how the transcript works.
        if (voice && !prefs.noteWordHintSeen) {
            hint = row(
                text("Tap any word to play from there", 14f, C.SUB).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                },
                iconButton(R.drawable.ic_close, "Hide this tip", 18, C.SUB) { hideHint() },
            ).apply { setPadding(dp(4), 0, 0, 0) }
            col.addView(hint, lp().margins(this, t = -4, b = 4))
        }
        col.addView(card(20).apply { addView(body) }, lp())

        // The less used settings and actions sit behind the "..." button.
        more.setOnClickListener {
            val items = mutableListOf<Pair<String, () -> Unit>>(
                "Rename" to {
                    val f = field("Title", NoteImport.info(this, id)?.title.orEmpty())
                    sheet("Rename", f, positive = "Save") {
                        // An empty title removes it, and the AI leaves this note untitled from then on.
                        val t = f.text.toString().trim().take(80)
                        NoteImport.setTitle(this, id, t)
                        showTitle(t.ifEmpty { null })
                    }
                    Unit
                },
            )
            items += "Tags" to { editTags(note.id) { showTags(it) } }
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
            // No question asked: the note goes at once and the bar that says so has Undo.
            items += "Delete note" to { release(); deleteNote(id) }
            popupMenu(more, items)
        }

        if (!voice) return scroll(col)
        val sv = scroll(col).also { scroller = it }
        // A drag or a wheel turn is the user reading elsewhere: stop following until "Back to playing".
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downY = 0f
        sv.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> downY = e.y
                MotionEvent.ACTION_MOVE -> if (Math.abs(e.y - downY) > slop) handScrolled()
            }
            false
        }
        sv.setOnGenericMotionListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_SCROLL) handScrolled(); false }
        backChip = pill("Back to playing", filled = true) {
            following = true
            follow(lit, force = true)
            updateChip()
        }.apply { visibility = View.GONE }
        val area = FrameLayout(this).apply {
            addView(sv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(backChip, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(8) })
        }
        if (find.isNotEmpty()) sv.post { if (!released) goToFind() }
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(area, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(View(this@with).apply { setBackgroundColor(C.LINE) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            addView(playerBar(file!!), lp())
        }
    }

    private fun summaryLevel() = a.prefs.noteSummaryLevel

    /**
     * Picks this note's tags: every tag already used, ticked if this note has it, and a box for a new one.
     * Saves on Save and hands the new tags to [done].
     */
    private fun editTags(noteId: Long, done: (List<String>) -> Unit) = with(a) {
        val all = Notes.all(this)
        val mine = all.firstOrNull { it.id == noteId }?.tags.orEmpty()
        val picked = LinkedHashSet(mine)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun line(t: String) = android.widget.CheckBox(this).apply {
            text = t
            textSize = 17f
            setTextColor(C.TEXT)
            buttonTintList = android.content.res.ColorStateList.valueOf(C.visible(prefs.accent))
            minHeight = dp(TOUCH_DP)
            isChecked = t in picked
            setOnCheckedChangeListener { _, on -> if (on) picked.add(t) else picked.remove(t) }
        }
        Notes.allTags(all).forEach { list.addView(line(it), lp()) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (list.childCount == 0) box.addView(text("No tags yet. Type one below, like Farm, Shop or Ideas.", 15f, C.SUB), lp().margins(this, b = 8))
        box.addView(list)
        val f = field("New tag")
        fun addTyped() {
            val t = Notes.cleanTags(listOf(f.text.toString())).firstOrNull() ?: return
            // The same tag in other capitals is the one already there.
            val same = (0 until list.childCount).map { list.getChildAt(it) as android.widget.CheckBox }
                .firstOrNull { it.text.toString().equals(t, ignoreCase = true) }
            if (same != null) same.isChecked = true else { picked.add(t); list.addView(line(t), lp()) }
            f.setText("")
        }
        f.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        f.setOnEditorActionListener { _, _, _ -> addTyped(); true }
        box.addView(row(
            f.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) },
            spacer(wDp = 8),
            pill("Add") { addTyped() },
        ), lp().margins(this, t = 8))
        sheet("Tags", box, positive = "Save") {
            addTyped() // typed but not added yet: meant to be
            val tags = Notes.cleanTags(picked.toList())
            if (tags != mine) runCatching { Notes.setTags(this, noteId, tags) }.onFailure { toast("Couldn't save the tags") }
            done(tags)
        }
        Unit
    }

    /** [s] with the words searched for marked, as the Notes list marks them. */
    private fun marked(s: String): CharSequence {
        val words = NoteFind.words(find)
        if (words.isEmpty()) return s
        val sp = SpannableString(s)
        val hl = (C.visible(a.prefs.accent) and 0x00FFFFFF) or 0x44000000
        NoteFind.hits(s, words).forEach { sp.setSpan(BackgroundColorSpan(hl), it.first, it.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        return sp
    }

    /**
     * Opened from a search: the transcript has the words marked, the page scrolls to the first, and a voice note is
     * set to play from there.
     */
    private fun goToFind() {
        val words = NoteFind.words(find)
        if (transcript == null || words.isEmpty()) return
        val i = this.words.indexOfFirst { w -> words.any { w.text.lowercase().contains(it) } }
        if (i < 0) return
        following = true
        follow(i, force = true)
        val file = NoteImport.audioFile(a, id) ?: return
        val p = prepare(file) ?: return
        val t = this.words[i].start
        seekingTo = t
        p.seekTo((t * 1000).toLong(), MediaPlayer.SEEK_CLOSEST)
        show((t * 1000).toInt())
    }

    /**
     * Summary and Timeline: small outlined buttons with a leading glyph (text or icon), filled while their card is
     * open. The pill is drawn 32dp tall inside a full 48dp touch target.
     */
    private fun quietPill(glyph: String?, iconRes: Int?, label: String, open: Boolean, onClick: () -> Unit): View = with(a) {
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val gap = dp((TOUCH_DP - 32) / 2)
            background = InsetDrawable(pressable(if (open) rounded(C.TEXT, 100) else rounded(C.BG, 100, C.PILL, 1)), 0, gap, 0, gap)
            setPadding(dp(12), gap, dp(14), gap)
            minimumHeight = dp(TOUCH_DP)
            val fg = if (open) C.CARD else C.TEXT
            if (glyph != null) addView(text(glyph, 14f, fg).apply { setPadding(0, 0, dp(6), 0) })
            if (iconRes != null) addView(icon(iconRes, 14, fg).apply {
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).margins(this@with, r = 6)
            })
            addView(text(label, 13f, fg))
            contentDescription = if (open) "$label, open" else label
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    /** Three grey bars that gently pulse, standing in for text on its way. [desc] is what a screen reader says. */
    private fun skeleton(desc: String): View = with(a) {
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            contentDescription = desc
            for (w in floatArrayOf(1f, 0.82f, 0.55f)) addView(LinearLayout(this@with).apply {
                weightSum = 1f
                addView(View(this@with).apply { background = rounded(if (C.dark) C.PILL else C.LINE, 6) },
                    LinearLayout.LayoutParams(0, dp(12), w))
            }, lp().margins(this@with, t = 4, b = 6))
            val pulse = ObjectAnimator.ofFloat(this, View.ALPHA, 1f, 0.4f).apply {
                duration = 800
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
            }
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { pulse.start() }
                override fun onViewDetachedFromWindow(v: View) { pulse.cancel() }
            })
        }
    }

    /**
     * Screen readers get the transcript as one block of plain text rather than a link per word, plus a
     * "Play from start" action.
     */
    private fun readAsOneBlock(tv: TextView) {
        val plain = NoteMedia.text(words)
        val playFromStart = View.generateViewId()
        tv.contentDescription = plain
        tv.setAccessibilityDelegate(object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.text = plain // without the word spans, which would each be a link
                info.addAction(AccessibilityNodeInfo.AccessibilityAction(playFromStart, "Play from start"))
            }
            override fun performAccessibilityAction(host: View, action: Int, args: Bundle?): Boolean {
                if (action != playFromStart) return super.performAccessibilityAction(host, action, args)
                jump(0.0)
                return true
            }
        })
    }

    private fun hideHint() {
        if (!a.prefs.noteWordHintSeen) a.prefs.noteWordHintSeen = true
        hint?.visibility = View.GONE
    }

    /** What playback jumps over: stretches with no voice (heard in the recording itself) and "um"s, as switched on. */
    private fun recut() {
        cuts = NoteMedia.merge((if (a.prefs.noteSkipSilence) silenceCuts else emptyList()) +
            (if (a.prefs.noteSkipUms) NoteMedia.fillerCuts(words) else emptyList()))
    }

    // ---------- following the lit word ----------

    private fun handScrolled() {
        if (!following) return
        following = false
        updateChip()
    }

    /** "Back to playing" shows while it plays and the page isn't following. */
    private fun updateChip() {
        backChip?.visibility = if (!following && player?.isPlaying == true) View.VISIBLE else View.GONE
    }

    /**
     * Scrolls word [i] to about a quarter of the way down, smoothly, when it has left the band between the top
     * eighth and the upper 60% of the page (or always, with [force]).
     */
    private fun follow(i: Int, force: Boolean = false) {
        val sv = scroller ?: return
        val tv = transcript ?: return
        val layout = tv.layout ?: return
        if (i < 0 || i >= starts.size || sv.height == 0) return
        val line = layout.getLineForOffset(starts[i])
        var y = tv.totalPaddingTop + layout.getLineTop(line)
        var v: View = tv
        while (true) { // the line's place in the scrolled page
            y += v.top
            val p = v.parent as? View ?: return
            if (p === sv) break
            v = p
        }
        val at = y - sv.scrollY
        val h = sv.height
        if (force || at < h / 8 || at + (layout.getLineBottom(line) - layout.getLineTop(line)) > h * 6 / 10)
            sv.smoothScrollTo(0, (y - h / 4).coerceAtLeast(0))
    }

    // ---------- the player ----------

    private val speeds = NOTE_SPEEDS
    private var speedLabelView: TextView? = null

    private fun speed() = a.prefs.noteSpeed

    private fun speedLabel() = speed().let { if (it == it.toInt().toFloat()) "${it.toInt()}×" else "$it×" }

    /** The slim bar under the note: play, the position with the time gone and left under it, and the speed. */
    private fun playerBar(file: java.io.File): View = with(a) {
        playButton = iconButton(R.drawable.ic_play, "Play", 26) { toggle(file) }
        elapsed = text("0:00", 12f, C.SUB).apply { fontFeatureSettings = "tnum" }
        remaining = text("-${NoteMedia.clock(duration)}", 12f, C.SUB).apply { fontFeatureSettings = "tnum" }
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
            val now = speeds.indices.firstOrNull { Math.abs(speeds[it] - speed()) < 0.001f } ?: 0
            val next = speeds[(now + 1) % speeds.size]
            a.prefs.noteSpeed = next
            speedLabelView?.text = speedLabel()
            applySpeed()
        }
        speedLabelView = speedPill.getChildAt(0) as? TextView
        // The times sit under the ends of the bar's track, so the bar gets the whole width.
        val middle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(seek, lp())
            addView(row(elapsed!!, weightSpacer(), remaining!!).apply {
                setPadding(seek!!.paddingStart, 0, seek!!.paddingEnd, 0)
            }, lp())
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        row(playButton!!, middle, spacer(wDp = 6), speedPill).apply {
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(C.CARD)
            setPadding(dp(12), dp(6), dp(16), dp(6))
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
                override fun onClick(v: View) { hideHint(); jump(w.start) }
                override fun updateDrawState(ds: TextPaint) { ds.color = C.TEXT; ds.isUnderlineText = false }
            }, from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            at = to
        }
        // Opened from a search: the words searched for are marked (bold, lighter than the word being played).
        val hl = (C.visible(a.prefs.accent) and 0x00FFFFFF) or 0x2E000000
        NoteFind.hits(text, NoteFind.words(find)).forEach {
            s.setSpan(BackgroundColorSpan(hl), it.first, it.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            s.setSpan(android.text.style.StyleSpan(Typeface.BOLD), it.first, it.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
                setOnCompletionListener { stopped() }
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
            following = true
            playButton?.setImageResource(R.drawable.ic_pause)
            playButton?.contentDescription = "Pause"
            main.removeCallbacksAndMessages(null) // one ticker at a time
            tick()
        }
    }

    private fun jump(t: Double) {
        val file = NoteImport.audioFile(a, id) ?: return
        val p = prepare(file) ?: return
        following = true // a word or point picked to hear: bring the page along again
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
            if (to >= duration - 0.05) { p.pause(); p.seekTo(0); seekingTo = null; stopped(); return }
            seekingTo = to
            p.seekTo((to * 1000).toLong(), MediaPlayer.SEEK_CLOSEST)
        }
        show(p.currentPosition)
        updateChip()
        main.postDelayed({ tick() }, 50)
    }

    /** Played to the end: back to the start, with nothing lit. */
    private fun stopped() {
        playButton?.setImageResource(R.drawable.ic_play)
        playButton?.contentDescription = "Play"
        show(0)
        updateChip()
    }

    private fun show(ms: Int) {
        seek?.progress = ms
        elapsed?.text = NoteMedia.clock(ms / 1000.0)
        remaining?.text = "-${NoteMedia.clock((duration - ms / 1000.0).coerceAtLeast(0.0))}"
        markPoint(if (ms > 0 || player?.isPlaying == true) ms / 1000.0 else null)
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
        if (following && player?.isPlaying == true) transcript?.post { follow(i) } // after the text has laid out again
    }

    private var current: BackgroundColorSpan? = null
    private var seekingTo: Double? = null

    fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
        playButton?.setImageResource(R.drawable.ic_play)
        playButton?.contentDescription = "Play"
        updateChip()
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
        // The length shows in the header, and tapping it picks another.
        val lengthPick = text("", 13f, C.SUB).apply {
            minHeight = dp(TOUCH_DP)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        c.addView(row(text("Summary", 16f, bold = true).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }, lengthPick), lp().margins(this, t = -12, b = -6))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var level = summaryLevel()
        var busy = false
        fun fill() {
            val name = NoteMedia.LEVEL_NAMES[level - 1]
            lengthPick.text = "$name ▾"
            lengthPick.contentDescription = "Summary length: $name. Change"
            body.removeAllViews()
            val saved = NoteImport.info(this, id)?.summary(level)
            when {
                busy -> body.addView(skeleton("Summarizing"), lp().margins(this, t = 6))
                saved != null -> {
                    body.addView(text(saved, 16f).apply { setTextIsSelectable(true); setLineSpacing(0f, 1.2f) }, lp().margins(this, t = 4))
                    body.addView(row(weightSpacer(),
                        iconButton(R.drawable.ic_copy, "Copy summary", 20, C.SUB) { copy(saved) },
                        pill("Redo") { summarize(level) },
                    ))
                }
                else -> body.addView(row(pill("Summarize", filled = true) { summarize(level) }))
            }
        }
        lengthPick.setOnClickListener {
            choose("Summary length", NoteMedia.LEVEL_NAMES, summaryLevel() - 1) { i ->
                prefs.noteSummaryLevel = i + 1
                summaryLevelChanged?.invoke()
            }
        }
        // Opening it asks for a summary at the chosen length if there isn't one yet.
        summaryOpened = { if (!busy && NoteImport.info(this, id)?.summary(level) == null) summarize(level) }
        // A new length shows that summary, making it first if the summary is open.
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

    /** A point on the timeline: when it starts, and its row's accent bar and text, lit while it plays. */
    private class PointRow(val time: Double, val bar: View, val text: TextView)
    private val pointRows = ArrayList<PointRow>()
    private var litPoint = -1

    /** Lights the latest point at or before [t] seconds; none for null. */
    private fun markPoint(t: Double?) {
        val i = if (t == null) -1 else pointRows.indexOfLast { it.time <= t + 0.05 }
        if (i == litPoint) return
        pointRows.getOrNull(litPoint)?.let { it.bar.visibility = View.INVISIBLE; it.text.typeface = Typeface.DEFAULT }
        pointRows.getOrNull(i)?.let { it.bar.visibility = View.VISIBLE; it.text.typeface = Typeface.DEFAULT_BOLD }
        litPoint = i
    }

    private fun indexCard(segments: List<NoteMedia.Segment>): View = with(a) {
        val c = card(18)
        c.addView(text("Timeline", 16f, bold = true))
        c.addView(text("Every point in the order it was said. Tap one to hear it.", 14f, C.SUB), lp().margins(this, t = 2, b = 8))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c.addView(body)
        fun fill(busy: Boolean) {
            body.removeAllViews()
            pointRows.clear()
            litPoint = -1
            val points = NoteImport.info(this, id)?.index
            when {
                busy -> body.addView(skeleton("Making the timeline"))
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
                        val bar = View(this).apply { background = rounded(C.visible(prefs.accent), 2); visibility = View.INVISIBLE }
                        val line = text(p.text, 15f)
                        pointRows += PointRow(p.time, bar, line)
                        body.addView(LinearLayout(this).apply {
                            orientation = LinearLayout.HORIZONTAL
                            minimumHeight = dp(TOUCH_DP)
                            gravity = Gravity.CENTER_VERTICAL
                            addView(bar, LinearLayout.LayoutParams(dp(3), dp(20)).margins(this@with, r = 7))
                            addView(text(NoteMedia.clock(p.time), 14f, C.LINK).apply { fontFeatureSettings = "tnum" },
                                LinearLayout.LayoutParams(dp(52), ViewGroup.LayoutParams.WRAP_CONTENT))
                            addView(line, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                            contentDescription = "${NoteMedia.clock(p.time)}, ${p.text}"
                            setOnClickListener { jump(p.time) }
                        })
                    }
                    body.addView(row(weightSpacer(), pill("Redo") { make(segments, ::fill) }))
                    player?.let { pl -> if (pl.isPlaying || pl.currentPosition > 0) markPoint(pl.currentPosition / 1000.0) }
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
