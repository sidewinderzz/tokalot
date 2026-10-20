package com.tokalot.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

private fun EditText.onChange(block: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun afterTextChanged(s: Editable?) = block(s?.toString() ?: "")
})

/** A title/subtitle row with an on/off switch. */
private fun MainActivity.switchRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit): View {
    val sw = themedSwitch(on, prefs.accent)
    val texts = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(title, 17f))
        if (sub.isNotEmpty()) addView(text(sub, 14f, C.SUB))
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(20), dp(14), dp(16), dp(14))
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(sw)
        sw.setOnCheckedChangeListener { _, v -> onChange(v) }
        setOnClickListener { sw.toggle() }
    }
}

/** A tappable row with a round selection dot, used for picking providers and styles. */
private fun MainActivity.choiceRow(title: String, sub: String, selected: Boolean, onPick: () -> Unit): View {
    val dot = View(this).apply {
        background = if (selected) rounded(C.TEXT, 100) else rounded(C.CARD, 100, C.PILL, 2)
    }
    val texts = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(title, 17f, bold = selected))
        if (sub.isNotEmpty()) addView(text(sub, 14f, C.SUB))
    }
    return LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(18), dp(14), dp(18), dp(14))
        addView(dot, LinearLayout.LayoutParams(dp(20), dp(20)))
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).margins(this@choiceRow, l = 14))
        setOnClickListener { onPick() }
    }
}

private fun MainActivity.intro(col: LinearLayout, title: String, sub: String) {
    col.addView(heading(title), lp().margins(this, t = 8))
    col.addView(text(sub, 15f, C.SUB), lp().margins(this, t = 4, b = 18))
}

// ------------------------------------------------------------------ Dictionary

/**
 * Whether Android shows its floating accessibility button for Tokalot (Android 11 and newer). These are Android's
 * own settings, read only: "accessibility_button_targets" lists the services on the button, and
 * "accessibility_button_mode" 1 means it floats on the screen rather than sitting in the navigation bar.
 */
private fun MainActivity.shortcutFloats(): Boolean = runCatching {
    val targets = Settings.Secure.getString(contentResolver, "accessibility_button_targets").orEmpty()
    targets.split(':').any { it.startsWith("$packageName/") } &&
        Settings.Secure.getInt(contentResolver, "accessibility_button_mode", 0) == 1
}.getOrDefault(false)

/** Android's page for Tokalot's accessibility shortcut where the phone has one (Android 12 and newer), else the accessibility list. */
internal fun MainActivity.openShortcutSettings() {
    val detail = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
        .putExtra(Intent.EXTRA_COMPONENT_NAME, android.content.ComponentName(this, OfflineFlowService::class.java).flattenToString())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(detail) }.onFailure {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

class DictionaryScreen(private val a: MainActivity) {
    fun build(): View = with(a) {
        val col = column()
        intro(col, "Dictionary", "Names, places and jargon Tokalot should always spell right. They're given to speech recognition and to the cleanup model as hints.")
        suggestionsCard()?.let { col.addView(it, lp().margins(this, b = 16)) }

        val input = field("Add words, separated by commas\ne.g. Kubernetes, Nguyen, PostgreSQL", multiLine = true).apply { minLines = 2 }
        col.addView(input, lp())
        val add = pill("Add", filled = true) {
            // Split on commas or new lines, trim, drop blanks and anything already in the list.
            val existing = prefs.words.map { it.lowercase() }.toMutableSet()
            val fresh = input.text.toString().split(',', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() && existing.add(it.lowercase()) }
            if (fresh.isNotEmpty()) {
                prefs.words = prefs.words + fresh
                toast("Added ${fresh.size} ${if (fresh.size == 1) "word" else "words"}")
                render()
            } else if (input.text.isNotBlank()) {
                toast("Already in your dictionary")
            }
        }
        col.addView(row(weightSpacer(), add), lp().margins(this, t = 10, b = 16))

        col.addView(card().apply {
            addView(switchRow(
                "Learn from my corrections",
                "Off unless you turn it on. After Tokalot types a dictation, it looks at that text box again for up to two minutes. " +
                    "If you respell one word into a name or a term (Kaitlin to Caitlyn, get hub to GitHub), the new spelling is added here, " +
                    "and a note lets you undo it. Ordinary words are left alone. The text is only compared on this phone, " +
                    "never saved or sent; the learned word is all that's kept.",
                prefs.learnWords
            ) { prefs.learnWords = it })
        }, lp().margins(this, b = 16))

        val words = prefs.words
        val learned = prefs.learnedWords
        if (words.isEmpty()) {
            col.addView(card(22).apply { addView(text("No words yet.", 16f, C.SUB)) })
        } else {
            val c = card()
            words.sortedBy { it.lowercase() }.forEachIndexed { i, w ->
                if (i > 0) c.addView(divider())
                val remove = iconButton(R.drawable.ic_close, "Remove $w", 20, C.SUB) {
                    prefs.words = prefs.words - w
                    if (w in learned) prefs.learnedWords = prefs.learnedWords - w
                    render()
                }
                // The X is a full 48dp target, so the row's own padding shrinks to keep its height.
                c.addView(row(
                    text(w, 17f).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) },
                    text(if (w in learned) "learned" else "", 13f, C.SUB),
                    remove
                ).apply { setPadding(dp(20), dp(4), dp(6), dp(4)) })
            }
            col.addView(c)
        }
        scroll(col)
    }
}

// ------------------------------------------------------------------ Snippets

class SnippetsScreen(private val a: MainActivity) {
    fun build(): View = with(a) {
        val col = column()
        intro(col, "Snippets", "Say a trigger phrase and it's replaced with the full text, exactly as you wrote it. The AI never rewrites snippet text.")
        col.addView(row(pill("New snippet", filled = true) { edit(null) }), lp().margins(this, b = 16))

        val list = prefs.snippets
        if (list.isEmpty()) {
            col.addView(card(22).apply {
                addView(text("Example: say \"my email\" and get your full email address, or \"home address\" for your full street address.", 16f, C.SUB))
            })
        } else {
            val c = card()
            list.forEachIndexed { i, s ->
                if (i > 0) c.addView(divider())
                c.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(16), dp(20), dp(16))
                    addView(text("“${s.trigger}”", 17f, bold = true))
                    addView(text(s.text, 15f, C.SUB).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END })
                    setOnClickListener { edit(s) }
                })
            }
            col.addView(c)
        }
        scroll(col)
    }

    private fun edit(existing: Snippet?) = with(a) {
        val trig = field("Trigger phrase you'll say", existing?.trigger ?: "")
        val body = field("Text to insert", existing?.text ?: "", multiLine = true)
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(trig)
            addView(body, lp().margins(this@with, t = 12))
        }
        sheet(
            if (existing == null) "New snippet" else "Edit snippet", form,
            positive = "Save",
            neutral = if (existing != null) "Delete" else null,
            onNeutral = {
                if (existing != null) {
                    prefs.snippets = prefs.snippets - existing
                    render()
                }
            },
        ) {
            val t = trig.text.toString().trim()
            val x = body.text.toString()
            if (t.isNotEmpty() && x.isNotBlank()) {
                val rest = prefs.snippets.filter { it != existing && !it.trigger.equals(t, true) }
                prefs.snippets = rest + Snippet(t, x)
                render()
            }
        }
        Unit
    }
}

// ------------------------------------------------------------------ Style

class StyleScreen(private val a: MainActivity) {
    companion object {
        var selected = AppCategory.MESSAGING
    }

    fun build(): View = with(a) {
        val col = column()
        intro(col, "Style", "How the cleanup model writes what you say, per kind of app. Tokalot checks which app you're in when you tap the mic.")

        if (!prefs.cleanupReady) {
            col.addView(card(18).apply {
                addView(text("AI cleanup isn't active, so styles won't apply yet. Pick a cleanup model and add its key in Settings.", 15f, C.WARN))
                setOnClickListener { openSettings(SettingsScreen.SPEECH) }
            }, lp().margins(this, b = 16))
        }

        col.addView(card().apply {
            addView(switchRow("Polish my wording", "Off: your own words are kept, with fillers removed and punctuation and formatting fixed. On: the AI may also tighten and clarify what you said, and for a few seconds after each dictation you can tap My wording to put your own words back.", prefs.polish) {
                prefs.polish = it
            })
            addView(divider())
            addView(switchRow("Fit into the sentence", "Dictating into the middle of a sentence: no capital at the start, and no period when the sentence carries on. Tokalot only looks at the characters right next to the cursor; the AI is just told \"mid-sentence\", never your text.", prefs.fitSentence) {
                prefs.fitSentence = it
            })
        }, lp().margins(this, b = 16))

        // Category tabs
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        AppCategory.values().forEach { c ->
            val on = c == selected
            tabs.addView(text(c.label, 15f, if (on) C.CARD else C.TEXT).apply {
                setPadding(dp(16), dp(9), dp(16), dp(9))
                background = if (on) rounded(C.TEXT, 100) else rounded(C.CARD, 100, C.PILL, 1)
                setOnClickListener { selected = c; render() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).margins(this, r = 8))
        }
        col.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs)
        }, lp().margins(this, b = 12))
        col.addView(text(selected.blurb, 14f, C.SUB), lp().margins(this, b = 12, l = 4))

        val c = card()
        Style.values().forEachIndexed { i, st ->
            if (i > 0) c.addView(divider())
            c.addView(choiceRow(st.label, st.example, prefs.styleFor(selected) == st) {
                prefs.setStyleFor(selected, st); render()
            })
        }
        col.addView(c)
        if (selected == AppCategory.AI_CODE) {
            col.addView(text("In these apps Tokalot also keeps technical terms exact and puts file names, commands and code names in `backticks`.", 13f, C.SUB), lp().margins(this, t = 8, l = 4))
        }
        if (selected == AppCategory.EMAIL) {
            col.addView(text("In email apps a spoken greeting and sign-off go on their own lines, with blank lines between paragraphs.", 13f, C.SUB), lp().margins(this, t = 8, l = 4))
        }

        // Apps you've dictated into, with their category (tap to move)
        val apps = History.all(this).map { it.app }.filter { it.isNotEmpty() }.distinct().take(30)
        if (apps.isNotEmpty()) {
            col.addView(heading("Your apps", 26f), lp().margins(this, t = 28, b = 4))
            col.addView(text("Tap an app to change which style it uses.", 14f, C.SUB), lp().margins(this, b = 12))
            val ac = card()
            apps.forEachIndexed { i, pkg ->
                if (i > 0) ac.addView(divider())
                val cat = AppContext.categorize(pkg, prefs)
                ac.addView(row(
                    text(AppContext.label(this, pkg), 17f).apply {
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    },
                    text(cat.label, 15f, C.SUB)
                ).apply {
                    setPadding(dp(20), dp(15), dp(20), dp(15))
                    setOnClickListener {
                        val cats = AppCategory.values()
                        choose(AppContext.label(this@with, pkg), cats.map { it.label }, cats.indexOf(cat)) { which ->
                            prefs.setAppOverride(pkg, cats[which]); render()
                        }
                    }
                })
            }
            col.addView(ac)
        }

        col.addView(heading("Your instructions", 26f), lp().margins(this, t = 28, b = 4))
        col.addView(text("Applies everywhere. E.g. \"Use US spelling\", \"Write numbers as digits\", \"Never use exclamation points\".", 14f, C.SUB), lp().margins(this, b = 12))
        val custom = field("Optional", prefs.customInstructions, multiLine = true)
        custom.onChange { prefs.customInstructions = it }
        col.addView(custom)
        scroll(col)
    }
}

// ------------------------------------------------------------------ Notes (beta)

class NotesScreen(private val a: MainActivity) {
    fun build(): View = with(a) {
        val col = column()
        intro(col, "Notes", "One note per dictation, saved here and copied to the clipboard. Start one from the Voice note tile in Quick Settings, or the accessibility shortcut if you set it to notes. You can also share a voice message or text to Tokalot from WhatsApp or any app.")
        // The beta asks whether it's worth keeping: a short public form on GitHub, nothing sent from here.
        // Answering or closing it hides it for good.
        if (!prefs.notesFeedbackDone) col.addView(card(18).apply {
            addView(row(
                text("Voice notes are a beta. Worth keeping?", 16f, bold = true).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                },
                iconButton(R.drawable.ic_close, "Close", 20, C.SUB) { prefs.notesFeedbackDone = true; render() },
            ).apply { gravity = Gravity.CENTER_VERTICAL })
            addView(text("Opens a short form on GitHub (a free account is needed). It's public, so your notes aren't in it.", 14f, C.SUB), lp().margins(this@with, t = 2, b = 6))
            fun open(vote: String) {
                prefs.notesFeedbackDone = true
                render()
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Notes.feedbackUrl(this@with, vote))))
            }
            addView(row(pill("Useful", filled = true) { open("useful") }, spacer(wDp = 8), pill("Not for me") { open("not for me") }))
        }, lp().margins(this, b = 16))
        val all = Notes.all(this)
        NoteImport.prune(this, all.map { it.id }.toSet())
        val notes = all.filter { it.id != pendingDelete } // deleted a moment ago, while Undo shows
        // Shared voice notes still being transcribed, or that failed, come first.
        val waiting = NoteImport.waiting(this)
        if (waiting.isNotEmpty()) {
            val c = card()
            waiting.forEachIndexed { i, w ->
                if (i > 0) c.addView(divider())
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(14), dp(12), dp(10))
                }
                val going = NoteImport.isRunning(w.id)
                val length = if (w.duration > 0) " · ${NoteMedia.clock(w.duration)}" else ""
                box.addView(text("Shared voice note$length", 16f, bold = true))
                box.addView(text(when {
                    going -> "Transcribing…"
                    w.status == "failed" -> "Couldn't transcribe it: ${w.error}"
                    else -> "Stopped before it finished."
                }, 14f, C.SUB), lp().margins(this, t = 2))
                if (!going) box.addView(row(
                    weightSpacer(),
                    pill("Remove") { confirm("Remove this voice note? Its recording is deleted.", "Remove") { NoteImport.deleteMedia(this, w.id); render() } },
                    spacer(wDp = 8),
                    pill("Try again", filled = true) { NoteImport.transcribe(this, w.id) },
                ), lp().margins(this, t = 6))
                c.addView(box)
            }
            col.addView(c, lp().margins(this, b = 8))
        }
        if (notes.isEmpty() && waiting.isEmpty()) {
            col.addView(card(22).apply {
                addView(text("No notes yet. Swipe down twice from the top of the screen, tap Tokalot Voice note and talk. Tap the button when you're done. Or share a voice message from WhatsApp to Tokalot.", 16f, C.SUB))
            })
            return scroll(col)
        }
        // A plain list grouped by day, newest first: tap a note to open it (its player, summary, timeline, copy and delete).
        notes.groupBy { dayLabel(it.time) }.forEach { (label, group) ->
            col.addView(text(label.uppercase(java.util.Locale.US), 12f, C.SUB, bold = true).apply { letterSpacing = 0.08f },
                lp().margins(this, t = 12, b = 8, l = 4))
            val c = card()
            group.forEachIndexed { i, n ->
                if (i > 0) c.addView(divider())
                val media = NoteImport.info(this, n.id)
                val voice = media?.audio != null
                val time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(n.time))
                val meta = if (voice) "$time · voice note${if (media!!.duration > 0) " · " + NoteMedia.clock(media.duration) else ""}${media.source?.let { " · from $it" } ?: ""}" else time
                val preview = n.text.trim().replace(Regex("\\s+"), " ")
                // A titled note leads with its title, then its first line.
                val title = media?.title
                c.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(14), dp(16), dp(14))
                    minimumHeight = dp(TOUCH_DP)
                    background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(C.RIPPLE), null,
                        android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE))
                    if (title != null) {
                        addView(text(title, 16f, bold = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                        addView(text(n.text.trim().lineSequence().first().trim(), 15f, C.SUB).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END },
                            lp().margins(this@with, t = 2))
                    } else addView(text(preview, 16f).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
                    addView(row(
                        if (voice) icon(R.drawable.ic_play, 14, C.SUB).apply { setPadding(0, 0, dp(4), 0) } else spacer(wDp = 0),
                        text(meta, 13f, C.SUB),
                    ).apply { gravity = Gravity.CENTER_VERTICAL }, lp().margins(this@with, t = 4))
                    contentDescription = "${title?.let { "$it. " } ?: ""}$meta. $preview"
                    setOnClickListener { openNote(n.id) }
                })
            }
            col.addView(c)
        }
        scroll(col)
    }
}

// ------------------------------------------------------------------ Word stats

/** Opened by tapping the stats card on Home: words per month since the start, and a few highlights. */
class StatsScreen(private val a: MainActivity) {
    fun build(): View = with(a) {
        val b = WordStats.build(this)
        val col = column()
        val where = if (b.devices.size > 1) " on all ${b.devices.size} of your devices" else ""
        intro(col, "Your words", if (b.months.isEmpty()) "Nothing counted yet. Dictate something and it shows up here." else "Since ${b.since}$where.")

        val all = card(20)
        all.addView(text("ALL TIME", 12f, C.SUB).apply { letterSpacing = 0.08f })
        val cells = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        fun stat(value: String, label: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(heading(value, 30f))
            addView(text(label, 13f, C.SUB))
        }
        cells.addView(stat(MainActivity.compact(b.words.toInt()), "words"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cells.addView(stat(MainActivity.compact(b.dictations.toInt()), "dictations"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        cells.addView(stat(if (b.dictations > 0) (b.words / b.dictations).toString() else "–", "words each"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        all.addView(cells)
        col.addView(all)

        if (b.months.isNotEmpty()) {
            section(col, "Words per month")
            val max = maxOf(1L, b.months.maxOf { it.words })
            // The month in progress says so, with where it's heading at the pace so far.
            val today = java.util.Calendar.getInstance()
            val nowKey = java.text.SimpleDateFormat("yyyy-MM", java.util.Locale.US).format(today.time)
            val day = today.get(java.util.Calendar.DAY_OF_MONTH)
            val days = today.getActualMaximum(java.util.Calendar.DAY_OF_MONTH)
            col.addView(list(b.months.reversed().map {
                if (it.key != nowKey) return@map bar(it.short, it.words, max, "${it.dictations} dictations")
                val track = WordStats.onTrack(it.words, day, days)?.let { w -> " · on track for ${MainActivity.compact(w.toInt())}" } ?: ""
                bar(it.short, it.words, max, "So far, day $day of $days$track")
            }))
        }

        section(col, "Highlights")
        val facts = mutableListOf<View>()
        fun fact(label: String, value: String, sub: String? = null) {
            if (value.isEmpty()) return
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(label, 16f))
                if (sub != null) addView(text(sub, 13f, C.SUB))
            }
            facts += row(
                texts.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) },
                text(value, 16f, bold = true).apply { setPadding(dp(12), 0, 0, 0) },
            ).apply { setPadding(dp(20), dp(14), dp(20), dp(14)) }
        }
        fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"
        b.best?.let { fact("Best month", "${MainActivity.compact(it.words.toInt())} words", it.label) }
        if (b.months.size > 1) fact("Average month", "${MainActivity.compact(b.average.toInt())} words", "Not counting this month yet")
        if (b.words > 0) fact("Typing it would have taken", WordStats.typingTime(b.words), "At the average phone typing speed, ${WordStats.TYPING_WPM} words a minute")
        fact("That's about", WordStats.equivalent(b.words))
        b.pace?.let { fact("Speaking pace", "${Math.round(it)} words a minute") }
        if (b.longest > 0) fact("Longest dictation", plural(b.longest, "word"),
            java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.US).format(java.util.Date(b.longestAt)))
        if (b.days.days > 0) fact("Days you dictated", b.days.days.toString(),
            "Longest streak ${plural(b.days.best, "day")}" + if (b.days.current > 1) " · ${b.days.current} days running now" else "")
        b.busiestDay?.let { fact("Busiest day of the week", it) }
        if (b.here.fillers + b.here.corrections > 0)
            fact("Cleaned up", plural(b.here.fillers, "filler word"), "and ${plural(b.here.corrections, "self-correction")}")
        if (b.here.offline > 0) fact("Offline dictations", b.here.offline.toString())
        if (facts.isEmpty()) facts += text("Highlights show up once you've dictated a few times.", 15f, C.SUB).apply { setPadding(dp(20), dp(14), dp(20), dp(14)) }
        col.addView(list(facts))

        if (b.apps.isNotEmpty()) {
            section(col, "Where you talk the most")
            val max = maxOf(1L, b.apps.first().words)
            col.addView(list(b.apps.map { bar(it.label, it.words, max) }))
        }

        if (b.devices.size > 1) {
            section(col, "By device")
            val max = maxOf(1L, b.devices.maxOf { it.words })
            col.addView(list(b.devices.map { bar(it.name.ifBlank { "Unnamed device" }, it.words, max, "${it.dictations} dictations") }))
        }

        col.addView(text(
            (if (b.devices.size > 1) "Words per month and clean-ups count every device that syncs. " else "") +
                (if (prefs.syncUri != null && prefs.syncHistory) "Pace, streaks and apps come from the dictation history, synced from your other devices too."
                else "Pace, streaks and apps come from this phone's history."), 13f, C.SUB,
        ), lp().margins(this, t = 8, l = 4))
        scroll(col)
    }

    private fun section(col: LinearLayout, title: String) = with(a) {
        col.addView(text(title.uppercase(java.util.Locale.US), 12f, C.SUB, bold = true).apply { letterSpacing = 0.08f },
            lp().margins(this, t = 28, b = 8, l = 4))
    }

    /** A card of rows with thin lines between them. */
    private fun list(rows: List<View>): View = with(a) {
        val c = card()
        rows.forEachIndexed { i, v -> if (i > 0) c.addView(divider()); c.addView(v) }
        c
    }

    /** A label and its number, with a bar under them as long as [value] is next to [max]. */
    private fun bar(label: String, value: Long, max: Long, sub: String? = null): View = with(a) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(14))
        }
        box.addView(row(
            text(label, 16f).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) },
            text(MainActivity.compact(value.toInt()), 16f, bold = true).apply { setPadding(dp(12), 0, 0, 0) },
        ))
        val track = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(C.FIELD, 4)
        }
        if (value > 0) track.addView(View(this).apply { background = rounded(C.TEXT, 4); minimumWidth = dp(8) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, value.toFloat()))
        track.addView(View(this), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, (max - value).coerceAtLeast(0).toFloat()))
        box.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(8)).margins(this, t = 8))
        if (sub != null) box.addView(text(sub, 13f, C.SUB), lp().margins(this, t = 6))
        box
    }
}

// ------------------------------------------------------------------ Settings

class SettingsScreen(private val a: MainActivity) {

    companion object {
        const val SETUP = "Setup"
        const val KEYBOARD = "Keyboard"
        const val SPEECH = "Speech & cleanup"
        const val DATA = "Your data"
        const val RECORDING = "Recording & look"
        private const val SECTION = "section:"

        /** Settings categories, in order, each with its sections in the order shown when it's open. */
        val CATEGORIES = listOf(
            SETUP to listOf("Setup"),
            KEYBOARD to listOf("Keyboard"),
            SPEECH to listOf("Speech to text", "On-device model", "AI cleanup", "API keys", "Speed"),
            RECORDING to listOf("Recording", "Without a text box", "Appearance"),
            DATA to listOf("Recordings", "Sync", "Backup", "Usage"),
            "About" to listOf("About"),
        )

        /** The on-device model being downloaded (one at a time), and how far along it is. */
        @Volatile var downloading: LocalModel? = null
        @Volatile var downloadPct: Int? = null
        @Volatile var downloadError: String? = null
        // Progress updates only change this label; redrawing the whole screen each tick made it flash.
        var modelStatusView: TextView? = null
        var modelRowStatus: TextView? = null // the line under the model being downloaded, in its own section

        // Same for sync: a finished sync only moves this line, so it never interrupts typing in a field below.
        var syncStatusView: TextView? = null
        var syncShownBroken = false

        fun showSyncStatus(prefs: Prefs) {
            val error = prefs.syncError
            syncStatusView?.apply {
                text = when {
                    error != null -> error
                    Sync.running -> "Syncing…"
                    else -> Sync.ago(prefs.syncLast)
                }
                setTextColor(if (error != null) C.WARN else C.SUB)
            }
        }

        val SYNC_INFO = listOf(
            "Tokalot keeps your dictionary, snippets, styles, instructions, voice notes, word stats and settings in one small file. Put that file in a folder you already sync, such as Google Drive, OneDrive, Dropbox or Syncthing, and point each of your devices at it. Each device reads the file and adds its own changes.",
            "A few settings stay on each device because they depend on it: the on-device model, how long recordings are kept, the volume-key shortcut and where things sit on the screen.",
            "It's private. There is no Tokalot account and no Tokalot server. The file only goes where your own sync service takes it. Recordings are never put in it, and your dictation history only if you turn on Include dictation history (the newest ${SyncMerge.HISTORY_MAX} dictations, as text).",
            "API keys are left out unless you turn on Include API keys. The file isn't encrypted, so only do that if the folder is private to you. You can stop syncing at any time; your settings stay on this device.",
        ).joinToString("\n\n")
    }

    fun build(): View = with(a) {
        val col = column()
        col.addView(heading("Settings"), lp().margins(this, t = 8, b = 16))

        // --- Setup
        section(col, "Setup")
        val setup = card()
        fun setupRow(title: String, done: Boolean, doneText: String, action: String, onClick: () -> Unit): TextView {
            if (setup.childCount > 0) setup.addView(divider())
            val status = text(if (done) doneText else doneText.takeIf { it.startsWith("Failed") } ?: "Not done", 14f, if (done) C.GOOD else C.WARN)
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 17f))
                addView(status)
            }
            setup.addView(row(
                texts.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) },
                if (done) spacer() else pill(action) { onClick() }
            ).apply { setPadding(dp(20), dp(14), dp(16), dp(14)) })
            return status
        }
        setupRow("Microphone", micGranted(), "Allowed", "Allow") {
            askPermission(Manifest.permission.RECORD_AUDIO, 1)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val ok = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            setupRow("Notifications (recording indicator)", ok, "Allowed", "Allow") {
                askPermission(Manifest.permission.POST_NOTIFICATIONS, 2)
            }
        }
        setupRow("Accessibility switch", serviceEnabled(), "On", "Set up") { openAccessibilitySetup() }
        // Some phones (Samsung) switch the service off after every update; a one-time permission lets Tokalot fix that itself.
        setup.addView(divider())
        val keep = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(16), dp(14))
            addView(text("Stay on after updates", 17f))
            if (ServiceGuard.canRepair(this@with)) {
                addView(text("On. If an update switches the mic button off, Tokalot turns it back on.", 14f, C.GOOD))
            } else {
                addView(text("Some phones, such as Samsung, switch Tokalot off after each update, and you have to turn it off and on again. To have Tokalot do that itself, connect the phone to a computer with USB debugging on and run this once (it lasts through updates):", 14f, C.SUB))
                addView(text(ServiceGuard.GRANT_COMMAND, 13f).apply {
                    typeface = android.graphics.Typeface.MONOSPACE
                    setPadding(0, dp(8), 0, dp(4))
                    setTextIsSelectable(true)
                })
                addView(row(pill("Copy command") { copy(ServiceGuard.GRANT_COMMAND) }), lp().margins(this@with, t = 6))
            }
        }
        setup.addView(keep)
        val pct = downloadPct
        val haveModel = ModelManager.active(this)
        val modelStatus = when {
            haveModel != null -> "${haveModel.label} downloaded"
            pct != null -> "Downloading $pct%"
            downloadError != null -> "Failed: $downloadError"
            else -> ""
        }
        modelStatusView = setupRow("Offline backup model", haveModel != null || pct != null, modelStatus, "Download") {
            startDownload(prefs.localModel)
        }
        col.addView(setup)
        col.addView(text("If the accessibility switch is greyed out: phone Settings › Apps › Tokalot › ⋮ › Allow restricted settings. Also set Tokalot's battery usage to Unrestricted.", 13f, C.SUB), lp().margins(this, t = 8))

        // --- Keyboard: Tokalot as the phone's keyboard (see TokalotKeyboard)
        section(col, "Keyboard")
        val kbSetup = card()
        fun kbRow(title: String, done: Boolean, doneText: String, action: String, onClick: () -> Unit) {
            if (kbSetup.childCount > 0) kbSetup.addView(divider())
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 17f))
                addView(text(if (done) doneText else "Not yet", 14f, if (done) C.GOOD else C.SUB))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            kbSetup.addView(row(texts, if (done) spacer() else pill(action) { onClick() }).apply {
                setPadding(dp(20), dp(14), dp(16), dp(14))
            })
        }
        val kbOn = TokalotKeyboard.isEnabled(this)
        kbRow("Turn on Tokalot Keyboard", kbOn, "On", "Turn on") {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        kbRow("Use it as your keyboard", TokalotKeyboard.isCurrent(this), "In use", "Switch") {
            if (!TokalotKeyboard.isEnabled(this)) startActivity(android.content.Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS))
            else getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showInputMethodPicker()
        }
        // Optional extras for the bar, each needing one permission: a screenshot just taken, ready to paste,
        // and a sign-in code that just came in a text.
        val photos = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
        kbRow("Paste new screenshots", checkSelfPermission(photos) == android.content.pm.PackageManager.PERMISSION_GRANTED, "Allowed", "Allow") {
            askPermission(photos, 3)
        }
        kbRow("Codes from texts", CodeListener.allowed(this), "Allowed", "Allow") {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        col.addView(kbSetup)
        col.addView(text("A keyboard in the style of Gboard, with autocorrect and swipe typing. The mic on its bar dictates like the floating button, and ✨ fixes what you typed with your AI cleanup (the selected text, or the whole box). The floating button hides while it's up. Hold the space bar to switch keyboards, or the comma to come back here.", 13f, C.SUB), lp().margins(this, t = 8))
        val kb = card()
        fun kbSwitch(title: String, note: String, on: Boolean, set: (Boolean) -> Unit) {
            if (kb.childCount > 0) kb.addView(divider())
            kb.addView(switchRow(title, note, on, set))
        }
        kbSwitch("Number row", "A row of digits above the letters, always showing.", prefs.kbNumberRow) { prefs.kbNumberRow = it }
        kbSwitch("Autocorrect", "Fixes a mistyped word when you type a space or punctuation. Backspace straight after puts back what you typed.", prefs.kbAutocorrect) { prefs.kbAutocorrect = it }
        kbSwitch("Swipe typing", "Slide your finger from letter to letter instead of tapping.", prefs.kbSwipe) { prefs.kbSwipe = it }
        kbSwitch("Capitalize sentences", "A capital letter at the start of each sentence, where the app asks for one.", prefs.kbAutoCaps) { prefs.kbAutoCaps = it }
        kbSwitch("Double space for a period", "Two taps on the space bar type a period and a space.", prefs.kbDoubleSpace) { prefs.kbDoubleSpace = it }
        kbSwitch("Learn from my typing", "Remembers the words you use and what follows what, on this phone only, for better suggestions. A word the keyboard doesn't know goes into your dictionary once you've kept it twice, so it syncs.", prefs.kbLearn) { prefs.kbLearn = it }
        kbSwitch("Symbols on letters", "Each letter shows the symbol a long press gives.", prefs.kbSymbolHints) { prefs.kbSymbolHints = it }
        kbSwitch("Show key on press", "The key pops up bigger above your finger.", prefs.kbPopup) { prefs.kbPopup = it }
        kbSwitch("Vibrate on key press", "", prefs.kbVibrate) { prefs.kbVibrate = it }
        kbSwitch("Sound on key press", "Follows your phone's touch sound volume.", prefs.kbSound) { prefs.kbSound = it }
        kbSwitch("Clipboard", "Keeps what you copy for an hour (pin anything to keep it), on this phone only. Open it with the clipboard button on the bar. Nothing from password boxes, and nothing an app marks as private.", prefs.kbClipboard) {
            prefs.kbClipboard = it
            if (!it) TokalotKeyboard.clearClips(this)
        }
        kbSwitch("Offer new screenshots", "A screenshot you just took shows on the bar as a small picture; tap it to paste it into apps that take pictures, like texting apps. Needs Paste new screenshots above.", prefs.kbScreenshots) { prefs.kbScreenshots = it }
        kbSwitch("Offer codes from texts", "A sign-in code that just came in a text shows on the bar for a few minutes, ready to paste. Needs Codes from texts above. Tokalot only looks for the code, keeps nothing and sends nothing.", prefs.kbCodes) { prefs.kbCodes = it }
        kbSwitch("Suggest what I copied", "Something you just copied shows on the bar, ready to paste. A link, email address or phone number in it gets its own button.", prefs.kbClipSuggest) { prefs.kbClipSuggest = it }
        col.addView(kb)

        col.addView(text("On the keyboard itself, the four-squares button at the left of its top bar opens its menu: move, remove or add the top bar's buttons, change its height, and switch its look.", 13f, C.SUB), lp().margins(this, t = 8))

        // How the keyboard looks: light or dark, the key style, and its colour.
        col.addView(text("Keyboard look", 14f, C.SUB), lp().margins(this, t = 16, b = 8, l = 4))
        val look = card()
        listOf("auto" to "Match phone", "light" to "Light", "dark" to "Dark").forEachIndexed { i, (id, label) ->
            if (i > 0) look.addView(divider())
            look.addView(choiceRow(label, if (id == "auto") "Light by day, dark when your phone is in dark mode." else "", prefs.kbTheme == id) {
                if (prefs.kbTheme != id) { prefs.kbTheme = id; render() }
            })
        }
        col.addView(look)
        val styleCard = card()
        listOf(
            KeyboardTheme.CLASSIC to ("Classic" to "Grey keys, as on Gboard."),
            KeyboardTheme.TINTED to ("Tinted" to "Keys and background tinted with the keyboard colour."),
            KeyboardTheme.BORDERLESS to ("Borderless" to "Just the letters, no key shapes."),
            KeyboardTheme.CONTRAST to ("High contrast" to "Pure black or white, easiest to read in sunlight."),
        ).forEachIndexed { i, (id, t) ->
            if (i > 0) styleCard.addView(divider())
            styleCard.addView(choiceRow(t.first, t.second, prefs.kbStyle == id) {
                if (prefs.kbStyle != id) { prefs.kbStyle = id; render() }
            })
        }
        col.addView(styleCard, lp().margins(this, t = 8))
        col.addView(text("Keyboard colour (enter key, mic, swipe trail)", 14f, C.SUB), lp().margins(this, t = 16, b = 8, l = 4))
        val kbSwatches = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        (listOf("Same as the app" to 0) + Accents.all).forEach { (name, color) ->
            val on = prefs.kbAccent == color
            val disc = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    // "Same as the app": the app's colour as a ring.
                    setColor(if (color == 0) android.graphics.Color.TRANSPARENT else color)
                    setStroke(dp(if (on) 4 else if (color == 0) 3 else 1), if (on) C.TEXT else if (color == 0) prefs.accent else C.PILL)
                }
            }
            kbSwatches.addView(FrameLayout(this).apply {
                addView(disc, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
                contentDescription = if (on) "$name, selected" else name
                setOnClickListener { prefs.kbAccent = color; render() }
            }, LinearLayout.LayoutParams(dp(TOUCH_DP), dp(TOUCH_DP)))
        }
        col.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(kbSwatches)
        }, lp())
        col.addView(row(
            text("Forget the words and phrases the keyboard learned from your typing (your dictionary stays).", 14f, C.SUB).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
            pill("Clear") {
                TokalotKeyboard.clearHistory(this)
                android.widget.Toast.makeText(this, "Typing history cleared", android.widget.Toast.LENGTH_SHORT).show()
            }
        ), lp().margins(this, t = 8, l = 4))

        // --- Appearance
        section(col, "Appearance")
        val ap = card()
        listOf("system" to "Match phone", "light" to "Light", "dark" to "Dark").forEachIndexed { i, (id, label) ->
            if (i > 0) ap.addView(divider())
            ap.addView(choiceRow(label, "", prefs.theme == id) {
                if (prefs.theme != id) { prefs.theme = id; applyTheme() } // in place: stays on Settings
            })
        }
        col.addView(ap)
        col.addView(text("Floating button color while listening", 14f, C.SUB), lp().margins(this, t = 16, b = 8, l = 4))
        val swatches = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Accents.all.forEach { (name, color) ->
            val on = prefs.accent == color
            // The disc stays 34dp; the frame around it makes the tap target a full 48dp.
            val disc = View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(color)
                    setStroke(dp(if (on) 4 else 1), if (on) C.TEXT else C.PILL)
                }
            }
            swatches.addView(FrameLayout(this).apply {
                addView(disc, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER))
                contentDescription = if (on) "$name, selected" else name
                setOnClickListener { prefs.accent = color; render() }
            }, LinearLayout.LayoutParams(dp(TOUCH_DP), dp(TOUCH_DP)))
        }
        col.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(swatches)
        }, lp())

        // --- Recording
        section(col, "Recording")
        val rc = card()
        rc.addView(switchRow("Hold to talk", "Hold the button while you speak, release to finish, slide off to cancel. Off: tap to start and tap to stop.", prefs.holdToTalk) {
            prefs.holdToTalk = it
        })
        rc.addView(divider())
        rc.addView(switchRow("Auto-stop after 30 s of silence", "Tap mode only. Long pauses to think are fine.", prefs.autoStop) {
            prefs.autoStop = it
        })
        rc.addView(divider())
        rc.addView(switchRow("Transcribe while I talk", "In a long dictation, what you've said so far is sent to the speech service each time you pause, so the wait at the end stays short. Nothing is typed until you finish. Needs a cloud speech service and AI cleanup.", prefs.liveStt) {
            prefs.liveStt = it
        })
        rc.addView(divider())
        rc.addView(switchRow("Haptics", "A small buzz when recording starts, stops, finishes or fails.", prefs.haptics) {
            prefs.haptics = it
            if (it) Haptics.play(this, Haptics.Kind.DONE)
        })
        rc.addView(divider())
        rc.addView(switchRow("Sounds", "A short tone when recording starts, stops, finishes or fails. Follows your phone's system sound volume.", prefs.sounds) {
            prefs.sounds = it
            if (it) Sounds.play(this, Haptics.Kind.DONE)
            render()
        })
        if (prefs.sounds) Sounds.styles.forEach { st ->
            rc.addView(divider())
            rc.addView(choiceRow(st.label, st.note, prefs.soundSet == st.id) {
                Sounds.play(this, Haptics.Kind.START, st.id)
                if (prefs.soundSet != st.id) { prefs.soundSet = st.id; render() }
            })
        }
        rc.addView(divider())
        rc.addView(switchRow("Detect language automatically", "Off keeps it English-only, which is most accurate for English. The offline backup is English-only either way.", prefs.autoLanguage) {
            prefs.autoLanguage = it
        })
        col.addView(rc)
        col.addView(row(
            text("The floating button stays where you last dragged it.", 14f, C.SUB).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
            pill("Reset position") {
                getSharedPreferences("overlay", android.content.Context.MODE_PRIVATE).edit().clear().apply()
                android.widget.Toast.makeText(this, "Back at the right edge, just above the keyboard", android.widget.Toast.LENGTH_SHORT).show()
            }
        ), lp().margins(this, t = 8, l = 4))

        // --- Without a text box: the Quick Settings tiles, voice notes (beta) and the accessibility shortcut
        section(col, "Without a text box")
        val hand = card()
        hand.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(16), dp(10))
            addView(text("Dictate tile", 17f))
            addView(text("Talk while you read or scroll, with no text box open. The text goes into the selected text box if there is one, and onto the clipboard if not. Swipe down twice from the top, tap the pencil, and drag Tokalot Dictate into your tiles.", 14f, C.SUB))
            if (Build.VERSION.SDK_INT >= 33) addView(row(pill("Add the tile") {
                StartTile.requestAdd(this@with, DictateTile::class.java, "Dictate", R.drawable.ic_mic)
            }), lp().margins(this@with, t = 6))
        })
        hand.addView(divider())
        // With no keyboard showing, these apps get the clipboard rather than having the text typed in.
        hand.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(14), dp(16), dp(10))
            addView(text("Clipboard only", 17f))
            addView(text("With no keyboard showing, Tokalot still types into the selected text box, except in these apps, where the text is copied instead. Launchers start out here: some keep a hidden search box selected on the home screen.", 14f, C.SUB))
            val listed = prefs.clipboardApps
            listed.map { it to AppContext.label(this@with, it) }.sortedBy { it.second.lowercase() }.forEach { (pkg, name) ->
                addView(row(
                    text(name, 15f).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) },
                    iconButton(R.drawable.ic_close, "Remove $name", 20, C.SUB) { prefs.clipboardApps = prefs.clipboardApps - pkg; render() },
                ).apply { minimumHeight = dp(44) }, lp().margins(this@with, t = 4))
            }
            addView(row(pill("Add an app") {
                val apps = AppContext.launchable(this@with).filter { it.first !in prefs.clipboardApps }
                lateinit var d: android.app.Dialog
                val list = LinearLayout(this@with).apply { orientation = LinearLayout.VERTICAL }
                apps.forEach { (pkg, name) ->
                    list.addView(text(name, 16f).apply {
                        setPadding(dp(4), dp(12), dp(4), dp(12))
                        setOnClickListener { prefs.clipboardApps = prefs.clipboardApps + pkg; d.dismiss(); render() }
                    })
                }
                d = sheet("Clipboard only", list)
            }), lp().margins(this@with, t = 6))
        })
        hand.addView(divider())
        hand.addView(switchRow("Voice notes (beta)", "Adds a Notes page and a Voice note tile. Each dictation started from it is saved as its own note and copied to the clipboard. Voice messages and text shared to Tokalot from WhatsApp or any app become notes too, which you can play, summarize and index. Notes are in backups, and their text is in sync when it's set up.", prefs.notesBeta) { on ->
            prefs.notesBeta = on
            StartTile.setNoteTile(this, on)
            if (!on && prefs.shortcutAction == "note") prefs.shortcutAction = "off"
            if (on) StartTile.requestAdd(this, NoteTile::class.java, "Voice note", R.drawable.ic_note)
            render()
        })
        if (prefs.notesBeta) {
            // Start any dictation with one of these and it's saved as a note instead of typed.
            hand.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), 0, dp(16), dp(16))
                addView(text("Start any dictation with one of these and it's saved as a note instead of typed. Only the very start counts, and a text box gets a Type it instead button in case you meant it as words.", 14f, C.SUB))
                addView(field(TextTools.DEFAULT_NOTE_PHRASES, prefs.notePhrases).apply {
                    onChange { prefs.notePhrases = it.ifBlank { TextTools.DEFAULT_NOTE_PHRASES } }
                }, lp().margins(this@with, t = 8))
            })
        }
        col.addView(hand)

        col.addView(text("Android's accessibility shortcut (hold both volume keys) does", 15f, C.SUB), lp().margins(this, t = 16, b = 6, l = 4))
        // The shortcut can only reach the app on Android 11 and newer; before that it switches Tokalot off and on.
        if (Build.VERSION.SDK_INT < 30) {
            col.addView(card(18).apply {
                addView(text("Needs Android 11 or newer. On this phone the shortcut can only switch Tokalot off and on, so use the Dictate tile instead.", 14f, C.SUB))
            })
        }
        val actions = if (Build.VERSION.SDK_INT < 30) emptyList() else buildList {
            add("off" to ("Nothing" to "Holding the keys shows a reminder to pick an action here."))
            add("dictate" to ("Dictate" to "Same as the Dictate tile."))
            if (prefs.notesBeta) add("note" to ("New voice note" to "Same as the Voice note tile."))
        }
        val sc = card()
        actions.forEachIndexed { i, (id, label) ->
            if (i > 0) sc.addView(divider())
            sc.addView(choiceRow(label.first, label.second, prefs.shortcutAction == id) {
                prefs.shortcutAction = id
                render()
            })
        }
        if (actions.isNotEmpty()) col.addView(sc)
        if (Build.VERSION.SDK_INT >= 30 && (prefs.shortcutAction != "off" || shortcutFloats())) {
            // Android's floating accessibility button is Android's own: it sits on every screen and can't follow the
            // keyboard. Some phones (OnePlus and others) put the shortcut there by default; Samsung uses the
            // navigation bar or the volume keys. Dragging it off the screen to remove it can switch Tokalot off as well.
            val tip = if (shortcutFloats())
                "Android's floating accessibility button is on for Tokalot, so it stays on every screen. It's Android's button, not Tokalot's, and it can't come and go with the keyboard. To use just the volume keys, open Tokalot's shortcut settings, untick the accessibility button and leave \"Hold volume keys\" ticked. Don't drag the button off the screen to get rid of it: on OnePlus, Oppo and Realme phones that switches Tokalot off too."
            else
                "Then turn the shortcut on in Android: Settings › Accessibility › Tokalot › Tokalot shortcut, and pick \"Hold volume keys\". It starts the action above; it doesn't switch Tokalot off. The accessibility button choice puts a button on every screen on some phones, not just with the keyboard."
            col.addView(text(tip, 13f, C.SUB), lp().margins(this, t = 8, l = 4))
            col.addView(link("Open Tokalot's shortcut settings") { openShortcutSettings() }, lp().margins(this, l = 4))
        }

        // --- Speed
        section(col, "Speed")
        val sp = card()
        sp.addView(switchRow("Quick mode (beta)", "Skips the AI cleanup when a short dictation (20 words or fewer) has nothing for it to fix: no ums, corrections, repeats or spoken punctuation. The text is tidied on the phone instead, which is instant. Email, snippets, the very casual style and your own instructions always go through the AI.", prefs.quickSkip) {
            prefs.quickSkip = it
        })
        sp.addView(divider())
        val timings = prefs.speedLog
        sp.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(13), dp(18), dp(13))
            addView(text("Last dictations", 16f))
            addView(text(if (timings.isEmpty()) "Nothing yet. Dictate something, then come back." else timings.take(8).joinToString("\n"), 13f, C.SUB))
        })
        col.addView(sp)
        if (timings.isNotEmpty()) {
            col.addView(row(pill("Copy timings") {
                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Tokalot timings", timings.joinToString("\n")))
                android.widget.Toast.makeText(this, "Copied", android.widget.Toast.LENGTH_SHORT).show()
            }), lp().margins(this, t = 8, l = 4))
        }

        // --- Speech to text
        section(col, "Speech to text")
        val stt = card()
        SttChoice.values().forEachIndexed { i, c ->
            if (i > 0) stt.addView(divider())
            val needsKey = c.service != null && prefs.key(c.service).isEmpty()
            stt.addView(choiceRow(c.label, c.note + if (needsKey) " Needs a key." else "", prefs.stt == c) {
                prefs.stt = c; render()
            })
        }
        col.addView(stt)
        if (prefs.stt != SttChoice.LOCAL) {
            col.addView(modelField(prefs.sttModel(prefs.stt)) { prefs.setSttModel(prefs.stt, it) })
        }

        // --- On-device model
        section(col, "On-device model")
        val om = card()
        LocalModel.values().forEachIndexed { i, m ->
            if (i > 0) om.addView(divider())
            val state = when {
                downloading == m -> "Downloading ${downloadPct ?: 0}%"
                ModelManager.isDownloaded(this, m) -> "Downloaded"
                downloading == null && downloadError != null && prefs.localModel == m -> "Download failed: $downloadError"
                else -> "Tap to download"
            }
            val status = text(state, 14f, if (state == "Downloaded") C.GOOD else C.SUB)
            if (downloading == m) modelRowStatus = status
            om.addView(choiceRow(m.label, m.note, prefs.localModel == m) {
                prefs.localModel = m
                if (!ModelManager.isDownloaded(this, m)) startDownload(m) else render()
            }.apply { ((this as LinearLayout).getChildAt(1) as LinearLayout).addView(status) })
        }
        col.addView(om)
        val others = LocalModel.values().filter { it != prefs.localModel && it != downloading && ModelManager.isDownloaded(this, it) }
        if (others.isNotEmpty()) {
            val mb = others.sumOf { it.size } / 1_000_000
            col.addView(row(pill("Remove the others ($mb MB)") {
                confirm("Delete ${others.joinToString(" and ") { it.label }} from this phone? You can download them again any time.", "Delete") {
                    others.forEach { ModelManager.delete(this, it) }
                    render()
                }
            }), lp().margins(this, t = 8, l = 4))
        }
        val ow = card()
        ow.addView(switchRow("Prefer on-device in weak signal", "Skips the cloud when there's no connection, or the speech service was slow to answer while you talked, and gives the cloud only a few seconds before switching to the phone. Needs an on-device model.", prefs.weakSignal) {
            prefs.weakSignal = it
        })
        col.addView(ow, lp().margins(this, t = 12))
        col.addView(text("Any time: while recording, slide the floating button sideways to do that dictation on the phone.", 13f, C.SUB), lp().margins(this, t = 8))

        // --- Cleanup
        section(col, "AI cleanup")
        val cl = card()
        CleanupChoice.values().forEachIndexed { i, c ->
            if (i > 0) cl.addView(divider())
            val needsKey = c.service != null && prefs.key(c.service).isEmpty()
            cl.addView(choiceRow(c.label, c.note + if (needsKey) " Needs a key." else "", prefs.cleanup == c) {
                prefs.cleanup = c; render()
            })
        }
        col.addView(cl)
        if (prefs.cleanup != CleanupChoice.OFF) {
            col.addView(modelField(prefs.cleanupModel(prefs.cleanup)) { prefs.setCleanupModel(prefs.cleanup, it) })
            val result = text("", 15f, C.SUB)
            col.addView(row(pill("Test cleanup") { testCleanup(result) }), lp().margins(this, t = 12))
            col.addView(result, lp().margins(this, t = 8))
        }
        col.addView(text("If your chosen provider fails or hits a limit, Tokalot tries another provider you have a key for, then falls back to offline.", 13f, C.SUB), lp().margins(this, t = 8))

        // --- Keys
        section(col, "API keys")
        val keys = card(18)
        Services.all.forEachIndexed { i, (id, name) ->
            val label = text(name, 16f, bold = true)
            // Tap opens the key page in the browser; hold copies the address.
            val page = Services.keyPages[id]
            val hint = link("Get one at $page", 13f) {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://$page"))) }
                    .onFailure { copy("https://$page") }
            }.apply {
                setOnLongClickListener { copy("https://$page"); true }
            }
            val f = field("Paste key", prefs.key(id), secret = true).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val warn = text("", 13f, C.WARN).apply { visibility = View.GONE }
            val check = { key: String ->
                val problem = Keys.problem(id, Keys.clean(key))
                warn.text = problem ?: ""
                warn.visibility = if (problem == null) View.GONE else View.VISIBLE
            }
            check(f.text.toString())
            f.onChange { prefs.setKey(id, it); check(it) }
            // Some keyboards hide their clipboard suggestion in password fields, and long-press
            // paste is easy to miss, so read the clipboard directly.
            val paste = pill("Paste") {
                val clip = (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
                val pasted = Keys.clean(clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() ?: "")
                if (pasted.isEmpty()) {
                    android.widget.Toast.makeText(this, "Nothing to paste. Copy the key first, then tap Paste.", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    f.setText(pasted)
                    android.widget.Toast.makeText(this, "$name key saved", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            keys.addView(label, lp().margins(this, t = if (i == 0) 0 else 16))
            keys.addView(hint)
            keys.addView(row(f, spacer(wDp = 8), paste), lp().margins(this, t = 6))
            keys.addView(warn, lp().margins(this, t = 4))
        }
        col.addView(keys)
        col.addView(text("Tap a link to open the page where you make a key. Hold it to copy the address.", 13f, C.SUB), lp().margins(this, t = 8))
        col.addView(text(
            if (prefs.syncUri != null && prefs.syncKeys) "Keys are saved on this phone, encrypted, in Tokalot's private storage. Include API keys is on under Sync, so they are also written to your sync file."
            else "Keys are saved only on this phone, encrypted, in Tokalot's private storage. Nothing is synced anywhere. Uninstalling the app deletes them.",
            13f, C.SUB
        ), lp().margins(this, t = 8))

        // --- Recordings
        section(col, "Recordings")
        val rec = card()
        listOf(0 to "Don't save audio", 7 to "Keep 7 days", 30 to "Keep 30 days", Int.MAX_VALUE to "Keep forever")
            .forEachIndexed { i, (days, label) ->
                if (i > 0) rec.addView(divider())
                rec.addView(choiceRow(label, "", prefs.audioKeepDays == days) {
                    val keep = {
                        prefs.audioKeepDays = days
                        Thread { AudioStore.prune(applicationContext, days); runOnUiThread { render() } }.start()
                        render()
                    }
                    if (days < prefs.audioKeepDays && AudioStore.totalBytes(this) > 0) {
                        confirm(
                            if (days == 0) "Delete all saved recordings now? Transcripts stay."
                            else "Delete recordings older than $days days now? Transcripts stay.",
                            "Delete"
                        ) { keep() }
                    } else keep()
                })
            }
        col.addView(rec)
        col.addView(text("A recording that failed or was cancelled is always kept until you transcribe or delete it.", 13f, C.SUB), lp().margins(this, t = 8))
        val mb = AudioStore.totalBytes(this) / 1_048_576.0
        col.addView(row(
            text("Using ${String.format(java.util.Locale.US, "%.1f", mb)} MB", 14f, C.SUB).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
            pill("Delete all") {
                confirm("Delete all saved recordings? Transcripts stay.", "Delete") { AudioStore.deleteAll(this); render() }
            }
        ), lp().margins(this, t = 8))

        // --- Sync (optional)
        section(col, "Sync")
        col.addView(syncCard())

        // --- Backup
        section(col, "Backup")
        val bc = card()
        // The choices live on the activity, so a redraw (or rotating the phone) can't reset them.
        bc.addView(switchRow("Include recordings", "Makes the file much bigger.", backupAudio) { backupAudio = it })
        bc.addView(divider())
        bc.addView(switchRow("Include API keys", "Only if you'll keep the file somewhere private. Anyone with the file could use your keys.", backupKeys) { backupKeys = it })
        col.addView(bc)
        val busy = MainActivity.backupBusy
        if (busy != null) {
            // Runs in the background; the buttons come back when it's done.
            col.addView(text(busy, 15f, C.SUB).apply { minHeight = dp(TOUCH_DP); gravity = Gravity.CENTER_VERTICAL }, lp().margins(this, t = 10, l = 4))
        } else {
            col.addView(row(
                pill("Back up…", filled = true) { startBackup() },
                spacer(wDp = 10),
                pill("Restore…") {
                    confirm(
                        "This replaces your current settings, dictionary, snippets and history with the backup's. API keys on this phone are kept unless the backup includes keys.",
                        "Choose file", title = "Restore from backup?"
                    ) { startRestore() }
                }
            ), lp().margins(this, t = 10))
        }
        col.addView(text("Saves one .zip file wherever you choose: Downloads, Google Drive, etc.", 13f, C.SUB), lp().margins(this, t = 8))

        // --- Usage
        section(col, "Usage")
        val months = Usage.recentMonths(this).ifEmpty { listOf(Usage.month(this)) }
        val u = card()
        months.forEachIndexed { i, m ->
            if (i > 0) u.addView(divider())
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(16), dp(20), dp(16))
            }
            box.addView(row(
                text(m.label, 17f, bold = true).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) },
                text(MainActivity.money(m.total), 17f, bold = true)
            ))
            box.addView(text("${m.dictations} dictations · ${m.words} words · ${m.localCount} offline", 14f, C.SUB))
            for (c in SttChoice.values()) {
                val s = m.sttSeconds[c] ?: 0.0
                if (s > 0) box.addView(text("${c.label}: ${String.format(java.util.Locale.US, "%.1f", s / 60)} min billed · ${MainActivity.money(m.sttCost(c))}", 14f, C.SUB))
            }
            for (c in CleanupChoice.values()) {
                val (inT, outT) = m.tokens[c] ?: (0L to 0L)
                if (inT + outT > 0) box.addView(text("${c.label}: ${MainActivity.compact(inT.toInt())} in / ${MainActivity.compact(outT.toInt())} out tokens · ${MainActivity.money(m.llmCost(c))}", 14f, C.SUB))
            }
            u.addView(box)
        }
        col.addView(u)
        col.addView(text("Estimated at each provider's paid list price. Free-tier usage actually costs $0. Check your provider's dashboard for real billing.", 13f, C.SUB), lp().margins(this, t = 8))

        // Words on every device that shares the sync file, from each device's own entry in it.
        Usage.everywhere(this)?.let { e ->
            val all = card(18)
            all.addView(row(
                text("All your devices", 17f, bold = true).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) },
                text("${MainActivity.compact(e.total.toInt())} words", 17f, bold = true)
            ))
            all.addView(text("${MainActivity.compact(e.month.toInt())} words this month", 14f, C.SUB))
            for (d in e.each) {
                all.addView(text("${d.name.ifBlank { "Unnamed device" }}: ${MainActivity.compact(d.words.toInt())} words · ${d.dictations} dictations", 14f, C.SUB))
            }
            col.addView(all, lp().margins(this, t = 12))
            col.addView(text("Each device adds its own counts to the sync file when it syncs.", 13f, C.SUB), lp().margins(this, t = 8))
        }

        // --- About
        section(col, "About")
        val about = card(18)
        about.addView(text("Tokalot ${Updater.currentVersion(this)}", 17f, bold = true))
        about.addView(text("Your recordings, history and keys stay on this phone. Dictations go only to the speech and cleanup services you chose, with your own keys. No Tokalot servers, accounts or tracking.", 14f, C.SUB), lp().margins(this, t = 4))
        about.addView(link("Source code: github.com/${Updater.REPO}") {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/${Updater.REPO}")))
        }, lp())
        // A new version can be installed from here too, not only from the banner on Home.
        val ready = Updater.available(this, evenDismissed = true)
        val downloaded = Updater.progress
        val status = text(when {
            ready == null -> ""
            downloaded != null -> "Downloading Tokalot ${ready.version}… $downloaded%"
            else -> "Version ${ready.version} is available."
        }, 14f, C.SUB)
        settingsUpdateText = status
        val check = pill("Check for updates") {
            status.text = "Checking…"
            val app = applicationContext
            Thread {
                Updater.check(app, force = true)
                val failed = Updater.lastCheckFailed
                val r = Updater.available(app, evenDismissed = true)
                runOnUiThread {
                    if (r != null) render() // brings up the Update button
                    else status.text = if (failed) {
                        // Not the same as "up to date": we simply don't know.
                        "Couldn't check for updates. Check your connection and try again."
                    } else "You're up to date."
                }
            }.start()
        }
        about.addView(
            if (ready != null && downloaded == null) row(pill("Update", filled = true) { startUpdate(ready) }, spacer(wDp = 8), check) else row(check),
            lp(),
        )
        about.addView(status, lp().margins(this, t = 2))
        about.addView(link("What's new in ${Tour.VERSION}") { Tour.show(this) }, lp().margins(this, t = 4))
        about.addView(link("Changelog") {
            val entries = Changelog.bundled(this)
            if (entries.isEmpty()) info("Changelog", "The changelog couldn't be read.")
            else sheet("Changelog", changeList(entries), positive = "Close", negative = null)
        }, lp().margins(this, t = 4))
        about.addView(link("Open-source licenses") { info("Open-source licenses", Licenses.TEXT) }, lp().margins(this, t = 4))
        col.addView(about)

        fold(col)
        scroll(col)
    }

    /** A section's small heading. Its title is kept on the view, so [fold] can put the section in its category. */
    private fun section(col: LinearLayout, title: String) = with(a) {
        col.addView(text(title.uppercase(java.util.Locale.US), 12f, C.SUB, bold = true).apply {
            letterSpacing = 0.08f
            tag = SECTION + title
        }, lp().margins(this, t = 28, b = 8, l = 4))
    }

    /**
     * Regroups the finished page into [CATEGORIES]: each a header you tap to open or close, holding its
     * sections. The sections are built as before; this only moves each one (its heading and everything up to
     * the next heading) under its category. Which ones are open is remembered, and Setup stays open while
     * setup isn't finished.
     */
    private fun fold(col: LinearLayout) = with(a) {
        val views = (0 until col.childCount).map { col.getChildAt(it) }
        val sections = LinkedHashMap<String, MutableList<View>>()
        val top = mutableListOf<View>()
        var current: MutableList<View>? = null
        for (v in views) {
            val t = v.tag as? String
            if (t != null && t.startsWith(SECTION)) current = mutableListOf<View>().also { sections[t.removePrefix(SECTION)] = it }
            (current ?: top).add(v)
        }
        col.removeAllViews()
        top.forEach { col.addView(it) }
        val open = prefs.openSettings + if (setupComplete()) emptySet() else setOf(SETUP)
        for ((name, titles) in CATEGORIES) {
            val parts = titles.filter { it in sections }
            if (parts.isEmpty()) continue
            val isOpen = name in open
            val chevron = icon(R.drawable.ic_expand, 22, C.SUB).apply { rotation = if (isOpen) 180f else 0f }
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(name, 20f, bold = true))
                if (parts.size > 1 || parts[0] != name) addView(text(parts.joinToString(" · "), 13f, C.SUB))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(row(texts, chevron).apply {
                background = pressable(rounded(C.CARD, 20))
                setPadding(dp(20), dp(16), dp(16), dp(16))
                minimumHeight = dp(TOUCH_DP)
                contentDescription = "$name, ${if (isOpen) "open" else "closed"}"
                setOnClickListener {
                    prefs.openSettings = if (name in prefs.openSettings) prefs.openSettings - name else prefs.openSettings + name
                    render()
                }
            }, lp().margins(this, t = 12))
            if (!isOpen) continue
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(8)) }
            for (title in parts) {
                val part = sections.getValue(title)
                // A category holding one section of its own name doesn't need the small heading as well.
                val skipHeading = parts.size == 1 && title == name
                part.forEachIndexed { i, v ->
                    if (i == 0 && skipHeading) return@forEachIndexed
                    val p = v.layoutParams as? LinearLayout.LayoutParams
                    // The first heading sits closer to the category header than headings between sections.
                    if (i == 0 && title == parts[0] && p != null) p.topMargin = dp(16)
                    if (i == 1 && skipHeading && p != null) p.topMargin = dp(12)
                    body.addView(v)
                }
            }
            col.addView(body)
        }
    }

    /** One card: an invitation while sync is off, its status and controls once a file is chosen. */
    private fun syncCard(): View = with(a) {
        val on = prefs.syncUri != null
        val broken = on && prefs.syncBroken
        syncShownBroken = broken
        val setup = syncSetup
        val status = text(if (on) "" else "Optional. Off.", 14f, C.SUB)
        syncStatusView = if (on) status else null
        if (on) showSyncStatus(prefs)
        val c = card()
        c.addView(row(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(text("Sync settings between your devices", 17f))
                addView(status)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
            iconButton(R.drawable.ic_info, "How sync works", 22, C.SUB) { info("How sync works", SYNC_INFO) }
        ).apply { setPadding(dp(20), dp(12), dp(6), dp(4)) })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(16), dp(12))
        }
        when {
            // The first sync runs in the background; the buttons come back when it's done.
            setup != null -> actions.addView(text(setup, 15f, C.SUB).apply { minHeight = dp(TOUCH_DP); gravity = Gravity.CENTER_VERTICAL })
            !on || broken -> {
                actions.addView(row(pill("Create a sync file") { createSyncFile() }))
                actions.addView(row(pill("Use an existing sync file") { openSyncFile() }))
            }
            else -> actions.addView(row(pill("Sync now") { syncNow() }))
        }
        c.addView(actions)
        if (on) {
            c.addView(divider())
            c.addView(switchRow("Include API keys", "Off by default. The sync file isn't encrypted, so only turn this on if the folder is private to you.", prefs.syncKeys) {
                prefs.syncKeys = it
                Sync.request(this)
                render() // the note under API keys says where they go
            })
            c.addView(divider())
            c.addView(switchRow("Include dictation history", "Off by default. Shares the text of your newest ${SyncMerge.HISTORY_MAX} dictations, so every device has the same history and stats. Recordings stay on each device.", prefs.syncHistory) {
                prefs.syncHistory = it
                Sync.request(this)
            })
            c.addView(divider())
            c.addView(row(pill("Stop syncing") {
                confirm(
                    "Tokalot forgets the sync file on this device. The file itself and all your settings stay as they are.",
                    "Stop syncing", title = "Stop syncing?"
                ) { stopSync() }
            }).apply { setPadding(dp(20), dp(8), dp(16), dp(8)) })
        }
        c
    }

    private fun modelField(value: String, save: (String) -> Unit): View = with(a) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(text("Model name (change only if the provider renames it)", 13f, C.SUB), lp().margins(this, t = 10, b = 4, l = 4))
        val f = field("Model", value)
        f.onChange { save(it) }
        box.addView(f)
        box
    }

    private fun testCleanup(out: TextView) = with(a) {
        val sample = "um so i was thinking uh we could meet on tuesday no wait wednesday at the the office around 3"
        out.text = "Testing…"
        Thread {
            val start = System.currentTimeMillis()
            val msg = try {
                val r = Cleanup.run(prefs, prefs.cleanup, sample, false)
                "“${r.text}”\n${System.currentTimeMillis() - start} ms"
            } catch (e: Exception) {
                "Failed: ${e.message}"
            }
            runOnUiThread { out.text = msg }
        }.start()
    }

    private fun startDownload(m: LocalModel) {
        if (downloading != null) {
            android.widget.Toast.makeText(a, "Wait for the ${downloading?.label} download to finish", android.widget.Toast.LENGTH_SHORT).show()
            a.render()
            return
        }
        downloading = m
        downloadPct = 0
        downloadError = null
        a.render()
        val app = a.applicationContext
        Thread {
            var last = -1
            val err = ModelManager.download(app, m) { p ->
                downloadPct = p
                if (p != last) {
                    last = p
                    a.runOnUiThread { modelStatusView?.text = "Downloading $p%"; modelRowStatus?.text = "Downloading $p%" }
                }
            }
            downloading = null
            downloadPct = null
            downloadError = err
            a.runOnUiThread { if (!a.isFinishing) a.render() }
        }.start()
    }

}
