package com.tokalot.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Colors and small view builders shared by every screen. Plain Android views, no libraries. */
object C {
    const val BG = 0xFFEFEFF1.toInt()
    const val CARD = 0xFFFFFFFF.toInt()
    const val TEXT = 0xFF1C1C1E.toInt()
    const val SUB = 0xFF8E8E93.toInt()
    const val LINE = 0xFFE4E4E7.toInt()
    const val PILL = 0xFFCDCDD2.toInt()
    const val NAV_ACTIVE = 0xFFDEDEE2.toInt()
    const val FIELD = 0xFFF4F4F6.toInt()
    const val GOOD = 0xFF2E7D32.toInt()
    const val WARN = 0xFFB26A00.toInt()
}

object Fonts {
    private var serif: Typeface? = null
    fun serif(ctx: Context): Typeface = serif ?: runCatching {
        Typeface.createFromAsset(ctx.assets, "fonts/serif.ttf")
    }.getOrDefault(Typeface.SERIF).also { serif = it }
}

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Int) = v * resources.displayMetrics.density

fun Context.rounded(color: Int, radiusDp: Int, strokeColor: Int = 0, strokeDp: Int = 0) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dpf(radiusDp)
    if (strokeDp > 0) setStroke(dp(strokeDp), strokeColor)
}

/** A background that shows a touch ripple over [base]. */
fun Context.pressable(base: GradientDrawable) =
    RippleDrawable(ColorStateList.valueOf(0x22000000), base, base)

fun Context.text(s: CharSequence, sizeSp: Float = 16f, color: Int = C.TEXT, bold: Boolean = false) = TextView(this).apply {
    text = s
    textSize = sizeSp
    setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
    setLineSpacing(0f, 1.15f)
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

/** Outlined pill button like "Copy" in Wispr Flow. */
fun Context.pill(label: String?, iconRes: Int? = null, filled: Boolean = false, onClick: () -> Unit): LinearLayout =
    LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        val hp = if (label == null) dp(12) else dp(16)
        setPadding(hp, dp(10), hp, dp(10))
        background = pressable(
            if (filled) rounded(C.TEXT, 100) else rounded(C.CARD, 100, C.PILL, 1)
        )
        val fg = if (filled) C.CARD else C.TEXT
        if (iconRes != null) addView(icon(iconRes, 20, fg))
        if (label != null) {
            addView(text(label, 15f, fg).apply {
                if (iconRes != null) setPadding(dp(8), 0, 0, 0)
            })
        }
        isClickable = true
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
