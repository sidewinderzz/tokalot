package com.tokalot.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The clipboard page, opened from the bar's clipboard button: what was copied in the last hour and anything
 * pinned, in two columns, newest first. Tap one to paste it; long-press for Pin and Delete. It takes the
 * keyboard's height, as the emoji page does.
 */
@SuppressLint("ViewConstructor")
class ClipboardPanel(context: Context, private val listener: Listener) : LinearLayout(context) {

    interface Listener {
        fun onClipPaste(text: String)
        fun onClipPin(text: String, on: Boolean)
        fun onClipDelete(text: String)
        fun onClipClear()
        fun onClipClose()
        fun onPress(code: Int)
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

    var theme = KeyboardTheme.light(Accents.DEFAULT)
        set(v) { field = v; build() }

    /** The panel's height: the keyboard's, so opening it doesn't make the screen jump. */
    var panelHeight = 0
        set(v) { if (field != v) { field = v; requestLayout() } }

    private var items: List<ClipHistory.Item> = emptyList()
    private var enabled = true
    /** The item showing Pin and Delete after a long press. */
    private var menuFor: String? = null

    private val list = LinearLayout(context).apply { orientation = VERTICAL; setPadding(dp(6f), 0, dp(6f), dp(6f)) }
    private val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(list) }

    init {
        orientation = VERTICAL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = maxOf(panelHeight, dp(260f))
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    /** Shows [items] ([enabled] false: the list is switched off in Settings). */
    fun show(items: List<ClipHistory.Item>, enabled: Boolean) {
        this.items = items
        this.enabled = enabled
        menuFor = null
        build()
        scroll.scrollTo(0, 0)
    }

    private fun build() {
        val t = theme
        removeAllViews()
        setBackgroundColor(t.background)

        // Header: back to the letters, the title, and Clear.
        val header = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_back)
            imageTintList = ColorStateList.valueOf(t.text)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            contentDescription = "Back to the keyboard"
            setOnClickListener { listener.onPress(KeyCode.LETTERS); listener.onClipClose() }
        }, LayoutParams(dp(48f), dp(44f)))
        header.addView(label("Clipboard", 16f, t.text, bold = true), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        if (enabled && items.any { !it.pinned }) {
            header.addView(label("Clear", 14f, t.accentText, bold = true).apply {
                setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
                setOnClickListener { listener.onPress(0); listener.onClipClear() }
            })
        }
        addView(header, LayoutParams(LayoutParams.MATCH_PARENT, dp(44f)))

        list.removeAllViews()
        if (!enabled || items.isEmpty()) {
            val msg = if (!enabled) "The clipboard list is off. Turn it on in Tokalot › Settings › Keyboard."
                else "Things you copy show up here for an hour.\nLong-press one to pin it so it stays."
            list.addView(label(msg, 14f, t.hint).apply {
                gravity = Gravity.CENTER
                setPadding(dp(24f), dp(40f), dp(24f), dp(24f))
            }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        } else {
            // Pinned first, then newest first.
            val sorted = items.sortedWith(compareByDescending<ClipHistory.Item> { it.pinned }.thenByDescending { it.time })
            for (pair in sorted.chunked(2)) {
                val row = LinearLayout(context).apply { orientation = HORIZONTAL }
                for (i in 0 until 2) {
                    val item = pair.getOrNull(i)
                    val lp = LayoutParams(0, dp(76f), 1f).apply { setMargins(dp(3f), dp(3f), dp(3f), dp(3f)) }
                    row.addView(if (item != null) card(item) else View(context), lp)
                }
                list.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            }
        }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
    }

    private fun card(item: ClipHistory.Item): View {
        val t = theme
        val box = FrameLayout(context)
        box.background = GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat()
            setColor(if (t.borderless) t.keyPressed else t.key)
        }
        if (menuFor == item.text) {
            // Pin or unpin, and delete; tapping elsewhere on the card closes this.
            val actions = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER }
            actions.addView(label(if (item.pinned) "Unpin" else "Pin", 14f, t.accentText, bold = true).apply {
                gravity = Gravity.CENTER
                setOnClickListener { listener.onPress(0); listener.onClipPin(item.text, !item.pinned) }
            }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            actions.addView(label("Delete", 14f, t.text, bold = true).apply {
                gravity = Gravity.CENTER
                setOnClickListener { listener.onPress(0); listener.onClipDelete(item.text) }
            }, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
            box.addView(actions, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            box.setOnClickListener { menuFor = null; build() }
            return box
        }
        box.addView(label(item.text, 14f, t.text).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10f), dp(8f), dp(if (item.pinned) 24f else 10f), dp(8f))
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        if (item.pinned) {
            box.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_kb_pin)
                imageTintList = ColorStateList.valueOf(t.accentText)
            }, FrameLayout.LayoutParams(dp(14f), dp(14f), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(7f), dp(7f), 0) })
        }
        box.setOnClickListener { listener.onPress(0); listener.onClipPaste(item.text) }
        box.setOnLongClickListener {
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            menuFor = item.text
            build()
            true
        }
        return box
    }

    private fun label(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(context).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
}
