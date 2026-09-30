package com.tokalot.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The whole app UI: a header, a content area and a bottom tab bar
 * (Home, Dictionary, Style, Snippets). The menu button opens Settings.
 */
class MainActivity : Activity() {

    enum class Tab(val label: String, val icon: Int) {
        HOME("Home", R.drawable.ic_home),
        DICTIONARY("Dictionary", R.drawable.ic_book),
        STYLE("Style", R.drawable.ic_style),
        SNIPPETS("Snippets", R.drawable.ic_snippet),
    }

    lateinit var prefs: Prefs
    private lateinit var content: FrameLayout
    private lateinit var headerLeft: FrameLayout
    private lateinit var nav: LinearLayout
    private var tab = Tab.HOME
    private var inSettings = false
    private var historyLimit = 100
    private val expanded = HashSet<Long>()
    private val showOriginal = HashSet<Long>()
    private var query = ""
    private var bannerText: TextView? = null
    private var logoBars: BarsView? = null
    private var historyBox: LinearLayout? = null
    private val searchField by lazy {
        field("Search your dictations").apply {
            background = rounded(C.CARD, 16, C.PILL, 1)
            setPadding(dp(18), dp(14), dp(18), dp(14))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: android.text.Editable?) {
                    query = e?.toString()?.trim() ?: ""
                    fillHistory()
                }
            })
        }
    }
    private var player: MediaPlayer? = null
    private var playingId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        // Pick light or dark before any views exist (dialogs and switches follow the theme too).
        val systemDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val dark = when (prefs.theme) { "dark" -> true; "light" -> false; else -> systemDark }
        C.apply(dark)
        setTheme(if (dark) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (dark) 0 else
            View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(C.BG)
        }

        // Header: menu/back on the left, logo centered.
        val header = FrameLayout(this).apply { setPadding(dp(12), dp(8), dp(12), dp(8)) }
        headerLeft = FrameLayout(this)
        header.addView(headerLeft, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.START or Gravity.CENTER_VERTICAL))
        val logo = row(
            BarsView(this).apply {
                barColor = C.TEXT
                accentColor = prefs.accent
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
                logoBars = this
            },
            text("Tokalot", 26f, bold = true).apply { setPadding(dp(8), 0, 0, 0) }
        )
        header.addView(logo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        root.addView(header, lp())

        content = FrameLayout(this)
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(10))
            setBackgroundColor(C.BG)
        }
        root.addView(nav, lp())
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        History.onChange = { if (!inSettings && tab == Tab.HOME) render() }
        render()
        // Quietly look for a newer release (at most every few hours).
        Thread { if (Updater.check(applicationContext)) runOnUiThread { render() } }.start()
    }

    // ---------- updates ----------

    fun startUpdate(r: Updater.Release) {
        if (Updater.progress != null) return
        // Android requires the user to allow installs from Tokalot once.
        if (!packageManager.canRequestPackageInstalls()) {
            toast("Allow Tokalot to install updates, then tap Update again")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        Updater.progress = 0
        render()
        val app = applicationContext
        Thread {
            // Only the banner's label changes while downloading; no full redraw (that flashed).
            val poll = Thread {
                while (Updater.progress != null) {
                    val pct = Updater.progress
                    runOnUiThread { bannerText?.text = "Downloading Tokalot ${r.version}… ${pct ?: 0}%" }
                    Thread.sleep(300)
                }
            }.also { it.start() }
            try {
                val apk = Updater.download(app, r)
                Updater.install(app, apk)
            } catch (e: Exception) {
                runOnUiThread { toast("Update failed: ${e.message}") }
            }
            poll.join()
            runOnUiThread { render() }
        }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_INSTALL_STATUS) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    (intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))?.let { startActivity(it) }
                }
                PackageInstaller.STATUS_SUCCESS -> {}
                else -> toast("Update not installed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "cancelled"}")
            }
        }
    }

    // ---------- backup (Android's "Save to…" / "Open" pickers) ----------

    private var backupAudio = false
    private var backupKeys = false

    fun startBackup(includeAudio: Boolean, includeKeys: Boolean) {
        backupAudio = includeAudio; backupKeys = includeKeys
        val name = "tokalot-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".zip"
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip").putExtra(Intent.EXTRA_TITLE, name), REQ_BACKUP
        )
    }

    fun startRestore() {
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_RESTORE
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        val app = applicationContext
        Thread {
            val msg = try {
                when (requestCode) {
                    REQ_BACKUP -> {
                        val s = contentResolver.openOutputStream(uri)!!.use { Backup.write(app, it, backupAudio, backupKeys) }
                        "Backed up ${s.entries} dictations" + (if (s.recordings > 0) " and ${s.recordings} recordings" else "") +
                            (if (s.keys) " (with API keys)" else "")
                    }
                    REQ_RESTORE -> {
                        val s = contentResolver.openInputStream(uri)!!.use { Backup.restore(app, it) }
                        "Restored ${s.entries} dictations" + (if (s.recordings > 0) " and ${s.recordings} recordings" else "") +
                            (if (!s.keys) ". Your existing API keys were kept." else "")
                    }
                    else -> return@Thread
                }
            } catch (e: Exception) {
                "Failed: ${e.message}"
            }
            runOnUiThread { prefs = Prefs(this); render(); toast(msg) }
        }.start()
    }

    // ---------- accessibility disclosure ----------

    /** Plain-language explanation shown before sending the user to Android's accessibility settings. */
    fun openAccessibilitySetup() {
        val pad = dp(22)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(8), pad, 0)
        }
        fun point(title: String, detail: String) {
            body.addView(text(title, 16f, bold = true), lp().margins(this, t = 12))
            body.addView(text(detail, 15f, C.SUB))
        }
        body.addView(text("Android shows a strong warning for every accessibility app, because the permission is powerful. Here's exactly what Tokalot does with it.", 15f))
        point("What it's used for",
            "Noticing when your keyboard is open on a text box (to show the button), learning which app you're in (for its style), and typing your words into that box.")
        point("What it never does",
            "It doesn't read your screen, messages, passwords or notifications, and it skips password fields entirely. Nothing is logged except the dictations you see on the Home tab.")
        point("Where your data goes",
            "Everything stays on this phone. The only thing that leaves is your dictation, sent directly to the speech and cleanup services you picked, using your own API keys. No Tokalot server, account or tracking.")
        point("Check it yourself",
            "Tokalot's code is open for anyone to read or have scanned.")
        body.addView(text("github.com/${Updater.REPO}", 15f, C.LINK).apply {
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${Updater.REPO}"))) }
        })
        body.addView(text("On the next screen: tap Tokalot › turn it on › Allow. If it's greyed out, open this phone's Settings › Apps › Tokalot › ⋮ › Allow restricted settings first.", 14f, C.SUB), lp().margins(this, t = 16, b = 8))
        android.app.AlertDialog.Builder(this)
            .setTitle("Before you turn this on")
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton("Continue") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            .setNegativeButton("Not now", null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        History.onChange = null
        stopPlayback(redraw = false)
    }

    private fun play(id: Long) {
        stopPlayback(redraw = false)
        try {
            player = MediaPlayer().apply {
                setDataSource(AudioStore.file(this@MainActivity, id).absolutePath)
                setOnCompletionListener { stopPlayback() }
                prepare()
                start()
            }
            playingId = id
        } catch (e: Exception) {
            toast("Couldn't play that recording")
            stopPlayback(redraw = false)
        }
        render()
    }

    private fun stopPlayback(redraw: Boolean = true) {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
        val was = playingId
        playingId = null
        if (redraw && was != null) render()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            inSettings -> { inSettings = false; render() }
            tab != Tab.HOME -> { tab = Tab.HOME; render() }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        render()
    }

    fun openSettings() { inSettings = true; render() }

    /** Rebuilds the visible screen. Screens are cheap to build, so we just redraw. */
    fun render() {
        logoBars?.accentColor = prefs.accent // follows the button color picked in Settings
        headerLeft.removeAllViews()
        val leftIcon = if (inSettings) R.drawable.ic_back else R.drawable.ic_menu
        headerLeft.addView(icon(leftIcon, 28).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = FrameLayout.LayoutParams(dp(48), dp(48))
            setOnClickListener { if (inSettings) { inSettings = false; render() } else openSettings() }
        })
        renderNav()
        // Keep the scroll position when redrawing the same screen (expanding an entry, download progress…).
        val key = if (inSettings) "settings" else tab.name
        val prevScroll = if (key == lastScreen) (content.getChildAt(0) as? ScrollView)?.scrollY ?: 0 else 0
        lastScreen = key
        content.removeAllViews()
        val screen = if (inSettings) SettingsScreen(this).build() else when (tab) {
            Tab.HOME -> buildHome()
            Tab.DICTIONARY -> DictionaryScreen(this).build()
            Tab.STYLE -> StyleScreen(this).build()
            Tab.SNIPPETS -> SnippetsScreen(this).build()
        }
        content.addView(screen)
        if (prevScroll > 0 && screen is ScrollView) screen.post { screen.scrollTo(0, prevScroll) }
    }

    private var lastScreen = ""

    private fun renderNav() {
        nav.removeAllViews()
        nav.visibility = if (inSettings) View.GONE else View.VISIBLE
        for (t in Tab.values()) {
            val active = t == tab
            val iconBox = FrameLayout(this).apply {
                background = if (active) rounded(C.NAV_ACTIVE, 100) else null
                addView(icon(t.icon, 26), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
            }
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                addView(iconBox, LinearLayout.LayoutParams(dp(64), dp(36)))
                addView(text(t.label, 13f, if (active) C.TEXT else C.SUB).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(4), 0, 0)
                })
                setOnClickListener { tab = t; render() }
            }
            nav.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    // ---------- shared helpers for screens ----------

    fun scroll(child: View) = ScrollView(this).apply {
        isFillViewport = true
        addView(child, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(8), dp(20), dp(32))
    }

    fun copy(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Tokalot", text))
        toast("Copied")
    }

    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    fun micGranted() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun serviceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.contains("$packageName/${OfflineFlowService::class.java.name}")
    }

    fun setupComplete() = micGranted() && serviceEnabled() &&
        (prefs.cloudSttReady || ModelManager.isReady(this))

    // ---------- Home ----------

    private fun buildHome(): View {
        val col = column()

        // Subtle "new version" banner.
        Updater.available(this)?.let { r ->
            val pct = Updater.progress
            val msg = if (pct != null) "Downloading Tokalot ${r.version}… $pct%" else "Tokalot ${r.version} is available"
            val label = text(msg, 15f).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
            bannerText = label
            val banner = row(
                label,
                if (pct == null) pill("Update", filled = true) { startUpdate(r) } else spacer(),
                if (pct == null) icon(R.drawable.ic_close, 20, C.SUB).apply {
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).margins(this@MainActivity, l = 8)
                    setOnClickListener { Updater.dismiss(this@MainActivity, r.version); render() }
                } else spacer()
            ).apply {
                background = rounded(C.CARD, 18)
                setPadding(dp(18), dp(10), dp(12), dp(10))
            }
            col.addView(banner, lp().margins(this, b = 12))
        }

        if (!setupComplete()) {
            val c = card(20)
            c.addView(heading("Finish setup", 28f))
            c.addView(text("Tokalot needs the microphone, the accessibility switch, and a Groq key (or the offline model) before the mic button will appear.", 15f, C.SUB).apply {
                setPadding(0, dp(6), 0, dp(14))
            })
            c.addView(row(pill("Open setup", filled = true) { openSettings() }))
            col.addView(c, lp().margins(this, b = 16))
        }

        // This month at a glance.
        val m = Usage.month(this)
        val stats = card(20)
        stats.addView(text(m.label.uppercase(Locale.US), 12f, C.SUB).apply { letterSpacing = 0.08f })
        val statRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(10), 0, 0) }
        fun stat(value: String, label: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(heading(value, 30f))
            addView(text(label, 13f, C.SUB))
        }
        statRow.addView(stat(compact(m.words), "words"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statRow.addView(stat(m.dictations.toString(), "dictations"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        statRow.addView(stat(money(m.total), "est. cost"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        stats.addView(statRow)
        if (m.fillers + m.corrections > 0) {
            stats.addView(text("Cleaned up ${m.fillers} filler words and ${m.corrections} self-corrections", 13f, C.SUB).apply {
                setPadding(0, dp(10), 0, 0)
            })
        }
        stats.setOnClickListener { openSettings() }
        col.addView(stats, lp().margins(this, b = 16))

        // Search stays the same view across redraws so typing isn't interrupted.
        (searchField.parent as? ViewGroup)?.removeView(searchField)
        col.addView(searchField, lp())

        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        historyBox = box
        col.addView(box, lp())
        fillHistory()
        return scroll(col)
    }

    /** (Re)builds just the history list, filtered by the search box. */
    private fun fillHistory() {
        val box = historyBox ?: return
        box.removeAllViews()
        val all = History.all(this)
        val q = query.lowercase()
        val list = if (q.isEmpty()) all else all.filter {
            it.text.lowercase().contains(q) || it.raw.lowercase().contains(q) ||
                (it.app.isNotEmpty() && AppContext.label(this, it.app).lowercase().contains(q))
        }
        if (list.isEmpty()) {
            box.addView(heading(if (q.isEmpty()) "Today" else "No matches"), lp().margins(this, t = 24, b = 12))
            val c = card(24)
            c.addView(text(
                if (q.isEmpty()) "Nothing yet. Tap any text field in any app and hit the button." else "Nothing matches \u201C$query\u201D.",
                17f, C.SUB
            ))
            box.addView(c)
            return
        }
        var lastDay = ""
        var group: LinearLayout? = null
        for (e in list.take(historyLimit)) {
            val day = dayLabel(e.time)
            if (day != lastDay) {
                box.addView(heading(day), lp().margins(this, t = 24, b = 12))
                group = card()
                box.addView(group)
                lastDay = day
            } else {
                group?.addView(divider())
            }
            group?.addView(entryView(e))
        }
        if (list.size > historyLimit) {
            box.addView(row(pill("Show older") { historyLimit += 200; fillHistory() }).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(20), 0, 0)
            })
        }
    }

    private fun entryView(e: Entry): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(18))
        }
        val isOpen = e.id in expanded
        val body = text(e.text, 18f).apply {
            if (!isOpen) {
                maxLines = 6
                ellipsize = TextUtils.TruncateAt.END
            }
            setTextIsSelectable(isOpen)
            setOnClickListener { toggle(expanded, e.id) }
        }
        box.addView(body)

        if (e.id in showOriginal) {
            val orig = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(C.FIELD, 14)
                setPadding(dp(14), dp(10), dp(14), dp(12))
                addView(text("ORIGINAL", 11f, C.SUB).apply { letterSpacing = 0.08f })
                addView(text(e.raw, 15f, C.SUB).apply { setTextIsSelectable(true) })
            }
            box.addView(orig, lp().margins(this, t = 12))
        }

        val secs = e.durationMs / 1000
        val meta = buildString {
            append(SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(e.time)))
            if (e.app.isNotEmpty()) append(" · ${AppContext.label(this@MainActivity, e.app)}")
            if (secs > 0) append(" · ${secs}s")
            if (e.cleaned) append(" · AI")
        }
        box.addView(text(meta, 14f, C.SUB), lp().margins(this, t = 10, b = 12))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        actions.addView(pill("Copy", R.drawable.ic_copy) { copy(e.text) })
        if (AudioStore.exists(this, e.id)) {
            val playing = playingId == e.id
            actions.addView(spacer(wDp = 8))
            actions.addView(pill(null, if (playing) R.drawable.ic_stop else R.drawable.ic_play, filled = playing) {
                if (playing) stopPlayback() else play(e.id)
            })
        }
        if (e.cleaned && e.raw.isNotBlank() && e.raw != e.text) {
            actions.addView(spacer(wDp = 8))
            actions.addView(pill("Original", filled = e.id in showOriginal) { toggle(showOriginal, e.id) })
        }
        actions.addView(weightSpacer())
        lateinit var more: View
        more = pill(null, R.drawable.ic_more) {
            PopupMenu(this, more).apply {
                menu.add(0, 1, 0, "Copy original")
                menu.add(0, 2, 1, "Delete")
                setOnMenuItemClickListener {
                    when (it.itemId) {
                        1 -> copy(e.raw.ifBlank { e.text })
                        2 -> {
                            if (playingId == e.id) stopPlayback(redraw = false)
                            History.delete(this@MainActivity, e.id)
                            AudioStore.delete(this@MainActivity, e.id)
                            render()
                        }
                    }
                    true
                }
            }.show()
        }
        actions.addView(more)
        box.addView(actions)
        return box
    }

    private fun toggle(set: HashSet<Long>, id: Long) {
        if (!set.add(id)) set.remove(id)
        render()
    }

    private fun dayLabel(t: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = t }
        val today = Calendar.getInstance()
        val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        fun same(a: Calendar, b: Calendar) =
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        return when {
            same(c, today) -> "Today"
            same(c, yesterday) -> "Yesterday"
            c.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> SimpleDateFormat("EEEE, MMM d", Locale.US).format(Date(t))
            else -> SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(t))
        }
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "com.tokalot.app.INSTALL_STATUS"
        private const val REQ_BACKUP = 41
        private const val REQ_RESTORE = 42

        fun compact(n: Int): String = when {
            n >= 1_000_000 -> String.format(Locale.US, "%.1fM", n / 1e6)
            n >= 10_000 -> "${n / 1000}K"
            n >= 1_000 -> String.format(Locale.US, "%.1fK", n / 1e3)
            else -> n.toString()
        }

        fun money(v: Double): String = when {
            v == 0.0 -> "$0"
            v < 0.01 -> "<$0.01"
            else -> String.format(Locale.US, "$%.2f", v)
        }
    }
}
