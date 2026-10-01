package com.tokalot.app

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.pow

/** Colors and small view builders shared by every screen. Plain Android views, no libraries. */
object C {
    var dark = false
        private set
    var BG = 0; var CARD = 0; var TEXT = 0; var SUB = 0; var LINE = 0; var PILL = 0
    var NAV_ACTIVE = 0; var FIELD = 0; var GOOD = 0; var WARN = 0; var LINK = 0; var RIPPLE = 0

    init { apply(false) }

    /** Switches every color the UI builders use. Screens are rebuilt after this. */
    fun apply(dark: Boolean) {
        this.dark = dark
        if (dark) {
            BG = 0xFF0E0E10.toInt(); CARD = 0xFF1C1C1E.toInt(); TEXT = 0xFFF2F2F7.toInt(); SUB = 0xFF98989F.toInt()
            LINE = 0xFF2C2C2E.toInt(); PILL = 0xFF3A3A3C.toInt(); NAV_ACTIVE = 0xFF2C2C2E.toInt(); FIELD = 0xFF2C2C2E.toInt()
            GOOD = 0xFF66BB6A.toInt(); WARN = 0xFFFFB74D.toInt(); LINK = 0xFF6EA8FE.toInt(); RIPPLE = 0x33FFFFFF
        } else {
            // SUB is dark enough for 4.5:1 on a white card (WCAG AA for small text).
            BG = 0xFFEFEFF1.toInt(); CARD = 0xFFFFFFFF.toInt(); TEXT = 0xFF1C1C1E.toInt(); SUB = 0xFF6B6B70.toInt()
            LINE = 0xFFE4E4E7.toInt(); PILL = 0xFFCDCDD2.toInt(); NAV_ACTIVE = 0xFFDEDEE2.toInt(); FIELD = 0xFFF4F4F6.toInt()
            GOOD = 0xFF2E7D32.toInt(); WARN = 0xFFB26A00.toInt(); LINK = 0xFF2F6FDB.toInt(); RIPPLE = 0x22000000
        }
    }

    /** WCAG relative luminance of an RGB color, 0 (black) to 1 (white). */
    fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val c = ((color shr shift) and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    /** WCAG contrast ratio between two colors, 1 to 21. */
    fun contrast(a: Int, b: Int): Double {
        val hi = maxOf(luminance(a), luminance(b))
        val lo = minOf(luminance(a), luminance(b))
        return (hi + 0.05) / (lo + 0.05)
    }

    /**
     * The accent as it should be drawn on the app's own surfaces: a near-white accent would
     * vanish on the light theme's white cards, so there it becomes the text color instead.
     */
    fun visible(accent: Int): Int = if (!dark && luminance(accent) > 0.85) TEXT else accent
}

object Fonts {
    private var serif: Typeface? = null
    fun serif(ctx: Context): Typeface = serif ?: runCatching {
        Typeface.createFromAsset(ctx.assets, "fonts/serif.ttf")
    }.getOrDefault(Typeface.SERIF).also { serif = it }
}

/** Android's minimum comfortable touch target, in dp. */
const val TOUCH_DP = 48

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Int) = v * resources.displayMetrics.density

fun Context.rounded(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dpf(radiusDp)
    if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
}

/** A background that shows a touch ripple over [base]. */
fun Context.pressable(base: GradientDrawable) =
    RippleDrawable(ColorStateList.valueOf(C.RIPPLE), base, base)

fun Context.text(s: CharSequence, sizeSp: Float = 16f, color: Int = C.TEXT, bold: Boolean = false) = TextView(this).apply {
    text = s
    textSize = sizeSp
    setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
    setLineSpacing(0f, 1.15f)
}

/** Tappable link-colored text, padded out to a full-size touch target. */
fun Context.link(s: CharSequence, sizeSp: Float = 14f, onClick: () -> Unit) = text(s, sizeSp, C.LINK).apply {
    minHeight = dp(TOUCH_DP)
    gravity = Gravity.CENTER_VERTICAL
    setOnClickListener { onClick() }
}

fun Context.heading(s: String, sizeSp: Float = 40f) = TextView(this).apply {
    text = s
    textSize = sizeSp
    setTextColor(C.TEXT)
    typeface = Fonts.serif(this@heading)
    includeFontPadding = true
}

fun Context.icon(res: Int, sizeDp: Int = 22, tint: Int = C.TEXT) = ImageView(this).apply {
    setImageResource(res)
    setColorFilter(tint)
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

/**
 * An icon that is a button on its own: the glyph stays [sizeDp] but the tappable area is a
 * full 48dp square, and [desc] is what a screen reader says for it.
 */
fun Context.iconButton(res: Int, desc: String, sizeDp: Int = 20, tint: Int = C.TEXT, onClick: () -> Unit) = ImageView(this).apply {
    setImageResource(res)
    setColorFilter(tint)
    val pad = dp((TOUCH_DP - sizeDp) / 2)
    setPadding(pad, pad, pad, pad)
    layoutParams = LinearLayout.LayoutParams(dp(TOUCH_DP), dp(TOUCH_DP))
    background = RippleDrawable(ColorStateList.valueOf(C.RIPPLE), null, null)
    contentDescription = desc
    setOnClickListener { onClick() }
}

/**
 * A switch drawn from the app palette instead of the stock Android tint, which comes out
 * almost the same color as the card in dark mode (so the switch vanished when on).
 * On = accent-colored track; off = neutral track. The thumb flips dark/white so it stays
 * visible against any accent, including white.
 */
fun Context.themedSwitch(on: Boolean, accent: Int): android.widget.Switch {
    val trackW = dp(52); val trackH = dp(32); val inset = dp(4)
    fun track(color: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = trackH / 2f; setSize(trackW, trackH)
    }
    fun thumb(color: Int) = InsetDrawable(
        GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setSize(trackH - inset * 2, trackH - inset * 2) },
        inset
    )
    // A white accent on the light theme would be a white track on a white card.
    val trackOn = C.visible(accent)
    val lightTrack = (0.299 * Color.red(trackOn) + 0.587 * Color.green(trackOn) + 0.114 * Color.blue(trackOn)) / 255.0 > 0.6
    val thumbOn = if (lightTrack) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
    val thumbOff = if (C.dark) 0xFFB5B5BA.toInt() else 0xFFFFFFFF.toInt()
    val trackOff = if (C.dark) 0xFF48484A.toInt() else 0xFFC7C7CC.toInt()

    return android.widget.Switch(this).apply {
        trackDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), track(trackOn))
            addState(intArrayOf(), track(trackOff))
        }
        thumbDrawable = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), thumb(thumbOn))
            addState(intArrayOf(), thumb(thumbOff))
        }
        showText = false
        isChecked = on
    }
}

/**
 * Outlined pill button, used for Copy / Play / Original and similar actions.
 * The pill is drawn 4dp short of the view at top and bottom, so it looks the same size as
 * before while the tappable area is a full 48dp. Icon-only pills need [desc] for screen readers.
 */
fun Context.pill(
    label: String?, iconRes: Int? = null, filled: Boolean = false, desc: String? = null, onClick: () -> Unit,
): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        val gap = dp(4)
        background = InsetDrawable(
            pressable(if (filled) rounded(C.TEXT, 100) else rounded(C.CARD, 100, C.PILL, 1)),
            0, gap, 0, gap
        )
        val hp = if (label == null) dp(14) else dp(16)
        setPadding(hp, dp(10) + gap, hp, dp(10) + gap)
        minimumHeight = dp(TOUCH_DP)
        minimumWidth = dp(TOUCH_DP)
        val fg = if (filled) C.CARD else C.TEXT
        if (iconRes != null) addView(icon(iconRes, 20, fg))
        if (label != null) {
            addView(text(label, 15f, fg).apply {
                if (iconRes != null) setPadding(dp(8), 0, 0, 0)
            })
        }
        if (desc != null) contentDescription = desc
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

/** White rounded card; children are stacked vertically. */
fun Context.card(padDp: Int = 0) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    background = rounded(C.CARD, 28)
    clipToOutline = true
    setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
}

fun Context.divider() = View(this).apply {
    setBackgroundColor(C.LINE)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
}

fun Context.field(hint: String, value: String = "", multiLine: Boolean = false, secret: Boolean = false) = EditText(this).apply {
    this.hint = hint
    setText(value)
    textSize = 16f
    setTextColor(C.TEXT)
    setHintTextColor(C.SUB)
    background = rounded(C.FIELD, 14)
    setPadding(dp(14), dp(12), dp(14), dp(12))
    inputType = when {
        secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        multiLine -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        else -> InputType.TYPE_CLASS_TEXT
    }
    if (multiLine) { minLines = 3; gravity = Gravity.TOP or Gravity.START }
}

fun Context.row(vararg views: View) = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    views.forEach { addView(it) }
}

fun Context.spacer(wDp: Int = 0, hDp: Int = 0) = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(dp(wDp), dp(hDp))
}

fun Context.weightSpacer() = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
}

fun lp(w: Int = ViewGroup.LayoutParams.MATCH_PARENT, h: Int = ViewGroup.LayoutParams.WRAP_CONTENT) =
    LinearLayout.LayoutParams(w, h)

fun LinearLayout.LayoutParams.margins(ctx: Context, l: Int = 0, t: Int = 0, r: Int = 0, b: Int = 0) = apply {
    setMargins(ctx.dp(l), ctx.dp(t), ctx.dp(r), ctx.dp(b))
}

// ---------- dialogs and menus, drawn as the app's own cards so they follow light/dark ----------

/**
 * The one dialog everything else is built on: an optional serif title, a scrolling body and
 * up to three pill buttons. Any button closes it. Needs an Activity context.
 */
fun Context.sheet(
    title: String?, body: View?,
    positive: String? = null, negative: String? = "Cancel", neutral: String? = null,
    onNeutral: () -> Unit = {}, onYes: () -> Unit = {},
): Dialog {
    val d = Dialog(this)
    d.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val box = card().apply {
        background = rounded(C.CARD, 28, C.LINE, 1) // the hairline keeps it apart from a dark screen behind
        setPadding(dp(22), dp(22), dp(22), dp(12))
    }
    if (title != null) box.addView(heading(title, 26f), lp().margins(this, b = 10))
    if (body != null) {
        // Weighted so a long body scrolls instead of pushing the buttons off screen.
        box.addView(
            ScrollView(this).apply { addView(body) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
    }
    val buttons = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }
    if (neutral != null) {
        buttons.addView(pill(neutral) { d.dismiss(); onNeutral() })
        buttons.addView(weightSpacer())
    }
    if (negative != null) buttons.addView(pill(negative) { d.dismiss() })
    if (positive != null) {
        if (negative != null) buttons.addView(spacer(wDp = 8))
        buttons.addView(pill(positive, filled = true) { d.dismiss(); onYes() })
    }
    box.addView(buttons, lp().margins(this, t = 12))
    d.setContentView(box)
    d.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        setLayout(minOf(resources.displayMetrics.widthPixels - dp(40), dp(460)), ViewGroup.LayoutParams.WRAP_CONTENT)
        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    d.show()
    return d
}

/** "Are you sure?" with Cancel and one action. */
fun Context.confirm(message: String, positiveLabel: String, title: String? = null, onYes: () -> Unit): Dialog =
    sheet(title, text(message, 16f), positive = positiveLabel, onYes = onYes)

/** Read-only text with a single button to close it. */
fun Context.info(title: String, message: CharSequence, button: String = "OK"): Dialog =
    sheet(title, text(message, 14f, C.SUB), positive = button, negative = null)

/** Pick one from a short list; the current one has a filled dot. Picking closes the dialog. */
fun Context.choose(title: String, items: List<String>, selected: Int, onPick: (Int) -> Unit): Dialog {
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val d = sheet(title, list)
    items.forEachIndexed { i, label ->
        val dot = View(this).apply {
            background = if (i == selected) rounded(C.TEXT, 100) else rounded(C.CARD, 100, C.PILL, 2)
        }
        list.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(TOUCH_DP + 4)
            background = RippleDrawable(ColorStateList.valueOf(C.RIPPLE), null, ColorDrawable(Color.WHITE))
            addView(dot, LinearLayout.LayoutParams(dp(20), dp(20)))
            addView(
                text(label, 17f, bold = i == selected),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).margins(this@choose, l = 14)
            )
            setOnClickListener { d.dismiss(); onPick(i) }
        })
    }
    return d
}

/** A small menu that drops down from [anchor] (the "..." button). Tapping outside closes it. */
fun Context.popupMenu(anchor: View, items: List<Pair<String, () -> Unit>>) {
    val box = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(C.CARD, 18, C.PILL, 1)
        clipToOutline = true
        setPadding(0, dp(6), 0, dp(6))
    }
    val pop = PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
    items.forEach { (label, action) ->
        box.addView(text(label, 16f).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(TOUCH_DP)
            minWidth = dp(168)
            setPadding(dp(20), 0, dp(24), 0)
            background = RippleDrawable(ColorStateList.valueOf(C.RIPPLE), null, ColorDrawable(Color.WHITE))
            setOnClickListener { pop.dismiss(); action() }
        }, lp())
    }
    pop.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)) // also what makes outside taps dismiss it
    pop.isOutsideTouchable = true
    pop.showAsDropDown(anchor, 0, 0, Gravity.END)
}
