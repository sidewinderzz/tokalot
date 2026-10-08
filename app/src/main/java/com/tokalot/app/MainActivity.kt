package com.tokalot.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.res.Configuration
import android.net.Uri
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
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
 * Rotation, dark-mode switches and the like are handled in place (see the manifest's
 * configChanges), so they never reset the screen you're on.
 */
class MainActivity : Activity() {

    enum class Tab(val label: String, val icon: Int) {
        HOME("Home", R.drawable.ic_home),
        DICTIONARY("Dictionary", R.drawable.ic_book),
        STYLE("Style", R.drawable.ic_style),
        SNIPPETS("Snippets", R.drawable.ic_snippet),
        NOTES("Notes", R.drawable.ic_note), // only while Voice notes (beta) is on
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
    private var bannerOpen = false // the update banner's "What's new" is showing
    private var logoBars: BarsView? = null
    private var historyBox: LinearLayout? = null
    private var searchField: EditText? = null
    private var player: MediaPlayer? = null
    private var playingId: Long? = null
    private var density = 0f
    private var fontScale = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = Prefs(this)
        // Pick light or dark before any views exist (dialogs and switches follow the theme too).
        applyColors()
        super.onCreate(savedInstanceState)
        savedInstanceState?.let { s ->
            tab = Tab.values().getOrElse(s.getInt("tab")) { Tab.HOME }
            inSettings = s.getBoolean("settings")
            backupAudio = s.getBoolean("backupAudio")
            backupKeys = s.getBoolean("backupKeys")
        }
        if (savedInstanceState == null) openTab(intent)
        buildRoot()
        // A fresh launch can itself be the installer reporting back (the old task was gone).
        // Not when reopened from Recents, which replays the old intent.
        val replayed = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        if (savedInstanceState == null && !replayed) handleInstallStatus(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", tab.ordinal)
        outState.putBoolean("settings", inSettings)
        outState.putBoolean("backupAudio", backupAudio)
        outState.putBoolean("backupKeys", backupKeys)
    }

    private fun wantDark(): Boolean {
        val systemDark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return when (prefs.theme) { "dark" -> true; "light" -> false; else -> systemDark }
    }

    private fun applyColors() {
        val dark = wantDark()
        C.apply(dark)
        setTheme(if (dark) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar)
    }

    /** Builds the frame every screen sits in. Called again when the theme or text size changes. */
    private fun buildRoot() {
        density = resources.displayMetrics.density
        fontScale = resources.configuration.fontScale
        window.statusBarColor = C.BG
        window.navigationBarColor = C.BG
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (C.dark) 0 else
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
                accentColor = C.visible(prefs.accent)
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
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

    /**
     * Re-reads the theme and redraws everything in place: same tab, same scroll position.
     * Used when the theme setting or the phone's dark mode changes, and after a restore.
     */
    fun applyTheme() {
        val scroll = (content.getChildAt(0) as? ScrollView)?.scrollY ?: 0
        prefs = Prefs(this)
        applyColors()
        searchField = null // holds the old colors
        buildRoot()
        render()
        if (scroll > 0) (content.getChildAt(0) as? ScrollView)?.let { it.post { it.scrollTo(0, scroll) } }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation and keyboard changes need nothing: the views just lay out again.
        // Colors and sizes are baked into the views, so those changes need a rebuild.
        val changed = wantDark() != C.dark || resources.displayMetrics.density != density || newConfig.fontScale != fontScale
        if (changed) applyTheme()
    }

    override fun onResume() {
        super.onResume()
        History.onChange = { if (!inSettings && tab == Tab.HOME) render() }
        Notes.onChange = { if (!inSettings && tab == Tab.NOTES) render() }
        render()
        Sync.onDone = { changed -> if (!isDestroyed) syncFinished(changed) }
        Sync.request(this)
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

    private val INSTALL_CONFIRM_ACTIONS = setOf(
        "android.content.pm.action.CONFIRM_INSTALL", "android.content.pm.action.CONFIRM_PERMISSIONS",
    )

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleInstallStatus(intent)
        if (openTab(intent)) render()
    }

    /** Opened on a given page (tapping "Saved to Notes" opens Notes). True if the intent asked for one. */
    private fun openTab(intent: Intent?): Boolean {
        val t = intent?.getStringExtra(EXTRA_TAB)?.let { name -> Tab.values().firstOrNull { it.name == name } } ?: return false
        tab = t
        inSettings = false
        return true
    }

    /** The installer's answer to Updater.install(): usually "ask the user", which means launching its prompt. */
    private fun handleInstallStatus(intent: Intent?) {
        if (intent?.action != ACTION_INSTALL_STATUS) return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val next = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (next != null && next.action in INSTALL_CONFIRM_ACTIONS) {
                    next.flags = next.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION).inv()
                    startActivity(next)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> toast("Update not installed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "cancelled"}")
        }
    }

    // ---------- backup (Android's "Save to…" / "Open" pickers) ----------

    /** The two backup switches. Kept here (and across restarts) so a redraw can't silently flip them off. */
    var backupAudio = false
    var backupKeys = false

    fun startBackup() {
        if (backupBusy != null) return
        val name = "tokalot-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".zip"
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip").putExtra(Intent.EXTRA_TITLE, name), REQ_BACKUP
        )
    }

    fun startRestore() {
        if (backupBusy != null) return
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
        if (requestCode == REQ_SYNC_CREATE || requestCode == REQ_SYNC_OPEN) { useSyncFile(uri); return }
        if (requestCode != REQ_BACKUP && requestCode != REQ_RESTORE) return
        if (backupBusy != null) return
        val app = applicationContext
        val withAudio = backupAudio
        val withKeys = backupKeys
        // Zipping or unzipping recordings can take a while; Settings shows this instead of the buttons.
        backupBusy = if (requestCode == REQ_BACKUP) "Backing up…" else "Restoring…"
        render()
        Thread {
            val msg = try {
                if (requestCode == REQ_BACKUP) {
                    val s = contentResolver.openOutputStream(uri)!!.use { Backup.write(app, it, withAudio, withKeys) }
                    "Backed up ${s.entries} dictations" + (if (s.recordings > 0) " and ${s.recordings} recordings" else "") +
                        (if (s.keys) " (with API keys)" else "")
                } else {
                    val s = contentResolver.openInputStream(uri)!!.use { Backup.restore(app, it) }
                    "Restored ${s.entries} dictations" + (if (s.recordings > 0) " and ${s.recordings} recordings" else "") +
                        (if (!s.keys) ". Your existing API keys were kept." else "")
                }
            } catch (e: Exception) {
                "Failed: ${e.message}"
            }
            runOnUiThread {
                backupBusy = null
                if (!isDestroyed) applyTheme() // a restore can change the theme along with everything else
                Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
                if (requestCode == REQ_RESTORE) Sync.request(app)
            }
        }.start()
    }

    // ---------- sync file (optional; see Sync.kt) ----------

    /** "Setting up…" while a newly picked sync file gets its first sync, else null. */
    var syncSetup: String? = null
        private set

    fun createSyncFile() {
        if (syncSetup != null) return
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, SyncFormat.FILE_NAME)
                .addFlags(SYNC_FILE_FLAGS), REQ_SYNC_CREATE
        )
    }

    fun openSyncFile() {
        if (syncSetup != null) return
        // Any type: cloud folders don't agree on what a .json file is.
        @Suppress("DEPRECATION")
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                .addFlags(SYNC_FILE_FLAGS), REQ_SYNC_OPEN
        )
    }

    private fun useSyncFile(uri: Uri) {
        if (syncSetup != null) return
        if (!Sync.keepPermission(this, uri)) {
            Toast.makeText(this, "Tokalot can't keep access to that file. Try another folder.", Toast.LENGTH_LONG).show()
            return
        }
        syncSetup = "Setting up…"
        render()
        val app = applicationContext
        Sync.adopt(app, uri) { error, _ ->
            syncSetup = null
            Toast.makeText(app, error ?: "Sync is on", Toast.LENGTH_LONG).show()
            if (!isDestroyed) render()
        }
    }

    fun syncNow() {
        SettingsScreen.syncStatusView?.apply { text = "Syncing…"; setTextColor(C.SUB) }
        Sync.request(this)
    }

    fun stopSync() {
        Sync.stop(this)
        render()
    }

    /** A sync ended. Only a change to what's on screen redraws it; otherwise just the status line moves. */
    private fun syncFinished(changedLocal: Boolean) {
        if (changedLocal || (inSettings && prefs.syncBroken != SettingsScreen.syncShownBroken)) render()
        else if (inSettings) SettingsScreen.showSyncStatus(prefs)
    }

    // ---------- accessibility disclosure ----------

    /** Plain-language explanation shown before sending the user to Android's accessibility settings. */
    fun openAccessibilitySetup() {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
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
        body.addView(link("github.com/${Updater.REPO}", 15f) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${Updater.REPO}")))
        })
        body.addView(text("On the next screen: tap Tokalot › turn it on › Allow. If it's greyed out, open this phone's Settings › Apps › Tokalot › ⋮ › Allow restricted settings first.", 14f, C.SUB), lp().margins(this, t = 4, b = 8))
        sheet("Before you turn this on", body, positive = "Continue", negative = "Not now") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    // ---------- permissions ----------

    private var permissionAskedAt = 0L

    /** Asks for a runtime permission; if Android no longer shows its dialog, opens the app's settings page instead. */
    fun askPermission(permission: String, code: Int) {
        permissionAskedAt = SystemClock.elapsedRealtime()
        requestPermissions(arrayOf(permission), code)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        val permission = perms.firstOrNull()
        val denied = results.firstOrNull() == PackageManager.PERMISSION_DENIED
        // After "Don't allow" twice, Android answers "denied" instantly without showing anything,
        // which made the Allow button look dead. An answer this fast can't have come from a person.
        val instant = SystemClock.elapsedRealtime() - permissionAskedAt < 600
        if (permission != null && denied && instant && !shouldShowRequestPermissionRationale(permission)) {
            toast("Allow it under Permissions on the next screen")
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        render()
    }

    override fun onPause() {
        super.onPause()
        History.onChange = null
        Sync.onDone = null
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

    // ---------- transcribing a saved recording again ----------

    /**
     * Runs a failed or cancelled recording through the pipeline again. There's no text field
     * to type into here, so the result replaces the history entry and goes to the clipboard.
     */
    private fun retry(e: Entry) {
        if (retryingId != null) return
        retryingId = e.id
        if (playingId == e.id) stopPlayback(redraw = false)
        render()
        val app = applicationContext
        Thread {
            val samples = runCatching { AudioStore.decode(AudioStore.file(app, e.id)) }.getOrNull()
            runOnUiThread {
                if (retryingId != e.id) return@runOnUiThread // cancelled while the file was being read
                if (samples == null || samples.isEmpty()) {
                    retryingId = null
                    toast("Couldn't read that recording")
                    if (!isDestroyed) render()
                    return@runOnUiThread
                }
                val d = retryDictation ?: Dictation(app).also { retryDictation = it }
                d.process(samples, e.app.ifEmpty { null }, retry = e) { outcome, err ->
                    retryingId = null
                    val msg = when {
                        err == Dictation.CANCELLED -> "Cancelled"
                        err != null -> err
                        outcome == null || outcome.text.isEmpty() -> "Didn't catch anything"
                        else -> {
                            (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                                .setPrimaryClip(ClipData.newPlainText("Tokalot", outcome.text))
                            outcome.warning ?: "Transcribed and copied"
                        }
                    }
                    Toast.makeText(app, msg, Toast.LENGTH_LONG).show()
                    if (!isDestroyed && !inSettings && tab == Tab.HOME) render()
                }
            }
        }.start()
    }

    private fun cancelRetry() {
        val d = retryDictation
        retryingId = null // covers the moment before the pipeline has started
        d?.cancel()
        render()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            inSettings -> { inSettings = false; render() }
            tab != Tab.HOME -> { tab = Tab.HOME; render() }
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    /** Opens Settings, with [category] (one of SettingsScreen.CATEGORIES) opened if given. */
    fun openSettings(category: String? = null) {
        if (category != null) prefs.openSettings = prefs.openSettings + category
        inSettings = true
        render()
    }

    /** Rebuilds the visible screen. Screens are cheap to build, so we just redraw. */
    fun render() {
        if (tab == Tab.NOTES && !prefs.notesBeta) tab = Tab.HOME // the beta was switched off
        logoBars?.accentColor = C.visible(prefs.accent) // follows the button color picked in Settings
        headerLeft.removeAllViews()
        val leftIcon = if (inSettings) R.drawable.ic_back else R.drawable.ic_menu
        headerLeft.addView(icon(leftIcon, 28).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = FrameLayout.LayoutParams(dp(48), dp(48))
            contentDescription = if (inSettings) "Back" else "Settings"
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
            Tab.NOTES -> NotesScreen(this).build()
        }
        content.addView(screen)
        if (prevScroll > 0 && screen is ScrollView) screen.post { screen.scrollTo(0, prevScroll) }
    }

    private var lastScreen = ""

    private fun renderNav() {
        nav.removeAllViews()
        nav.visibility = if (inSettings) View.GONE else View.VISIBLE
        for (t in Tab.values()) {
            if (t == Tab.NOTES && !prefs.notesBeta) continue
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
                contentDescription = if (active) "${t.label}, selected" else t.label
                setOnClickListener { tab = t; render() }
            }
            nav.addView(item, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    // ---------- shared helpers for screens ----------

    /**
     * Words the user spelled out while dictating, each with Add (to the dictionary) and Not now. Null when
     * there are none. Shown on Home and on the Dictionary page.
     */
    fun suggestionsCard(): View? {
        val words = prefs.suggestedWords
        if (words.isEmpty()) return null
        val c = card(18)
        c.addView(text(if (words.size == 1) "You spelled out a word" else "You spelled out ${words.size} words", 16f, bold = true))
        c.addView(text("Add it to your dictionary so it's spelled right without spelling it next time.", 14f, C.SUB), lp().margins(this, t = 2, b = 6))
        for (w in words) {
            c.addView(row(
                text(w, 16f).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) },
                pill("Not now") {
                    prefs.suggestedWords = prefs.suggestedWords - w
                    prefs.dismissedWords = prefs.dismissedWords + w
                    render()
                },
                spacer(wDp = 8),
                pill("Add", filled = true) {
                    prefs.suggestedWords = prefs.suggestedWords - w
                    if (prefs.words.none { it.equals(w, ignoreCase = true) }) prefs.words = prefs.words + w
                    toast("Added “$w” to your dictionary")
                    render()
                },
            ))
        }
        return c
    }

    /** The changes in a newer release [r], for the update banner. */
    private fun whatsNew(r: Updater.Release): View {
        val entries = Updater.whatsNew(this, r)
        if (entries.isNotEmpty()) return changeList(entries, compact = true, limit = 3)
        // A release from before the changelog, or it couldn't be fetched: the release's own text, or a link to it.
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val body = Updater.releaseText(r)
        if (body.isNotEmpty()) box.addView(text(body.take(1200), 14f, C.SUB))
        box.addView(link("See Tokalot ${r.version} on GitHub") {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.releasePage(r.version))))
        })
        return box
    }

    /**
     * Changelog entries as a list: each version, then its changes as bullets. [compact] is for the
     * update banner: smaller, and the version line only when there's more than one. Only the newest
     * [limit] versions are shown; the rest are a link away, on GitHub.
     */
    fun changeList(entries: List<Changelog.Entry>, compact: Boolean = false, limit: Int = 4): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val showVersion = !compact || entries.size > 1
        entries.take(limit).forEachIndexed { i, e ->
            if (showVersion) {
                val title = "Tokalot ${e.version}" + (e.date?.let { "  ·  ${prettyDate(it)}" } ?: "")
                box.addView(text(title, if (compact) 14f else 16f, bold = true), lp().margins(this, t = if (i == 0) 0 else 14, b = 4))
            }
            for (item in e.items) {
                box.addView(row(
                    text("•", 14f, C.SUB).apply { setPadding(0, 0, dp(8), 0) },
                    text(item, 14f, C.SUB).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) },
                ).apply { gravity = Gravity.TOP }, lp().margins(this, b = 4))
            }
        }
        if (entries.size > limit || !compact) {
            box.addView(link(if (entries.size > limit) "Older versions on GitHub" else "Full history on GitHub") {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.CHANGELOG_PAGE)))
            })
        }
        return box
    }

    /** "2026-10-08" as "8 Oct 2026"; anything else as it is. */
    private fun prettyDate(iso: String): String = runCatching {
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)!!)
    }.getOrDefault(iso)

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

    /** The search box stays the same view across redraws so typing isn't interrupted. */
    private fun searchBox(): EditText = searchField ?: field("Search your dictations", query).apply {
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
        searchField = this
    }

    private fun buildHome(): View {
        val col = column()

        // Subtle "new version" banner.
        Updater.available(this)?.let { r ->
            val pct = Updater.progress
            val msg = if (pct != null) "Downloading Tokalot ${r.version}… $pct%" else "Tokalot ${r.version} is available"
            val label = text(msg, 15f)
            bannerText = label
            // A quiet "What's new" under the label opens the list of changes inside the banner.
            val notes = whatsNew(r).apply {
                visibility = if (bannerOpen) View.VISIBLE else View.GONE
                setPadding(0, dp(4), dp(14), dp(10))
            }
            val chevron = icon(R.drawable.ic_expand, 18, C.SUB).apply { rotation = if (bannerOpen) 180f else 0f }
            val toggle = row(text("What's new", 13f, C.SUB), chevron).apply {
                minimumHeight = dp(32)
                contentDescription = "What's new in Tokalot ${r.version}"
                setOnClickListener {
                    bannerOpen = !bannerOpen
                    notes.visibility = if (bannerOpen) View.VISIBLE else View.GONE
                    chevron.animate().rotation(if (bannerOpen) 180f else 0f).setDuration(160).start()
                }
            }
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(label)
                addView(toggle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val top = row(
                texts,
                if (pct == null) pill("Update", filled = true) { startUpdate(r) } else spacer(),
                if (pct == null) iconButton(R.drawable.ic_close, "Dismiss", 20, C.SUB) {
                    Updater.dismiss(this@MainActivity, r.version); render()
                } else spacer()
            )
            val banner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(C.CARD, 18)
                setPadding(dp(18), dp(6), dp(4), dp(2))
                addView(top)
                addView(notes)
            }
            col.addView(banner, lp().margins(this, b = 12))
        }

        suggestionsCard()?.let { col.addView(it, lp().margins(this, b = 12)) }

        if (!setupComplete()) {
            val c = card(20)
            c.addView(heading("Finish setup", 28f))
            c.addView(text("Tokalot needs the microphone, the accessibility switch, and a Groq key (or the offline model) before the mic button will appear.", 15f, C.SUB).apply {
                setPadding(0, dp(6), 0, dp(14))
            })
            c.addView(row(pill("Open setup", filled = true) { openSettings(SettingsScreen.SETUP) }))
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
        // Speaking pace: words heard per minute of recording this month. (Cost lives in Settings › Usage.)
        val monthStart = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.DAY_OF_MONTH, 1); set(java.util.Calendar.HOUR_OF_DAY, 0)
            set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val spoken = History.all(this).filter { it.time >= monthStart && it.durationMs > 0 && it.raw.isNotEmpty() && !it.pending }
        val minutes = spoken.sumOf { it.durationMs } / 60000.0
        val pace = if (minutes > 0) Math.round(spoken.sumOf { TextTools.wordCount(it.raw) } / minutes).toString() else "–"
        statRow.addView(stat(pace, "words a minute"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        stats.addView(statRow)
        if (m.fillers + m.corrections > 0) {
            stats.addView(text("Cleaned up ${m.fillers} filler word${if (m.fillers == 1) "" else "s"} and ${m.corrections} self-correction${if (m.corrections == 1) "" else "s"}", 13f, C.SUB).apply {
                setPadding(0, dp(10), 0, 0)
            })
        }
        stats.setOnClickListener { openSettings(SettingsScreen.DATA) }
        col.addView(stats, lp().margins(this, b = 16))

        val search = searchBox()
        (search.parent as? ViewGroup)?.removeView(search)
        col.addView(search, lp())

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
                if (q.isEmpty()) "Nothing yet. Tap any text field in any app and hit the button." else "Nothing matches “$query”.",
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
            setPadding(dp(20), dp(20), dp(20), dp(14))
        }
        val isOpen = e.id in expanded
        // An entry without a transcript (failed or cancelled) shows its status in the quieter color.
        val body = text(e.text, 18f, if (e.pending) C.SUB else C.TEXT).apply {
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
        box.addView(text(meta, 14f, C.SUB), lp().margins(this, t = 10, b = 8))

        val hasAudio = AudioStore.exists(this, e.id)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        if (e.pending) {
            // The recording was kept: run it through speech-to-text again.
            when {
                retryingId == e.id -> actions.addView(pill("Transcribing… Cancel", filled = true) { cancelRetry() })
                hasAudio -> actions.addView(pill("Transcribe", filled = true) { retry(e) })
            }
        } else {
            actions.addView(pill("Copy", R.drawable.ic_copy) { copy(e.text) })
        }
        if (hasAudio) {
            val playing = playingId == e.id
            if (actions.childCount > 0) actions.addView(spacer(wDp = 8))
            actions.addView(pill(
                null, if (playing) R.drawable.ic_stop else R.drawable.ic_play, filled = playing,
                desc = if (playing) "Stop playback" else "Play recording"
            ) {
                if (playing) stopPlayback() else play(e.id)
            })
        }
        if ((e.cleaned || e.own) && e.raw.isNotBlank() && e.raw != e.text) {
            actions.addView(spacer(wDp = 8))
            actions.addView(pill("Original", filled = e.id in showOriginal) { toggle(showOriginal, e.id) })
        }
        actions.addView(weightSpacer())
        lateinit var more: View
        more = pill(null, R.drawable.ic_more, desc = "More") {
            val items = ArrayList<Pair<String, () -> Unit>>()
            if (!e.pending) items.add("Copy original" to { copy(e.raw.ifBlank { e.text }) })
            items.add("Delete" to {
                if (playingId == e.id) stopPlayback(redraw = false)
                if (retryingId == e.id) cancelRetry()
                History.delete(this@MainActivity, e.id)
                AudioStore.delete(this@MainActivity, e.id)
                render()
            })
            popupMenu(more, items)
        }
        actions.addView(more)
        box.addView(actions)
        return box
    }

    private fun toggle(set: HashSet<Long>, id: Long) {
        if (!set.add(id)) set.remove(id)
        render()
    }

    fun dayLabel(t: Long): String {
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
        /** Which page to open (a [Tab] name). */
        const val EXTRA_TAB = "com.tokalot.app.TAB"
        private const val REQ_BACKUP = 41
        private const val REQ_RESTORE = 42
        private const val REQ_SYNC_CREATE = 43
        private const val REQ_SYNC_OPEN = 44
        private const val SYNC_FILE_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION

        /** "Backing up…" / "Restoring…" while one runs in the background, else null. Outlives a redraw of the screen. */
        @Volatile var backupBusy: String? = null

        // A retry keeps running if the screen is rebuilt, so its state lives here (main thread only).
        private var retryDictation: Dictation? = null
        private var retryingId: Long? = null

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
