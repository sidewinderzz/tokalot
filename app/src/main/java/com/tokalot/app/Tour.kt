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
    const val VERSION = "1.27"

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

    private fun slides(a: MainActivity) = listOf(
        Slide(
            "Fits into the sentence",
            "Dictate into the middle of a sentence and it fits in: no capital at the start, no period when the sentence carries on.",
            { fitPicture() },
        ),
        Slide(
            "Spell it once",
            "Spell a name out while you talk and it's written once, spelled right. Tokalot then offers to add it to your dictionary.",
            { spelledPicture() },
        ),
        Slide(
            "No text box needed",
            "Add the Dictate tile to Quick Settings, or hold both volume keys (Android 11+), and talk while you read or scroll. With no keyboard up, the text goes on the clipboard.",
            { tilePicture() },
        ),
        Slide(
            "Voice notes (beta)",
            "Save what you say as a note instead: from the Voice note tile, or start any dictation with \"Note this\". Off until you turn it on.",
            { notePicture() },
            "Turn it on" to { a.openSettings(SettingsScreen.RECORDING) },
        ),
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

    private fun MainActivity.fitPicture(): View {
        return stage(
            box(span("I want " to 0, "T" to 2, "the blue one" to 1, "." to 2, " and the red one." to 0), "Before"),
            box(span("I want " to 0, "the blue one" to 1, " and the red one." to 0), "Now"),
        )
    }

    private fun MainActivity.spelledPicture(): View {
        val chip = row(
            text("Kowalski", 15f, bold = true).apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) },
            text("Add", 14f, C.CARD).apply {
                background = rounded(C.TEXT, 100)
                setPadding(dp(14), dp(5), dp(14), dp(5))
            },
        ).apply {
            background = rounded(C.CARD, 12, C.LINE, 1)
            setPadding(dp(12), dp(7), dp(8), dp(7))
        }
        return stage(
            text("“Ask Kowalski, K-O-W-A-L-S-K-I, about the report”", 14f, C.SUB).apply { gravity = Gravity.CENTER },
            box(span("Ask " to 0, "Kowalski" to 1, " about the report." to 0), "Typed"),
            chip,
        )
    }

    private fun MainActivity.tilePicture(): View {
        fun tile(iconRes: Int, label: String, on: Boolean) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(if (on) C.TEXT else C.CARD, 100)
            setPadding(dp(14), dp(10), dp(18), dp(10))
            addView(icon(iconRes, 20, if (on) C.CARD else C.TEXT))
            addView(text(label, 14f, if (on) C.CARD else C.TEXT).apply { setPadding(dp(8), 0, 0, 0) })
        }
        val tiles = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(tile(R.drawable.ic_mic, "Dictate", true))
            addView(spacer(wDp = 8))
            addView(tile(R.drawable.ic_note, "Voice note", false))
        }
        val copied = text("Copied ✓  The text is on your clipboard", 13f, Color.parseColor("#E2E2E6")).apply {
            background = rounded(Color.parseColor("#E6404044"), 8)
            setPadding(dp(12), dp(7), dp(12), dp(7))
            gravity = Gravity.CENTER
        }
        return stage(tiles, text("or hold both volume keys", 13f, C.SUB).apply { gravity = Gravity.CENTER }, copied)
    }

    private fun MainActivity.notePicture(): View {
        val note = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(C.CARD, 14, C.LINE, 1)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addView(text("TODAY · 9:41 AM", 11f, C.SUB, bold = true).apply { letterSpacing = 0.08f })
            addView(text(span("Call Joe about the invoice." to 1), 15f), lp().margins(this@notePicture, t = 3))
        }
        return stage(
            text("“Note this, call Joe about the invoice”", 14f, C.SUB).apply { gravity = Gravity.CENTER },
            note,
            text("Saved to Notes · copied", 13f, C.SUB).apply { gravity = Gravity.CENTER },
        )
    }
}
