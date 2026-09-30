package com.tokalot.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

private fun EditText.onChange(block: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun afterTextChanged(s: Editable?) = block(s?.toString() ?: "")
})

/** A title/subtitle row with an on/off switch. */
private fun MainActivity.switchRow(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit): View {
    val sw = android.widget.Switch(this).apply { isChecked = on }
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

        val words = prefs.words
        if (words.isEmpty()) {
            col.addView(card(22).apply { addView(text("No words yet.", 16f, C.SUB)) })
        } else {
            val c = card()
            words.sortedBy { it.lowercase() }.forEachIndexed { i, w ->
                if (i > 0) c.addView(divider())
                val remove = icon(R.drawable.ic_close, 20, C.SUB).apply {
                    setOnClickListener { prefs.words = prefs.words - w; render() }
                }
                c.addView(row(
                    text(w, 17f).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) },
                    remove
                ).apply { setPadding(dp(20), dp(16), dp(20), dp(16)) })
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
            setPadding(dp(20), dp(12), dp(20), 0)
            addView(trig)
            addView(body, lp().margins(this@with, t = 12))
        }
        val dlg = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "New snippet" else "Edit snippet")
            .setView(form)
            .setPositiveButton("Save") { _, _ ->
                val t = trig.text.toString().trim()
                val x = body.text.toString()
                if (t.isNotEmpty() && x.isNotBlank()) {
                    val rest = prefs.snippets.filter { it != existing && !it.trigger.equals(t, true) }
                    prefs.snippets = rest + Snippet(t, x)
                    render()
                }
            }
            .setNegativeButton("Cancel", null)
        if (existing != null) dlg.setNeutralButton("Delete") { _, _ ->
            prefs.snippets = prefs.snippets - existing
            render()
        }
        dlg.show()
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
                setOnClickListener { openSettings() }
            }, lp().margins(this, b = 16))
        }

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
                        AlertDialog.Builder(this@with)
                            .setTitle(AppContext.label(this@with, pkg))
                            .setSingleChoiceItems(cats.map { it.label }.toTypedArray(), cats.indexOf(cat)) { d, which ->
                                prefs.setAppOverride(pkg, cats[which]); d.dismiss(); render()
                            }.show()
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

// ------------------------------------------------------------------ Settings

class SettingsScreen(private val a: MainActivity) {

    companion object {
        @Volatile var downloadPct: Int? = null
        @Volatile var downloadError: String? = null
        // Progress updates only change this label; redrawing the whole screen each tick made it flash.
        var modelStatusView: TextView? = null
    }

    fun build(): View = with(a) {
        val col = column()
        col.addView(heading("Settings"), lp().margins(this, t = 8, b = 16))

        // --- Setup
        section(col, "Setup")
        val setup = card()
        fun setupRow(title: String, done: Boolean, doneText: String, action: String, onClick: () -> Unit): TextView {
            if (setup.childCount > 0) setup.addView(divider())
            val status = text(if (done) doneText else "Not done", 14f, if (done) C.GOOD else C.WARN)
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
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val ok = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            setupRow("Notifications (recording indicator)", ok, "Allowed", "Allow") {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
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
                if (prefs.theme != id) { prefs.theme = id; recreate() }
            })
        }
        col.addView(ap)
        col.addView(text("Floating button color while listening", 14f, C.SUB), lp().margins(this, t = 16, b = 8, l = 4))
        val swatches = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        Accents.all.forEach { (name, color) ->
            val on = prefs.accent == color
            swatches.addView(View(this).apply {
                // A dark disc with the color as a thick ring, like the button itself.
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(color)
                    setStroke(dp(if (on) 4 else 1), if (on) C.TEXT else C.PILL)
                }
                contentDescription = name
                setOnClickListener { prefs.accent = color; render() }
            }, LinearLayout.LayoutParams(dp(34), dp(34)).margins(this, r = 10))
        }
        col.addView(android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(swatches)
        }, lp().margins(this, l = 4))

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
        rc.addView(switchRow("Detect language automatically", "Off keeps it English-only, which is most accurate for English. The offline backup is English-only either way.", prefs.autoLanguage) {
            prefs.autoLanguage = it
        })
        col.addView(rc)

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
            val f = field("Paste key", prefs.key(id), secret = true)
            f.onChange { prefs.setKey(id, it) }
            keys.addView(label, lp().margins(this, t = if (i == 0) 0 else 16))
            keys.addView(hint)
            keys.addView(f, lp().margins(this, t = 6))
        }
        col.addView(keys)
        col.addView(text("Keys are saved only on this phone, in Tokalot's private storage. Nothing is synced anywhere. Uninstalling the app deletes them.", 13f, C.SUB), lp().margins(this, t = 8))

        // --- Recordings
        section(col, "Recordings")
        val rec = card()
        listOf(0 to "Don't save audio", 7 to "Keep 7 days", 30 to "Keep 30 days", Int.MAX_VALUE to "Keep forever")
            .forEachIndexed { i, (days, label) ->
                if (i > 0) rec.addView(divider())
                rec.addView(choiceRow(label, "", prefs.audioKeepDays == days) {
                    prefs.audioKeepDays = days
                    Thread { AudioStore.prune(applicationContext, days); runOnUiThread { render() } }.start()
                    render()
                })
            }
        col.addView(rec)
        val mb = AudioStore.totalBytes(this) / 1_048_576.0
        col.addView(row(
            text("Using ${String.format(java.util.Locale.US, "%.1f", mb)} MB", 14f, C.SUB).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
            pill("Delete all") {
                AlertDialog.Builder(this).setMessage("Delete all saved recordings? Transcripts stay.")
                    .setPositiveButton("Delete") { _, _ -> AudioStore.prune(this, 0); render() }
                    .setNegativeButton("Cancel", null).show()
            }
        ), lp().margins(this, t = 8))

        // --- Backup
        section(col, "Backup")
        val bc = card()
        var withAudio = false
        var withKeys = false
        bc.addView(switchRow("Include recordings", "Makes the file much bigger.", false) { withAudio = it })
        bc.addView(divider())
        bc.addView(switchRow("Include API keys", "Only if you'll keep the file somewhere private. Anyone with the file could use your keys.", false) { withKeys = it })
        col.addView(bc)
        col.addView(row(
            pill("Back up…", filled = true) { startBackup(withAudio, withKeys) },
            spacer(wDp = 10),
            pill("Restore…") {
                AlertDialog.Builder(this).setTitle("Restore from backup?")
                    .setMessage("This replaces your current settings, dictionary, snippets and history with the backup's. API keys on this phone are kept unless the backup includes keys.")
                    .setPositiveButton("Choose file") { _, _ -> startRestore() }
                    .setNegativeButton("Cancel", null).show()
            }
        ), lp().margins(this, t = 10))
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
        about.addView(text("Source code: github.com/${Updater.REPO}", 14f, C.LINK).apply {
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/${Updater.REPO}"))) }
        }, lp().margins(this, t = 8))
        val status = text("", 14f, C.SUB)
        about.addView(row(pill("Check for updates") {
            status.text = "Checking…"
            val app = applicationContext
            Thread {
                Updater.check(app, force = true)
                val r = Updater.available(app)
                runOnUiThread { status.text = if (r != null) "Version ${r.version} is available. See the banner on Home." else "You're up to date." }
            }.start()
        }), lp().margins(this, t = 12))
        about.addView(status, lp().margins(this, t = 6))
        about.addView(text("Open-source licenses", 14f, C.LINK).apply {
            setOnClickListener {
                AlertDialog.Builder(this@with).setTitle("Open-source licenses")
                    .setMessage(Licenses.TEXT).setPositiveButton("OK", null).show()
            }
        }, lp().margins(this, t = 12))
        col.addView(about)

        scroll(col)
    }

    private fun section(col: LinearLayout, title: String) = with(a) {
        col.addView(text(title.uppercase(java.util.Locale.US), 12f, C.SUB, bold = true).apply {
            letterSpacing = 0.08f
        }, lp().margins(this, t = 28, b = 8, l = 4))
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
