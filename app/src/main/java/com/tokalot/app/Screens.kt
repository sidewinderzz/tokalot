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
        intro(col, "Notes", "One note per dictation, saved here and copied to the clipboard. Start one from the Voice note tile in Quick Settings, or the accessibility shortcut if you set it to notes.")
        // The beta asks whether it's worth keeping: a short public form on GitHub, nothing sent from here.
        col.addView(card(18).apply {
            addView(text("Voice notes are a beta. Worth keeping?", 16f, bold = true))
            addView(text("Opens a short form on GitHub (a free account is needed). It's public, so your notes aren't in it.", 14f, C.SUB), lp().margins(this@with, t = 2, b = 6))
            fun open(vote: String) = startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Notes.feedbackUrl(this@with, vote))))
            addView(row(pill("Useful", filled = true) { open("useful") }, spacer(wDp = 8), pill("Not for me") { open("not for me") }))
        }, lp().margins(this, b = 16))
        val notes = Notes.all(this)
        if (notes.isEmpty()) {
            col.addView(card(22).apply {
                addView(text("No notes yet. Swipe down twice from the top of the screen, tap Tokalot Voice note and talk. Tap the button when you're done.", 16f, C.SUB))
            })
            return scroll(col)
        }
        // Grouped by day, newest first, each note with Copy, Share and Delete.
        notes.groupBy { dayLabel(it.time) }.forEach { (label, group) ->
            col.addView(text(label.uppercase(java.util.Locale.US), 12f, C.SUB, bold = true).apply { letterSpacing = 0.08f },
                lp().margins(this, t = 12, b = 8, l = 4))
            val c = card()
            group.forEachIndexed { i, n ->
                if (i > 0) c.addView(divider())
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(14), dp(12), dp(6))
                }
                box.addView(text(java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(n.time)), 13f, C.SUB))
                box.addView(text(n.text, 16f).apply { setTextIsSelectable(true) }, lp().margins(this, t = 2, r = 8))
                box.addView(row(
                    weightSpacer(),
                    iconButton(R.drawable.ic_copy, "Copy note", 20, C.SUB) { copy(n.text) },
                    pill("Share") {
                        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, n.text), "Share note"))
                    },
                    spacer(wDp = 4),
                    iconButton(R.drawable.ic_close, "Delete note", 20, C.SUB) {
                        confirm("Delete this note? It stays in history.", "Delete") { Notes.delete(this, n.id); render() }
                    },
                ))
                c.addView(box)
            }
            col.addView(c)
        }
        scroll(col)
    }
}

// ------------------------------------------------------------------ Settings

class SettingsScreen(private val a: MainActivity) {

    companion object {
        const val SETUP = "Setup"
        const val SPEECH = "Speech & cleanup"
        const val DATA = "Your data"
        const val RECORDING = "Recording & look"
        private const val SECTION = "section:"

        /** Settings categories, in order, each with its sections in the order shown when it's open. */
        val CATEGORIES = listOf(
            SETUP to listOf("Setup"),
            SPEECH to listOf("Speech to text", "AI cleanup", "API keys", "Speed"),
            RECORDING to listOf("Recording", "Without a text box", "Appearance"),
            DATA to listOf("Recordings", "Sync", "Backup", "Usage"),
            "About" to listOf("About"),
        )

        @Volatile var downloadPct: Int? = null
        @Volatile var downloadError: String? = null
        // Progress updates only change this label; redrawing the whole screen each tick made it flash.
        var modelStatusView: TextView? = null

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
            "Tokalot keeps your dictionary, snippets, styles, instructions and voice notes in one small file. Put that file in a folder you already sync, such as Google Drive, OneDrive, Dropbox or Syncthing, and point each of your devices at it. Each device reads the file and adds its own changes.",
            "It's private. There is no Tokalot account and no Tokalot server. The file only goes where your own sync service takes it. Your history and recordings are never put in it.",
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
        val pct = downloadPct
        val modelStatus = when {
            ModelManager.isReady(this) -> "Downloaded"
            pct != null -> "Downloading $pct%"
            downloadError != null -> "Failed: $downloadError"
            else -> ""
        }
        modelStatusView = setupRow("Offline backup model (60 MB)", ModelManager.isReady(this) || pct != null, modelStatus, "Download") {
            startDownload()
        }
        col.addView(setup)
        col.addView(text("If the accessibility switch is greyed out: phone Settings › Apps › Tokalot › ⋮ › Allow restricted settings. Also set Tokalot's battery usage to Unrestricted.", 13f, C.SUB), lp().margins(this, t = 8))

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
            addView(text("Talk while you read or scroll, with no text box open. The text goes into the text box if the keyboard is up, and onto the clipboard if it isn't. Swipe down twice from the top, tap the pencil, and drag Tokalot Dictate into your tiles.", 14f, C.SUB))
            if (Build.VERSION.SDK_INT >= 33) addView(row(pill("Add the tile") {
                StartTile.requestAdd(this@with, DictateTile::class.java, "Dictate", R.drawable.ic_mic)
            }), lp().margins(this@with, t = 6))
        })
        hand.addView(divider())
        hand.addView(switchRow("Voice notes (beta)", "Adds a Notes page and a Voice note tile. Each dictation started from it is saved as its own note and copied to the clipboard. Notes are in backups, and in sync when it's set up.", prefs.notesBeta) { on ->
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
        if (Build.VERSION.SDK_INT >= 30 && prefs.shortcutAction != "off") {
            col.addView(text("Then turn the shortcut on in Android: Settings › Accessibility › Tokalot › Tokalot shortcut, and pick \"Hold volume keys\" (or the accessibility button or gesture). It starts the action above; it doesn't switch Tokalot off.", 13f, C.SUB), lp().margins(this, t = 8, l = 4))
            col.addView(link("Open accessibility settings") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }, lp().margins(this, l = 4))
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
            val hint = text("Get one at ${Services.keyPages[id]}", 13f, C.SUB)
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
        val pct = Updater.progress
        val status = text(when {
            ready == null -> ""
            pct != null -> "Downloading Tokalot ${ready.version}… $pct%"
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
            if (ready != null && pct == null) row(pill("Update", filled = true) { startUpdate(ready) }, spacer(wDp = 8), check) else row(check),
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

    private fun startDownload() {
        if (downloadPct != null) return
        downloadPct = 0
        downloadError = null
        a.render()
        val app = a.applicationContext
        Thread {
            var last = -1
            val err = ModelManager.download(app) { p ->
                downloadPct = p
                if (p != last) {
                    last = p
                    a.runOnUiThread { modelStatusView?.text = "Downloading $p%" }
                }
            }
            downloadPct = null
            downloadError = err
            a.runOnUiThread { if (!a.isFinishing) a.render() }
        }.start()
    }

}
