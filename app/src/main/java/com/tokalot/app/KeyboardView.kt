package com.tokalot.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * The keyboard on screen: the suggestion bar along the top (AI fix on the left, three suggestions, the mic
 * on the right) and the keys below it, drawn straight onto a canvas. It reports what the finger did to
 * [listener] and keeps no typing state of its own: [TokalotKeyboard] decides what each touch means for
 * the text.
 *
 * Touches: a tap types on release (so a long press or a swipe can still become something else); a long
 * press opens the key's extra characters; a finger dragged across letters is swipe typing; dragged along
 * the space bar it moves the cursor; dragged left from delete it selects whole words to delete; delete
 * held down repeats.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        /** A key was tapped; ([x], [y]) is where, in the page's pixels, for autocorrect's touch model. */
        fun onKey(code: Int, x: Float, y: Float)
        /** Text from a long-press menu. */
        fun onText(text: String)
        /** A long press on a key without a menu (space, comma). True if it did something. */
        fun onLongPress(code: Int): Boolean
        fun onGlideStart(x: Float, y: Float)
        fun onGlideMove(x: Float, y: Float)
        fun onGlideEnd()
        /** Space bar slide: move the cursor [steps] characters (negative: left). */
        fun onCursorSlide(steps: Int)
        /** Delete slide: select [words] words back from the cursor; [done]: the finger lifted. */
        fun onDeleteSlide(words: Int, done: Boolean)
        /** Suggestion bar: a suggestion's slot (0-2), [SLOT_FIX], [SLOT_MIC], [SLOT_MESSAGE] or [SLOT_CANCEL]. */
        fun onStrip(slot: Int)
        /** A key went down: for the click and buzz. */
        fun onPress(code: Int)
        /** The keys moved (new page or size): key centres for autocorrect and swipe changed. */
        fun onLayoutChanged()
    }

    companion object {
        const val SLOT_FIX = -1
        const val SLOT_MIC = -2
        const val SLOT_MESSAGE = -3
        const val SLOT_CANCEL = -4
        private const val LONG_PRESS_MS = 380L
        private const val REPEAT_START_MS = 400L
        private const val REPEAT_MS = 55L
    }

    // ------------------------------------------------------------------ what's shown

    var page: KeyboardPage = Layouts.letters(numberRow = false, symbolHints = true)
        set(v) { field = v; requestLayout(); invalidate() }

    enum class Shift { OFF, ON, LOCKED }
    var shift = Shift.OFF
        set(v) { if (field != v) { field = v; invalidate() } }

    /** The enter key's look: 0 enter, 1 search, 2 send, 3 next, 4 done, 5 go. */
    var enterKind = 0
        set(v) { if (field != v) { field = v; invalidate() } }

    /** The three suggestions and which one space would put in (drawn bold), or -1. */
    var suggestions: List<String> = emptyList()
        set(v) { field = v; invalidate() }
    var boldSlot = -1
        set(v) { field = v; invalidate() }

    /** A message in place of the suggestions ("Listening…"), with a cancel button when [cancelable]. */
    var message: String? = null
        set(v) { field = v; invalidate() }
    var cancelable = false
        set(v) { field = v; invalidate() }
    /** The mic is recording: the mic button is lit and pulses with [level] (0-1). */
    var recording = false
        set(v) { field = v; invalidate() }
    var level = 0f
        set(v) { field = v; if (recording) invalidate() }
    var swipeTrail = true
    var keyPopups = true

    var theme = KeyboardTheme.light(Accents.DEFAULT)
        set(v) { field = v; invalidate() }

    // ------------------------------------------------------------------ sizes

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    val stripHeight get() = dp(46f)
    private val gap get() = dp(5f)
    private val sidePad get() = dp(3f)
    /** The keys' top edge in this view. */
    val keysTop get() = stripHeight
    private val rowHeight: Float
        get() {
            val land = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val base = if (land) 40f else 54f
            // A taller page (number pad, number row) gets slightly shorter rows so the keyboard doesn't grow much.
            val rows = page.rows.size
            return dp(if (rows > 4 && page.kind == PageKind.LETTERS && rows == 6) base * 0.9f else if (page.kind == PageKind.NUMBERS || page.kind == PageKind.PHONE) base * 1.1f else base)
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (stripHeight + rowHeight * page.rows.size + dp(6f)).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = relayout()

    fun relayout() {
        if (width == 0) return
        page.layout(width - 2 * sidePad, rowHeight, gap)
        listener.onLayoutChanged()
    }

    /** Converts a page position to this view's coordinates. */
    private fun pageX(x: Float) = x + sidePad
    private fun pageY(y: Float) = y + keysTop + dp(3f)
    private fun toPageX(x: Float) = x - sidePad
    private fun toPageY(y: Float) = y - keysTop - dp(3f)

    // ------------------------------------------------------------------ drawing

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val icons = HashMap<Int, Drawable>()

    private fun icon(res: Int): Drawable = icons.getOrPut(res) { context.getDrawable(res)!!.mutate() }

    override fun onDraw(canvas: Canvas) {
        val t = theme
        canvas.drawColor(t.background)
        drawStrip(canvas)
        val radius = dp(6f)
        for (k in page.keys) {
            val pressed = pointers.values.any { it.key === k && !it.gliding }
            val bg = when {
                k.code == KeyCode.ENTER -> t.accent
                k.isFunction -> if (pressed) t.keyPressed else t.functionKey
                else -> if (pressed) t.keyPressed else t.key
            }
            rect.set(pageX(k.x), pageY(k.y), pageX(k.x + k.w), pageY(k.y + k.h))
            fill.color = t.shadow
            canvas.drawRoundRect(rect.left, rect.top + dp(1f), rect.right, rect.bottom + dp(1f), radius, radius, fill)
            fill.color = bg
            canvas.drawRoundRect(rect, radius, radius, fill)
            drawLabel(canvas, k, if (k.code == KeyCode.ENTER) t.onAccent else t.text)
        }
        drawTrail(canvas)
        drawPreview(canvas)
        drawMenu(canvas)
    }

    private fun drawLabel(canvas: Canvas, k: Key, color: Int) {
        val cx = pageX(k.cx)
        val cy = pageY(k.cy)
        val iconRes = when (k.code) {
            KeyCode.SHIFT -> when (shift) { Shift.OFF -> R.drawable.ic_kb_shift; Shift.ON -> R.drawable.ic_kb_shift_on; Shift.LOCKED -> R.drawable.ic_kb_caps }
            KeyCode.DELETE -> R.drawable.ic_kb_delete
            KeyCode.EMOJI -> R.drawable.ic_kb_emoji
            KeyCode.ENTER -> when (enterKind) { 1 -> R.drawable.ic_kb_search; 2 -> R.drawable.ic_kb_send; 3, 5 -> R.drawable.ic_kb_next; 4 -> R.drawable.ic_kb_done; else -> R.drawable.ic_kb_enter }
            else -> 0
        }
        if (iconRes != 0) {
            val s = (dp(24f)).toInt() / 2
            val d = icon(iconRes)
            d.setTint(color)
            d.setBounds((cx - s).toInt(), (cy - s).toInt(), (cx + s).toInt(), (cy + s).toInt())
            d.draw(canvas)
            return
        }
        if (k.code == KeyCode.SPACE) return
        var label = k.label
        if (k.isLetter && shift != Shift.OFF) label = label.uppercase()
        val big = k.isLetter || (label.length == 1 && page.kind != PageKind.LETTERS) || k.width > 2f && label.length == 1
        text.color = color
        text.typeface = if (k.isFunction) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        text.textSize = when {
            k.isFunction -> dp(14f)
            big -> dp(if (page.kind == PageKind.NUMBERS || page.kind == PageKind.PHONE) 24f else 22f)
            else -> dp(20f)
        }
        canvas.drawText(label, cx, cy - (text.descent() + text.ascent()) / 2, text)
        val hint = k.hint
        if (hint != null && symbolHints) {
            text.color = theme.hint
            text.typeface = Typeface.DEFAULT
            text.textSize = dp(10.5f)
            canvas.drawText(hint, pageX(k.x + k.w) - dp(7f), pageY(k.y) + dp(13f), text)
        }
    }

    var symbolHints = true

    // ---- the suggestion bar

    /** Where the bar's parts are: fix button, three slots, mic button. */
    private fun stripSlots(): FloatArray {
        val side = stripHeight * 1.15f
        val mid = (width - 2 * side) / 3
        return floatArrayOf(0f, side, side + mid, side + 2 * mid, width - side, width.toFloat())
    }

    private fun drawStrip(canvas: Canvas) {
        val t = theme
        val h = stripHeight
        val s = stripSlots()
        val cy = h / 2
        // Left: AI fix, or cancel while something is running.
        val leftRes = if (cancelable) R.drawable.ic_close else R.drawable.ic_kb_sparkle
        drawIcon(canvas, leftRes, (s[0] + s[1]) / 2, cy, dp(22f), if (cancelable) t.text else t.accentText)
        // Right: the mic.
        val micX = (s[4] + s[5]) / 2
        if (recording) {
            fill.color = t.accent
            val r = dp(17f) + dp(6f) * level.coerceIn(0f, 1f)
            fill.alpha = 70
            canvas.drawCircle(micX, cy, r, fill)
            fill.alpha = 255
            canvas.drawCircle(micX, cy, dp(17f), fill)
        }
        drawIcon(canvas, R.drawable.ic_mic, micX, cy, dp(22f), if (recording) t.onAccent else t.text)

        val msg = message
        text.typeface = Typeface.DEFAULT
        if (msg != null) {
            text.color = t.text
            text.textSize = dp(15f)
            canvas.drawText(ellipsize(msg, s[4] - s[1] - dp(16f)), (s[1] + s[4]) / 2, cy - (text.descent() + text.ascent()) / 2, text)
            return
        }
        for (i in 0 until 3) {
            val word = suggestions.getOrNull(i) ?: continue
            val left = s[1 + i]; val right = s[2 + i]
            text.color = t.text
            text.textSize = dp(16f)
            text.typeface = if (i == boldSlot) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            canvas.drawText(ellipsize(word, right - left - dp(8f)), (left + right) / 2, cy - (text.descent() + text.ascent()) / 2, text)
            if (i > 0 && suggestions.getOrNull(i - 1) != null) {
                fill.color = t.divider
                canvas.drawRect(left - dp(0.5f), cy - dp(10f), left + dp(0.5f), cy + dp(10f), fill)
            }
        }
    }

    private fun ellipsize(s: String, maxW: Float): String {
        if (text.measureText(s) <= maxW) return s
        var n = s.length
        while (n > 1 && text.measureText(s, 0, n) + text.measureText("…") > maxW) n--
        return s.substring(0, n) + "…"
    }

    private fun drawIcon(canvas: Canvas, res: Int, cx: Float, cy: Float, size: Float, color: Int) {
        val d = icon(res)
        d.setTint(color)
        val s = size / 2
        d.setBounds((cx - s).toInt(), (cy - s).toInt(), (cx + s).toInt(), (cy + s).toInt())
        d.draw(canvas)
    }

    // ---- the key bubble, the long-press menu and the swipe trail

    private fun drawPreview(canvas: Canvas) {
        if (!keyPopups || menu != null) return
        val p = pointers.values.firstOrNull { !it.gliding && !it.sliding && it.key?.let { k -> !k.isFunction && k.code != KeyCode.SPACE } == true } ?: return
        val k = p.key ?: return
        val w = max(k.w * 1.1f, dp(44f))
        val h = k.h * 1.25f
        val cx = pageX(k.cx)
        val top = pageY(k.y) - h - dp(4f)
        rect.set(cx - w / 2, top, cx + w / 2, top + h)
        fill.color = theme.shadow
        canvas.drawRoundRect(rect.left, rect.top + dp(2f), rect.right, rect.bottom + dp(2f), dp(8f), dp(8f), fill)
        fill.color = theme.preview
        canvas.drawRoundRect(rect, dp(8f), dp(8f), fill)
        var label = k.label
        if (k.isLetter && shift != Shift.OFF) label = label.uppercase()
        text.color = theme.text
        text.typeface = Typeface.DEFAULT
        text.textSize = dp(28f)
        canvas.drawText(label, cx, rect.centerY() - (text.descent() + text.ascent()) / 2, text)
    }

    private class Menu(val key: Key, val items: List<String>, val left: Float, val top: Float, val cell: Float, val h: Float) {
        var selected = 0
    }
    private var menu: Menu? = null

    private fun drawMenu(canvas: Canvas) {
        val m = menu ?: return
        rect.set(m.left, m.top, m.left + m.cell * m.items.size, m.top + m.h)
        fill.color = theme.shadow
        canvas.drawRoundRect(rect.left, rect.top + dp(2f), rect.right, rect.bottom + dp(2f), dp(8f), dp(8f), fill)
        fill.color = theme.preview
        canvas.drawRoundRect(rect, dp(8f), dp(8f), fill)
        m.items.forEachIndexed { i, s ->
            val l = m.left + i * m.cell
            if (i == m.selected) {
                fill.color = theme.accent
                canvas.drawRoundRect(l + dp(3f), m.top + dp(3f), l + m.cell - dp(3f), m.top + m.h - dp(3f), dp(6f), dp(6f), fill)
            }
            text.color = if (i == m.selected) theme.onAccent else theme.text
            text.typeface = Typeface.DEFAULT
            text.textSize = dp(22f)
            val label = if (m.key.isLetter && shift != Shift.OFF) s.uppercase() else s
            canvas.drawText(label, l + m.cell / 2, m.top + m.h / 2 - (text.descent() + text.ascent()) / 2, text)
        }
    }

    private val trailPath = Path()

    private fun drawTrail(canvas: Canvas) {
        if (!swipeTrail) return
        val p = pointers.values.firstOrNull { it.gliding } ?: return
        if (p.trail.size < 4) return
        trailPath.reset()
        trailPath.moveTo(p.trail[0], p.trail[1])
        var i = 2
        while (i + 1 < p.trail.size) { trailPath.lineTo(p.trail[i], p.trail[i + 1]); i += 2 }
        trail.color = theme.accent
        trail.alpha = 200
        trail.strokeWidth = dp(5f)
        canvas.drawPath(trailPath, trail)
    }

    // ------------------------------------------------------------------ touch

    private class Pointer(val id: Int, var key: Key?, val downX: Float, val downY: Float, val downAt: Long) {
        var x = downX; var y = downY
        var longDone = false
        var gliding = false
        var sliding = false   // space bar cursor slide
        var slideSteps = 0
        var deleting = false  // delete key word slide
        var deleteWords = 0
        var repeating = false
        var fired = false
        val trail = ArrayList<Float>()
    }

    private val pointers = LinkedHashMap<Int, Pointer>()
    private val handler = Handler(Looper.getMainLooper())
    private var lastShiftTap = 0L
    /** Swipe typing is allowed now (letters page, a text field that takes suggestions). */
    var glideEnabled = true

    private val longPress = Runnable { onLongPressTimeout() }
    private val repeat = object : Runnable {
        override fun run() {
            val p = pointers.values.firstOrNull { it.key?.code == KeyCode.DELETE && !it.deleting } ?: return
            p.repeating = true
            p.fired = true
            listener.onKey(KeyCode.DELETE, 0f, 0f)
            handler.postDelayed(this, REPEAT_MS)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val x = e.getX(i); val y = e.getY(i)
                if (y < keysTop) {
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) stripDown = x
                    return true
                }
                // A second finger down: the first one's key is typed now (fast two-thumb typing).
                for (p in pointers.values) if (!p.fired && !p.gliding && !p.sliding && !p.deleting && menu == null) fire(p)
                val key = page.keyAt(toPageX(x), toPageY(y))
                val p = Pointer(e.getPointerId(i), key, x, y, SystemClock.uptimeMillis())
                pointers[p.id] = p
                if (key != null) {
                    listener.onPress(key.code)
                    handler.removeCallbacks(longPress)
                    handler.postDelayed(longPress, LONG_PRESS_MS)
                    if (key.code == KeyCode.DELETE) handler.postDelayed(repeat, REPEAT_START_MS)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val p = pointers[e.getPointerId(i)] ?: continue
                    move(p, e.getX(i), e.getY(i))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = e.actionIndex
                val id = e.getPointerId(i)
                if (pointers.isEmpty() && stripDown >= 0f && e.actionMasked == MotionEvent.ACTION_UP) {
                    stripTap(e.getX(i), e.getY(i))
                    stripDown = -1f
                    return true
                }
                val p = pointers.remove(id) ?: return true
                up(p)
                if (pointers.isEmpty()) {
                    handler.removeCallbacks(longPress)
                    handler.removeCallbacks(repeat)
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pointers.values.forEach { if (it.gliding) listener.onGlideEnd() }
                pointers.clear()
                menu = null
                stripDown = -1f
                handler.removeCallbacks(longPress)
                handler.removeCallbacks(repeat)
                invalidate()
            }
        }
        return true
    }

    private var stripDown = -1f

    private fun stripTap(x: Float, y: Float) {
        if (y > keysTop) return
        val s = stripSlots()
        val slot = when {
            x < s[1] -> if (cancelable) SLOT_CANCEL else SLOT_FIX
            x >= s[4] -> SLOT_MIC
            message != null -> SLOT_MESSAGE
            x < s[2] -> 0
            x < s[3] -> 1
            else -> 2
        }
        if (slot in 0..2 && suggestions.getOrNull(slot) == null) return
        listener.onStrip(slot)
    }

    private fun move(p: Pointer, x: Float, y: Float) {
        p.x = x; p.y = y
        val key = p.key ?: return
        val unit = page.unit
        val m = menu
        if (m != null && m.key === key) {
            val i = ((x - m.left) / m.cell).toInt().coerceIn(0, m.items.size - 1)
            if (i != m.selected) { m.selected = i; invalidate() }
            return
        }
        val dx = x - p.downX
        val dy = y - p.downY
        when {
            p.gliding -> {
                p.trail += x; p.trail += y
                listener.onGlideMove(toPageX(x), toPageY(y))
                invalidate()
            }
            p.sliding -> {
                val steps = (dx / (unit * 0.45f)).toInt()
                if (steps != p.slideSteps) { listener.onCursorSlide(steps - p.slideSteps); p.slideSteps = steps }
            }
            p.deleting -> {
                val words = max(0, (-dx / (unit * 0.9f)).toInt())
                if (words != p.deleteWords) { p.deleteWords = words; listener.onDeleteSlide(words, false) }
            }
            key.code == KeyCode.SPACE && abs(dx) > unit * 0.6f && !p.longDone -> {
                p.sliding = true; p.fired = true
                handler.removeCallbacks(longPress)
                invalidate()
            }
            key.code == KeyCode.DELETE && dx < -unit * 0.8f && !p.repeating -> {
                p.deleting = true; p.fired = true
                handler.removeCallbacks(longPress); handler.removeCallbacks(repeat)
                p.deleteWords = 1
                listener.onDeleteSlide(1, false)
            }
            glideEnabled && key.isLetter && pointers.size == 1 && !p.longDone && hypot(dx, dy) > unit * 0.55f -> {
                p.gliding = true; p.fired = true
                handler.removeCallbacks(longPress)
                p.trail += p.downX; p.trail += p.downY; p.trail += x; p.trail += y
                listener.onGlideStart(toPageX(p.downX), toPageY(p.downY))
                listener.onGlideMove(toPageX(x), toPageY(y))
                invalidate()
            }
            !p.longDone && !key.isFunction && key.code != KeyCode.SPACE -> {
                // Sliding onto another key before letting go types that key instead (as on Gboard).
                val now = page.keyAt(toPageX(x), toPageY(y))
                if (now != null && now !== key && !now.isFunction && now.code != KeyCode.SPACE) {
                    p.key = now
                    handler.removeCallbacks(longPress)
                    handler.postDelayed(longPress, LONG_PRESS_MS)
                    invalidate()
                }
            }
        }
    }

    private fun up(p: Pointer) {
        val m = menu
        when {
            m != null && m.key === p.key -> {
                menu = null
                listener.onText(m.items[m.selected])
            }
            p.gliding -> listener.onGlideEnd()
            p.sliding -> {}
            p.deleting -> listener.onDeleteSlide(p.deleteWords, true)
            !p.fired && !p.longDone -> fire(p)
        }
    }

    /** Types the pointer's key. */
    private fun fire(p: Pointer) {
        if (p.fired) return
        p.fired = true
        val k = p.key ?: return
        if (k.code == KeyCode.SHIFT) {
            val now = SystemClock.uptimeMillis()
            val double = now - lastShiftTap < 300
            lastShiftTap = now
            shift = when {
                double && shift != Shift.LOCKED -> Shift.LOCKED
                shift == Shift.OFF -> Shift.ON
                else -> Shift.OFF
            }
            listener.onKey(KeyCode.SHIFT, 0f, 0f)
            return
        }
        listener.onKey(k.code, toPageX(p.downX).let { if (k.isLetter) toPageX(p.x) else it }, toPageY(p.y))
    }

    private fun onLongPressTimeout() {
        val p = pointers.values.lastOrNull { !it.fired && !it.gliding && !it.sliding && !it.deleting } ?: return
        val k = p.key ?: return
        if (k.code == KeyCode.DELETE) return
        if (k.popup.isEmpty()) {
            if (listener.onLongPress(k.code)) { p.longDone = true; p.fired = true; invalidate() }
            return
        }
        p.longDone = true
        p.fired = true
        val items = k.popup
        val cell = max(k.w, dp(36f))
        val h = k.h * 1.1f
        var left = pageX(k.cx) - cell / 2
        left = left.coerceIn(dp(2f), width - cell * items.size - dp(2f))
        val top = pageY(k.y) - h - dp(4f)
        menu = Menu(k, items, left, top, cell, h).also { m ->
            // Start on the item nearest the finger.
            m.selected = ((p.x - left) / cell).toInt().coerceIn(0, items.size - 1)
        }
        listener.onPress(KeyCode.SHIFT) // a small tick, like Gboard's
        invalidate()
    }

    /** Forgets every touch (the keyboard was hidden or changed page mid-press). */
    fun reset() {
        pointers.clear(); menu = null; stripDown = -1f
        handler.removeCallbacks(longPress); handler.removeCallbacks(repeat)
        invalidate()
    }
}

/** The keyboard's colours: Gboard-like greys, with the user's Tokalot accent on enter, the mic and swipe. */
class KeyboardTheme(
    val background: Int, val key: Int, val functionKey: Int, val keyPressed: Int, val text: Int, val hint: Int,
    val accent: Int, val onAccent: Int, val accentText: Int, val preview: Int, val divider: Int, val shadow: Int,
) {
    companion object {
        fun light(accent: Int) = KeyboardTheme(
            background = 0xFFE8EAED.toInt(), key = 0xFFFFFFFF.toInt(), functionKey = 0xFFCDD1D6.toInt(),
            keyPressed = 0xFFBDC1C6.toInt(), text = 0xFF202124.toInt(), hint = 0xFF80868B.toInt(),
            accent = accent, onAccent = onColor(accent), accentText = darkerFor(accent, light = true),
            preview = 0xFFFFFFFF.toInt(), divider = 0xFFC4C7CC.toInt(), shadow = 0x22000000,
        )

        fun dark(accent: Int) = KeyboardTheme(
            background = 0xFF1F1F21.toInt(), key = 0xFF3C3C40.toInt(), functionKey = 0xFF2C2C30.toInt(),
            keyPressed = 0xFF55555B.toInt(), text = 0xFFE8EAED.toInt(), hint = 0xFF9AA0A6.toInt(),
            accent = accent, onAccent = onColor(accent), accentText = darkerFor(accent, light = false),
            preview = 0xFF4A4A50.toInt(), divider = 0xFF4A4A50.toInt(), shadow = 0x33000000,
        )

        /** Black or white, whichever reads better on [c]. */
        private fun onColor(c: Int) = if (C.luminance(c) > 0.45) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()

        /** The accent as an icon colour on the bar: a light accent (amber, white) is too faint on a light bar. */
        private fun darkerFor(c: Int, light: Boolean): Int {
            if (!light || C.luminance(c) < 0.5) return c
            val r = (c shr 16 and 0xFF) * 6 / 10; val g = (c shr 8 and 0xFF) * 6 / 10; val b = (c and 0xFF) * 6 / 10
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
