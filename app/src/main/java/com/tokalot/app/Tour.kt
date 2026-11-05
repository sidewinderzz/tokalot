package com.tokalot.app

import android.app.Dialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * "What's new": a few slides about this version's new features, shown once after updating to it (not on a
 * new install, where nothing is new) and again from Settings › About. The pictures are drawn here in the
 * app's own style, so they follow light and dark and never go out of date the way screenshots would.
 */
object Tour {
    /** The version these slides are about. Raise it (and change the slides) to show a new tour. */
    const val VERSION = "1.56"

    private fun sp(ctx: android.content.Context) = ctx.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)

    /** True once, after updating to [VERSION] from an older one. A fresh install skips it. */
    fun due(a: MainActivity): Boolean {
        val s = sp(a)
        if (s.getString("tour_seen", null) == VERSION) return false
        val info = runCatching { a.packageManager.getPackageInfo(a.packageName, PackageManager.GET_META_DATA) }.getOrNull()
        val freshInstall = info != null && info.firstInstallTime == info.lastUpdateTime
        if (freshInstall) { seen(a); return false }
        return true
    }

    fun seen(ctx: android.content.Context) = sp(ctx).edit().putString("tour_seen", VERSION).apply()

    private class Slide(val title: String, val text: String, val picture: MainActivity.() -> View, val action: Pair<String, () -> Unit>? = null)

    private fun slides(a: MainActivity) = listOfNotNull(
        Slide(
            "Shared messages land on Home",
            "Voice messages and text you share now show up on Home with your dictations. Tap Move to Notes to file one.",
            { homePicture() },
        ),
        Slide(
            "Stretch the speed button",
            "Press the speed button and drag up for faster, down for slower. Auto speed brings slow talkers up to the pace you pick.",
            { speedPicture() },
        ),
        Slide(
            "Copy what you need",
            "Copy text copies the whole note in one tap. The Summary and Timeline each have their own copy button too.",
            { copyPicture() },
        ),
        Slide(
            "Find any setting",
            "Settings has a search box that knows everyday words. Your note defaults have their own Notes page.",
            { searchPicture() },
        ),
        Slide(
            "Listen to articles (beta)",
            "Share a link, PDF or Word file to Listen in Tokalot and it plays like a voice note.",
            { listenPicture() },
            "Try it" to { a.toast("Open a web page, PDF or Word file, tap Share, then pick Listen in Tokalot.") },
        ),
        // Only for people who use the Tokalot keyboard.
        if (TokalotKeyboard.isEnabled(a)) Slide(
            "Smarter keyboard",
            "Autocorrect now fixes run-together words and common misspellings, and leaves texting words like omw alone.",
            { keysPicture() },
        ) else null,
    )

    /** Shows the slides; Next / Back and a swipe move between them, and closing marks them seen. */
    fun show(a: MainActivity) = with(a) {
        seen(this)
        val list = slides(this)
        var at = 0
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = card().apply {
            background = rounded(C.CARD, 28, C.LINE, 1)
            setPadding(dp(22), dp(22), dp(22), dp(14))
        }
        val picture = FrameLayout(this)
        val title = heading("", 26f)
        val body = text("", 15f, C.SUB)
        val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        box.addView(picture, lp().margins(this, b = 16))
        box.addView(title)
        box.addView(body, lp().margins(this, t = 6, b = 14))
        box.addView(dots, lp().margins(this, b = 8))
        box.addView(buttons)

        lateinit var draw: () -> Unit
        fun go(i: Int) { at = i.coerceIn(0, list.lastIndex); draw() }
        draw = {
            val s = list[at]
            picture.removeAllViews()
            picture.addView(s.picture(this), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)))
            title.text = s.title
            body.text = s.text
            dots.removeAllViews()
            list.indices.forEach { i ->
                dots.addView(View(this).apply {
                    background = rounded(if (i == at) C.TEXT else C.PILL, 100)
                }, LinearLayout.LayoutParams(dp(if (i == at) 18 else 7), dp(7)).margins(this, l = 3, r = 3))
            }
            dots.contentDescription = "Slide ${at + 1} of ${list.size}"
            buttons.removeAllViews()
            buttons.addView(pill(if (at == 0) "Skip" else "Back") { if (at == 0) d.dismiss() else go(at - 1) })
            buttons.addView(weightSpacer())
            s.action?.let { (label, act) ->
                buttons.addView(pill(label) { d.dismiss(); act() })
                buttons.addView(spacer(wDp = 8))
            }
            val last = at == list.lastIndex
            buttons.addView(pill(if (last) "Done" else "Next", filled = true) { if (last) d.dismiss() else go(at + 1) })
        }
        // A sideways swipe on the picture or text moves between slides too.
        val swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) < abs(vy) || abs(vx) < 400) return false
                go(if (vx < 0) at + 1 else at - 1)
                return true
            }
        })
        picture.setOnTouchListener { _, e -> swipe.onTouchEvent(e) }
        body.setOnTouchListener { _, e -> swipe.onTouchEvent(e) }

        draw()
        d.setContentView(box)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(minOf(resources.displayMetrics.widthPixels - dp(40), dp(460)), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        d.show()
    }

    // ---------- pictures, drawn from plain views ----------

    /** A grey "screen" the pictures sit on, so they read as part of a phone. */
    private fun MainActivity.stage(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = rounded(C.BG, 20, C.LINE, 1)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        views.forEach { addView(it, lp().margins(this@stage, t = 4, b = 4)) }
    }

    /** A text box with [content] in it. */
    private fun MainActivity.box(content: CharSequence, label: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(text(label.uppercase(), 11f, C.SUB, bold = true).apply { letterSpacing = 0.08f })
        addView(text(content, 15f).apply {
            background = rounded(C.FIELD, 12)
            setPadding(dp(12), dp(9), dp(12), dp(9))
        }, lp().margins(this@box, t = 3))
    }

    /** Text in parts: 0 = what was already there, 1 = what Tokalot typed, 2 = what it used to type and no longer does. */
    private fun MainActivity.span(vararg parts: Pair<String, Int>): CharSequence {
        val b = SpannableStringBuilder()
        for ((s, kind) in parts) {
            val start = b.length
            b.append(s)
            when (kind) {
                1 -> {
                    b.setSpan(ForegroundColorSpan(C.visible(prefs.accent)), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    b.setSpan(StyleSpan(Typeface.BOLD), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                2 -> {
                    b.setSpan(ForegroundColorSpan(C.WARN), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    b.setSpan(StrikethroughSpan(), start, b.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
        return b
    }

    private fun MainActivity.chip(label: String, on: Boolean = false) = text(label, 13f, if (on) C.CARD else C.TEXT).apply {
        background = if (on) rounded(C.TEXT, 100) else rounded(C.CARD, 100, C.LINE, 1)
        setPadding(dp(12), dp(6), dp(12), dp(6))
    }

    private fun MainActivity.chips(vararg c: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        c.forEachIndexed { i, v -> if (i > 0) addView(spacer(wDp = 6)); addView(v) }
    }

    /** A note card: a small caps label over one line of text. */
    private fun MainActivity.noteCard(label: String, body: CharSequence) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(C.CARD, 14, C.LINE, 1)
        setPadding(dp(12), dp(7), dp(12), dp(7))
        addView(text(label, 11f, C.SUB, bold = true).apply { letterSpacing = 0.06f })
        addView(text(body, 14f), lp().margins(this@noteCard, t = 2))
    }

    private fun MainActivity.homePicture(): View = stage(
        noteCard("SHARED · WHATSAPP · 8:12 PM", "“Running late, be there by six”"),
        noteCard("DICTATION · 7:40 PM", "Pick up milk on the way home"),
        chips(chip("Move to Notes", on = true)),
    )

    private fun MainActivity.speedPicture(): View {
        val wheel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(C.CARD, 22, C.LINE, 1)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            listOf("2.0×", "1.9×", "1.8×").forEach { addView(text(it, 12f, C.SUB).apply { gravity = Gravity.CENTER }) }
            addView(text("1.7×", 16f, bold = true).apply {
                gravity = Gravity.CENTER
                background = rounded((C.visible(prefs.accent) and 0x00FFFFFF) or 0x48000000, 100)
                setPadding(dp(10), dp(2), dp(10), dp(2))
            }, lp().margins(this@speedPicture, t = 2))
        }
        val side = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(chip("Auto speed: on"))
            addView(chip("Aim for 180 wpm"), lp().margins(this@speedPicture, t = 6))
        }
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(wheel); addView(spacer(wDp = 16)); addView(side)
        }
        return stage(r, text("Press and drag up, from 0.5× to 4×", 13f, C.SUB).apply { gravity = Gravity.CENTER })
    }

    private fun MainActivity.copyPicture(): View = stage(
        chips(chip("Summary"), chip("Timeline"), chip("⧉ Copy text", on = true)),
        text("Copied the whole note ✓", 14f).apply {
            gravity = Gravity.CENTER
            background = rounded(C.FIELD, 12)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        },
    )

    private fun MainActivity.searchPicture(): View = stage(
        text("🔍  slow talkers", 14f).apply { background = rounded(C.FIELD, 12); setPadding(dp(12), dp(8), dp(12), dp(8)) },
        noteCard("NOTES › PLAYBACK", "Auto speed"),
        noteCard("NOTES › PLAYBACK", "Default playback speed"),
    )

    private fun MainActivity.listenPicture(): View = stage(
        text("Share a link, PDF or Word file", 13f, C.SUB).apply { gravity = Gravity.CENTER },
        chips(chip("Copy link"), chip("Listen in Tokalot", on = true), chip("Save")),
        noteCard("READ ALOUD · 12 MIN", "Why we need more sleep"),
    )

    private fun MainActivity.keysPicture(): View = stage(
        box("ofthe teh omw", "You type"),
        box(span("of the" to 1, " " to 0, "the" to 1, " omw" to 0), "Keyboard fixes"),
    )
}
