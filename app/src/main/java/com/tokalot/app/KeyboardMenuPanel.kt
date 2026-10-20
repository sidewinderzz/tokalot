package com.tokalot.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The keyboard's own menu, opened from the button at the left of the top bar (as Gboard's): the top bar's
 * buttons to reorder, remove and add, the look (light or dark, the key style), a few quick switches, and a
 * way to the rest of the keyboard's settings in the app. It takes the keyboard's height.
 */
@SuppressLint("ViewConstructor")
class KeyboardMenuPanel(context: Context, private val listener: Listener) : LinearLayout(context) {

    interface Listener {
        /** The top bar's buttons changed (already saved). */
        fun onMenuToolbar(list: List<String>)
        /** A setting changed (already saved): the keyboard applies it. */
        fun onMenuSetting()
        fun onMenuAllSettings()
        fun onMenuClose()
        fun onPress(code: Int)
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

    var theme = KeyboardTheme.light(Accents.DEFAULT)
        set(v) { field = v; if (isShown) build() }

    var panelHeight = 0
        set(v) { if (field != v) { field = v; requestLayout() } }

    private val list = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(12f), 0, dp(12f), dp(16f)) }
    private val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(list) }

    init { orientation = VERTICAL }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxOf(panelHeight, dp(260f)), MeasureSpec.EXACTLY))
    }

    fun show() {
        build()
        scroll.scrollTo(0, 0)
    }

    private fun prefs() = Prefs(context)

    private fun build() {
        val t = theme
        val p = prefs()
        removeAllViews()
        setBackgroundColor(t.background)

        val header = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(icon(R.drawable.ic_back, t.text).apply {
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            contentDescription = "Back to the keyboard"
            setOnClickListener { listener.onPress(0); listener.onMenuClose() }
        }, LayoutParams(dp(48f), dp(44f)))
        header.addView(label("Keyboard", 16f, t.text, bold = true), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        header.addView(label("All settings", 14f, t.accentText, bold = true).apply {
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            setOnClickListener { listener.onPress(0); listener.onMenuAllSettings() }
        })
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(44f)))

        list.removeAllViews()

        // --- The top bar's buttons
        heading("Top bar", "Arrows move a button, ✕ takes it off. The mic always stays.")
        val bar = KeyboardToolbar.parse(p.kbToolbar)
        for ((i, item) in bar.withIndex()) {
            val row = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = card()
                setPadding(dp(12f), 0, dp(4f), 0)
            }
            row.addView(icon(itemIcon(item), if (item == KeyboardToolbar.FIX) t.accentText else t.text), LayoutParams(dp(22f), dp(22f)))
            row.addView(label(KeyboardToolbar.label(item), 15f, t.text).apply { setPadding(dp(12f), 0, 0, 0) },
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            row.addView(button("◀", i > 0, "Move ${KeyboardToolbar.label(item)} left") { setBar(KeyboardToolbar.move(bar, item, -1)) })
            row.addView(button("▶", i < bar.size - 1, "Move ${KeyboardToolbar.label(item)} right") { setBar(KeyboardToolbar.move(bar, item, 1)) })
            row.addView(button("✕", item != KeyboardToolbar.MIC, "Remove ${KeyboardToolbar.label(item)}") { setBar(KeyboardToolbar.remove(bar, item)) })
            list.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, dp(48f)).apply { topMargin = dp(4f) })
        }
        val missing = KeyboardToolbar.ALL.filter { it !in bar }
        if (missing.isNotEmpty()) {
            list.addView(label("Add a button", 13f, t.hint).apply { setPadding(dp(4f), dp(12f), 0, dp(6f)) })
            list.addView(pills(missing.map { "+ " + KeyboardToolbar.label(it) to it }, null) { setBar(KeyboardToolbar.add(bar, it)) })
        }

        // --- How it looks
        heading("Look", null)
        list.addView(pills(listOf("Match phone" to "auto", "Light" to "light", "Dark" to "dark"), p.kbTheme) {
            prefs().kbTheme = it; listener.onMenuSetting()
        })
        list.addView(View(context), LayoutParams(1, dp(8f)))
        list.addView(pills(listOf("Classic" to KeyboardTheme.CLASSIC, "Tinted" to KeyboardTheme.TINTED,
            "Borderless" to KeyboardTheme.BORDERLESS, "High contrast" to KeyboardTheme.CONTRAST), p.kbStyle) {
            prefs().kbStyle = it; listener.onMenuSetting()
        })
        list.addView(label("Colours are in All settings › Keyboard.", 12f, t.hint).apply { setPadding(dp(4f), dp(6f), 0, 0) })

        // --- Height: the number row keeps its smaller share as everything grows or shrinks.
        heading("Keyboard height", null)
        val h = p.kbHeight
        val hr = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = card()
            setPadding(dp(8f), 0, dp(8f), 0)
        }
        hr.addView(button("−", h > KB_HEIGHTS.first, "Shorter") { setHeight(h - 5) })
        hr.addView(label(if (h == 100) "Normal" else "$h%", 15f, t.text, bold = true).apply { gravity = Gravity.CENTER },
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        hr.addView(button("+", h < KB_HEIGHTS.last, "Taller") { setHeight(h + 5) })
        list.addView(hr, LayoutParams(LayoutParams.MATCH_PARENT, dp(48f)).apply { topMargin = dp(4f) })
        if (h != 100) list.addView(label("Back to normal", 13f, t.accentText, bold = true).apply {
            setPadding(dp(4f), dp(8f), 0, 0)
            setOnClickListener { setHeight(100) }
        })

        // --- Quick switches
        heading("Typing", null)
        switch("Number row", p.kbNumberRow) { prefs().kbNumberRow = it }
        switch("Swipe typing", p.kbSwipe) { prefs().kbSwipe = it }
        switch("Autocorrect", p.kbAutocorrect) { prefs().kbAutocorrect = it }
        switch("Suggest what I copied", p.kbClipSuggest) { prefs().kbClipSuggest = it }
        switch("Sound on key press", p.kbSound) { prefs().kbSound = it }
        switch("Vibrate on key press", p.kbVibrate) { prefs().kbVibrate = it }

        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun setBar(bar: List<String>) {
        prefs().kbToolbar = KeyboardToolbar.format(bar)
        listener.onPress(0)
        listener.onMenuToolbar(bar)
        val y = scroll.scrollY
        build()
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun setHeight(percent: Int) {
        prefs().kbHeight = percent
        listener.onPress(0)
        listener.onMenuSetting()
        val y = scroll.scrollY
        build()
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun itemIcon(item: String) = when (item) {
        KeyboardToolbar.MIC -> R.drawable.ic_mic
        KeyboardToolbar.FIX -> R.drawable.ic_kb_sparkle
        KeyboardToolbar.CLIPBOARD -> R.drawable.ic_kb_clipboard
        KeyboardToolbar.EMOJI -> R.drawable.ic_kb_emoji
        KeyboardToolbar.THEME -> R.drawable.ic_kb_theme
        KeyboardToolbar.NUMBERS -> R.drawable.ic_kb_numbers
        else -> R.drawable.ic_kb_settings
    }

    private fun heading(title: String, note: String?) {
        list.addView(label(title, 14f, theme.text, bold = true).apply { setPadding(dp(4f), dp(16f), 0, dp(4f)) })
        if (note != null) list.addView(label(note, 12f, theme.hint).apply { setPadding(dp(4f), 0, 0, dp(4f)) })
    }

    /** A row of choices; the one matching [selected] is filled with the accent. */
    private fun pills(choices: List<Pair<String, String>>, selected: String?, pick: (String) -> Unit): View {
        val t = theme
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        for ((name, id) in choices) {
            val on = id == selected
            row.addView(label(name, 14f, if (on) t.onAccent else t.text, bold = on).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14f), 0, dp(14f), 0)
                background = GradientDrawable().apply { cornerRadius = dp(18f).toFloat(); setColor(if (on) t.accent else if (t.borderless) t.keyPressed else t.key) }
                setOnClickListener {
                    listener.onPress(0)
                    pick(id)
                    val y = scroll.scrollY
                    build()
                    scroll.post { scroll.scrollTo(0, y) }
                }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, dp(36f)).apply { marginEnd = dp(6f) })
        }
        return HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    private fun switch(title: String, on: Boolean, set: (Boolean) -> Unit) {
        val t = theme
        val sw = android.widget.Switch(context).apply {
            isChecked = on
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(t.accent, t.hint))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(KeyboardTheme.mix(t.accent, t.background, 0.5f), t.divider))
            setOnCheckedChangeListener { _, v -> listener.onPress(0); set(v); listener.onMenuSetting() }
        }
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = card()
            setPadding(dp(14f), 0, dp(10f), 0)
            setOnClickListener { sw.toggle() }
        }
        row.addView(label(title, 15f, t.text), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        row.addView(sw)
        list.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, dp(48f)).apply { topMargin = dp(4f) })
    }

    private fun button(text: String, enabled: Boolean, description: String, onClick: () -> Unit) =
        label(text, 16f, if (enabled) theme.text else theme.divider).apply {
            gravity = Gravity.CENTER
            contentDescription = description
            isEnabled = enabled
            if (enabled) setOnClickListener { onClick() }
            layoutParams = LayoutParams(dp(44f), LayoutParams.MATCH_PARENT)
        }

    private fun card() = GradientDrawable().apply {
        cornerRadius = dp(10f).toFloat()
        setColor(if (theme.borderless) theme.keyPressed else theme.key)
    }

    private fun icon(res: Int, color: Int) = ImageView(context).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
    }

    private fun label(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
}
