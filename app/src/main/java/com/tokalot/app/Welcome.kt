package com.tokalot.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * The first-run welcome, in two steps: pick a privacy level (each lists what it turns on, and every switch can be
 * changed right there), then the setup that level needs: the mic, the accessibility switch, and a key or the
 * on-device model. Finishing or skipping it marks it done; anything left over is on Home's Finish setup card.
 * Shown once, on a new install. Someone who updated keeps their settings and gets one Home card about the
 * levels instead ([privacyCard]).
 */
class WelcomeScreen(private val a: MainActivity) {
    companion object {
        /** 0: pick a level, 1: setup. Kept while the app runs. */
        var step = 0

        /** Whether to show the welcome now. On an update it's marked done straight away, so it never shows. */
        fun due(a: MainActivity): Boolean {
            val p = a.prefs
            if (p.welcomeDone) return false
            val info = runCatching { a.packageManager.getPackageInfo(a.packageName, 0) }.getOrNull()
            val freshInstall = info != null && info.firstInstallTime == info.lastUpdateTime
            if (!freshInstall) { p.welcomeDone = true; return false }
            // A new install starts on Everything on, the level picked when the welcome opens.
            if (!p.welcomeStarted) { p.welcomeStarted = true; Privacy.apply(a, p, Privacy.Level.FULL) }
            return true
        }

        fun done(a: MainActivity) {
            a.prefs.welcomeDone = true
            a.prefs.privacyCardSeen = true // they've just picked a level; no need to introduce levels again
            step = 0
            a.render()
        }

        /** Back on the welcome: from setup to the levels. False on the first step (Android closes the app). */
        fun back(a: MainActivity): Boolean {
            if (step == 0) return false
            step = 0
            a.render()
            return true
        }
    }

    fun build(): View = with(a) {
        val col = column()
        if (step == 0) levels(col) else setup(col)
        scroll(col)
    }

    private fun levels(col: LinearLayout) = with(a) {
        col.addView(heading("Welcome to Tokalot", 34f), lp().margins(this, t = 8, b = 6))
        col.addView(text("How private should it be? Pick a level, and change any switch below if you like. You can change all of this later in Settings › Privacy.", 16f, C.SUB), lp().margins(this, b = 16))
        val level = Privacy.current(prefs)
        val lv = card()
        Privacy.Level.values().forEachIndexed { i, l ->
            if (i > 0) lv.addView(divider())
            lv.addView(choiceRow(l.label, l.blurb, level == l) {
                if (level != l) { Privacy.apply(this, prefs, l); render() }
            })
        }
        if (level == null) {
            lv.addView(divider())
            lv.addView(choiceRow("Custom", "Your own mix of the switches below.", true) {})
        }
        col.addView(lv)

        col.addView(text("What's on", 14f, C.SUB), lp().margins(this, t = 20, b = 8, l = 4))
        val sw = card()
        Privacy.switches.forEachIndexed { i, s ->
            if (i > 0) sw.addView(divider())
            sw.addView(switchRow(s.title, s.note, s.isOn(prefs)) { on -> s.set(prefs, on); render() })
        }
        col.addView(sw)
        val keep = when (prefs.audioKeepDays) {
            0 -> "Recordings aren't saved."
            Int.MAX_VALUE -> "Recordings are kept until you delete them."
            else -> "Recordings are kept for ${prefs.audioKeepDays} days, so you can play one back or transcribe it again."
        }
        col.addView(text("$keep Change it in Settings › Privacy.", 13f, C.SUB), lp().margins(this, t = 8, l = 4))
        col.addView(text("Whatever you pick: no Tokalot account or server, your keys stay on this phone, and dictations go only to the services you choose.", 13f, C.SUB), lp().margins(this, t = 8, l = 4))
        col.addView(row(
            link("Skip setup", 15f) { done(this) },
            weightSpacer(),
            pill("Next", filled = true) { step = 1; render() },
        ), lp().margins(this, t = 20, b = 16))
    }

    private fun setup(col: LinearLayout) = with(a) {
        col.addView(heading("Set it up", 34f), lp().margins(this, t = 8, b = 6))
        col.addView(text("A few things Tokalot needs before the mic button can appear.", 16f, C.SUB), lp().margins(this, b = 16))
        val c = card()
        fun item(title: String, sub: String, done: Boolean, doneText: String, action: String?, onClick: () -> Unit): android.widget.TextView {
            if (c.childCount > 0) c.addView(divider())
            val status = text(if (done) doneText else sub, 14f, if (done) C.GOOD else C.SUB)
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(text(title, 17f))
                addView(status)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            c.addView(row(texts, if (done || action == null) spacer() else pill(action) { onClick() }).apply {
                setPadding(dp(20), dp(14), dp(16), dp(14))
            })
            return status
        }
        item("Microphone", "So Tokalot can hear you.", micGranted(), "Allowed", "Allow") {
            askPermission(Manifest.permission.RECORD_AUDIO, 1)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val ok = checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            item("Notifications", "Shows that Tokalot is recording.", ok, "Allowed", "Allow") {
                askPermission(Manifest.permission.POST_NOTIFICATIONS, 2)
            }
        }
        item("Accessibility switch", "Shows the mic button over your keyboard and types your words in.", serviceEnabled(), "On", "Set up") {
            openAccessibilitySetup()
        }
        if (prefs.stt == SttChoice.LOCAL) {
            val pct = SettingsScreen.downloadPct
            val ready = ModelManager.active(this)
            val m = prefs.localModel
            SettingsScreen.modelStatusView = item(
                "On-device model",
                SettingsScreen.downloadError?.let { "Failed: $it" } ?: if (pct != null) "Downloading $pct%" else "${m.label}. Speech to text runs on the phone.",
                ready != null, "${ready?.label} downloaded", if (pct != null) null else "Download",
            ) { SettingsScreen(this).startDownload(m) }
        }
        col.addView(c)

        // The keys the chosen services need, pasted right here.
        val services = listOfNotNull(prefs.stt.service, prefs.cleanup.service).distinct()
        for (id in services) {
            val name = Services.all.firstOrNull { it.first == id }?.second ?: id
            val page = Services.keyPages[id]
            val k = card(18)
            k.addView(text("$name key", 17f))
            k.addView(text(
                if (id == "groq") "Free, and covers most people. Make one at $page, then copy it and tap Paste."
                else "Make one at $page, then copy it and tap Paste.", 14f, C.SUB
            ), lp().margins(this, t = 2))
            if (page != null) k.addView(link("Open $page") {
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://$page"))) }
            }, lp().margins(this, t = 4))
            val saved = prefs.key(id).isNotEmpty()
            val status = text(if (saved) "Saved" else "", 14f, C.GOOD)
            k.addView(row(
                status.apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) },
                pill("Paste", filled = !saved) {
                    val clip = (getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).primaryClip
                    val pasted = Keys.clean(clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() ?: "")
                    val problem = if (pasted.isEmpty()) "Nothing to paste. Copy the key first, then tap Paste." else Keys.problem(id, pasted)
                    if (problem != null) {
                        status.text = problem
                        status.setTextColor(C.WARN)
                    } else {
                        prefs.setKey(id, pasted)
                        render()
                    }
                }
            ), lp().margins(this, t = 8))
            col.addView(k, lp().margins(this, t = 12))
        }
        col.addView(text("Anything you skip stays on Home under Finish setup.", 13f, C.SUB), lp().margins(this, t = 12, l = 4))
        col.addView(row(
            link("Back", 15f) { step = 0; render() },
            weightSpacer(),
            pill(if (setupComplete()) "Done" else "Finish later", filled = true) { done(this) },
        ), lp().margins(this, t = 20, b = 16))
    }
}

/**
 * For people who updated: one card on Home saying privacy levels are new and their settings didn't change.
 * Null once it's been seen or dismissed.
 */
fun MainActivity.privacyCard(): View? {
    if (prefs.privacyCardSeen) return null
    val c = card(20)
    c.addView(text("New: privacy levels", 17f))
    c.addView(text("Settings now has a Privacy page with three levels (Everything on, Balanced and Most private) and every privacy switch in one place. Your settings didn't change.", 14f, C.SUB), lp().margins(this, t = 4))
    c.addView(row(
        pill("Have a look", filled = true) { prefs.privacyCardSeen = true; openSettings(SettingsScreen.PRIVACY) },
        spacer(wDp = 8),
        pill("Dismiss") { prefs.privacyCardSeen = true; render() },
    ), lp().margins(this, t = 10))
    return c
}
