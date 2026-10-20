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
 * The keyboard on screen: the bar along the top (the menu button, then the user's toolbar, or three
 * suggestions with the mic beside them while there's something to suggest) and the keys below it, drawn straight onto a canvas. It reports what the finger did to
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
        /** Space bar slide: move the cursor [steps] more characters (negative: left). */
        fun onCursorSlide(steps: Int)
        /** Delete slide: select [words] words back from the cursor; [done]: the finger lifted, so delete them. */
        fun onDeleteSlide(words: Int, done: Boolean)
        /**
         * A cursor slide ended ([cancelled] false), or either slide was abandoned (the finger strayed off
         * the row, or the touch was taken away): the cursor and selection go back to where they were.
         */
        fun onSlideEnd(cancelled: Boolean)
        /** The bar: a suggestion's slot (0-2), [SLOT_MENU], [SLOT_MESSAGE] or [SLOT_CANCEL]. */
        fun onStrip(slot: Int)
        /** A toolbar button on the bar (one of [KeyboardToolbar]'s). */
        fun onTool(item: String)
        /** A key went down: for the click and buzz. */
        fun onPress(code: Int)
        /** The keys moved (new page or size): key centres for autocorrect and swipe changed. */
        fun onLayoutChanged()
    }

    companion object {
        const val SLOT_MENU = -1
        const val SLOT_MESSAGE = -3
        const val SLOT_CANCEL = -4
        private const val TOOL = -10
        /** A chip that pastes a picture: drawn as [chipThumb] instead of text. */
        const val IMAGE_CHIP = "\u0001image"
        private const val LONG_PRESS_MS = 380L
        private const val REPEAT_START_MS = 400L
        private const val REPEAT_MS = 55L
        /** A slide along space or from backspace only starts this long after the touch, so a quick tap stays a tap. */
        private const val SLIDE_DELAY_MS = 200L
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
    /** The suggestions are things to paste (copied, a texted code, a new screenshot), drawn as chips. */
    var chips = false
        set(v) { field = v; invalidate() }
    /** The picture an [IMAGE_CHIP] shows. */
    var chipThumb: android.graphics.Bitmap? = null
        set(v) { field = v; invalidate() }
    /** The toolbar's buttons in the user's order (see [KeyboardToolbar]). */
    var toolbar: List<String> = KeyboardToolbar.DEFAULT
        set(v) { if (field != v) { field = v; invalidate() } }

    /**
     * The mic is Tokalot's logo, five bars. Recording, it lights up and the bars follow the voice ([level],
     * 0-1) as the floating button's do; while the words are worked on ([working]) they ripple.
     */
    var recording = false
        set(v) { field = v; micFrom = System.nanoTime(); invalidate() }
    var working = false
        set(v) { field = v; micFrom = System.nanoTime(); invalidate() }
    var level = 0f
        set(v) { field = v; if (recording) invalidate() }
    var swipeTrail = true
    var keyPopups = true

    var theme = KeyboardTheme.light(Accents.DEFAULT)
        set(v) { field = v; invalidate() }

    // ------------------------------------------------------------------ sizes

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun pt(v: Float) = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_PT, v, resources.displayMetrics)
    private fun mm(v: Float) = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_MM, v, resources.displayMetrics)

    // Slide distances in physical units, so they feel the same on every screen: how far the finger must go
    // before a slide starts, how far per character, how far for the first, second and each further word,
    // and how far above or below the key it may stray before the slide is abandoned.
    private val slideStart by lazy { pt(8f) }
    private val cursorStep by lazy { pt(8f) }
    private val deleteFirst by lazy { pt(23f) }
    private val deleteSecond by lazy { pt(41f) }
    private val deleteStep by lazy { pt(16f) }
    private val slideSlack by lazy { mm(4f) }
    val stripHeight get() = dp(46f)
    private val gap get() = dp(5f)
    private val sidePad get() = dp(3f)
    /** The keys' top edge in this view. */
    val keysTop get() = stripHeight
    private val rowHeight: Float
        get() {
            val land = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            val base = if (land) 40f else 54f
            // The number pad's keys are a little taller; the number row's are shorter (see Layouts.NUMBER_ROW).
            return dp(heightScale * if (page.kind == PageKind.NUMBERS || page.kind == PageKind.PHONE) base * 1.1f else base)
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (stripHeight + page.heightFor(rowHeight) + dp(6f)).toInt()
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
            if (t.borderless && !pressed && k.code != KeyCode.ENTER) {
                drawLabel(canvas, k, t.text)
                continue
            }
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

    /** The keys' height against the usual (Settings' keyboard height, 0.8-1.3). */
    var heightScale = 1f
        set(v) { if (field != v) { field = v; requestLayout(); invalidate() } }

    // ---- the suggestion bar

    /** A part of the bar: [kind] is a SLOT_ constant, a suggestion's index, or [TOOL] with its [item]. */
    private class Part(val left: Float, val right: Float, val kind: Int, val item: String? = null)

    /**
     * The bar's parts, left to right. The menu (cancel while something runs) always comes first. With nothing
     * to suggest or say, the toolbar's buttons share the rest; otherwise the suggestions (or the message) take
     * the middle and the mic stays on its side.
     */
    private fun stripParts(): List<Part> {
        val side = stripHeight * 1.15f
        val w = width.toFloat()
        val out = ArrayList<Part>()
        out += Part(0f, side, if (cancelable) SLOT_CANCEL else SLOT_MENU)
        if (message == null && suggestions.isEmpty()) {
            val each = (w - side) / toolbar.size.coerceAtLeast(1)
            toolbar.forEachIndexed { i, item -> out += Part(side + i * each, side + (i + 1) * each, TOOL, item) }
            return out
        }
        val micLeft = KeyboardToolbar.micOnLeft(toolbar)
        val from = if (micLeft) 2 * side else side
        val to = if (micLeft) w else w - side
        if (micLeft) out += Part(side, 2 * side, TOOL, KeyboardToolbar.MIC)
        when {
            message != null -> out += Part(from, to, SLOT_MESSAGE)
            chips -> {
                // Copied things share the room between them.
                val each = (to - from) / suggestions.size
                suggestions.indices.forEach { out += Part(from + it * each, from + (it + 1) * each, it) }
            }
            else -> {
                val each = (to - from) / 3
                for (i in 0 until 3) out += Part(from + i * each, from + (i + 1) * each, i)
            }
        }
        if (!micLeft) out += Part(w - side, w, TOOL, KeyboardToolbar.MIC)
        return out
    }

    private fun toolIcon(item: String) = when (item) {
        KeyboardToolbar.FIX -> R.drawable.ic_kb_sparkle
        KeyboardToolbar.CLIPBOARD -> R.drawable.ic_kb_clipboard
        KeyboardToolbar.EMOJI -> R.drawable.ic_kb_emoji
        KeyboardToolbar.THEME -> R.drawable.ic_kb_theme
        KeyboardToolbar.NUMBERS -> R.drawable.ic_kb_numbers
        KeyboardToolbar.SETTINGS -> R.drawable.ic_kb_settings
        else -> 0
    }

    private fun drawStrip(canvas: Canvas) {
        val t = theme
        val cy = stripHeight / 2
        val baseline = { cy - (text.descent() + text.ascent()) / 2 }
        for (p in stripParts()) {
            val mid = (p.left + p.right) / 2
            when (p.kind) {
                SLOT_MENU -> drawIcon(canvas, R.drawable.ic_kb_grid, mid, cy, dp(19f), t.hint)
                SLOT_CANCEL -> drawIcon(canvas, R.drawable.ic_close, mid, cy, dp(22f), t.text)
                SLOT_MESSAGE -> {
                    // While recording just a quiet word: the moving mic says the rest.
                    text.color = if (recording) t.hint else t.text
                    text.typeface = Typeface.DEFAULT
                    text.textSize = dp(if (recording) 13f else 15f)
                    canvas.drawText(ellipsize(message ?: "", p.right - p.left - dp(16f)), mid, baseline(), text)
                }
                TOOL -> when (p.item) {
                    KeyboardToolbar.MIC -> drawMic(canvas, mid, cy)
                    KeyboardToolbar.FIX -> drawIcon(canvas, R.drawable.ic_kb_sparkle, mid, cy, dp(22f), t.accentText)
                    else -> drawIcon(canvas, toolIcon(p.item ?: ""), mid, cy, dp(21f), t.text)
                }
                else -> {
                    val i = p.kind
                    val word = suggestions.getOrNull(i) ?: continue
                    text.color = t.text
                    text.textSize = dp(if (chips) 14f else 16f)
                    text.typeface = if (i == boldSlot) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                    if (chips) {
                        // A copied thing: a pill with the clipboard mark, tap to paste.
                        rect.set(p.left + dp(3f), cy - dp(15f), p.right - dp(3f), cy + dp(15f))
                        fill.color = if (t.borderless) t.keyPressed else t.key
                        canvas.drawRoundRect(rect, dp(15f), dp(15f), fill)
                        val thumb = chipThumb
                        if (word == IMAGE_CHIP && thumb != null) {
                            // A tiny picture of it, cropped to fit the pill.
                            val h = rect.height() - dp(6f)
                            val w = minOf(rect.width() - dp(12f), h * 1.6f)
                            val src = android.graphics.Rect(0, 0, thumb.width, thumb.height)
                            val want = w / h
                            if (thumb.width.toFloat() / thumb.height > want) {
                                val cw = (thumb.height * want).toInt(); src.left = (thumb.width - cw) / 2; src.right = src.left + cw
                            } else {
                                val ch = (thumb.width / want).toInt(); src.top = (thumb.height - ch) / 2; src.bottom = src.top + ch
                            }
                            val dst = RectF(mid - w / 2, cy - h / 2, mid + w / 2, cy + h / 2)
                            canvas.save()
                            val clipPath = android.graphics.Path().apply { addRoundRect(dst, dp(6f), dp(6f), android.graphics.Path.Direction.CW) }
                            canvas.clipPath(clipPath)
                            canvas.drawBitmap(thumb, src, dst, null)
                            canvas.restore()
                            continue
                        }
                        drawIcon(canvas, R.drawable.ic_kb_clipboard, rect.left + dp(14f), cy, dp(14f), t.accentText)
                        val room = rect.width() - dp(32f)
                        canvas.drawText(ellipsize(word.replace('\n', ' '), room), rect.left + dp(26f) + room / 2, baseline(), text)
                        continue
                    }
                    canvas.drawText(ellipsize(word, p.right - p.left - dp(8f)), mid, baseline(), text)
                    if (i > 0 && suggestions.getOrNull(i - 1) != null) {
                        fill.color = t.divider
                        canvas.drawRect(p.left - dp(0.5f), cy - dp(10f), p.left + dp(0.5f), cy + dp(10f), fill)
                    }
                }
            }
        }
    }

    // ---- the mic: Tokalot's five bars, as on the app icon and the floating button

    private val barBase = floatArrayOf(16f, 36f, 22f, 44f, 16f)
    private val barGain = floatArrayOf(0.55f, 0.85f, 0.7f, 1f, 0.5f)
    private val barNow = barBase.copyOf()
    private var micFrom = System.nanoTime()
    private var micLevel = 0f

    private fun drawMic(canvas: Canvas, cx: Float, cy: Float) {
        val t = theme
        val active = recording || working
        val size = dp(34f)
        rect.set(cx - size / 2, cy - size / 2, cx + size / 2, cy + size / 2)
        // The floating button's graphite square while it works; quiet the rest of the time.
        if (active) {
            fill.color = 0xFF2C2C2E.toInt()
            canvas.drawRoundRect(rect, dp(10f), dp(10f), fill)
        }
        val time = (System.nanoTime() - micFrom) / 1e9f
        if (recording) {
            val raw = kotlin.math.sqrt((level * 6f).coerceIn(0f, 1f))
            micLevel += (raw - micLevel) * 0.4f
        }
        val unit = size / 108f * 1.25f // the icon's 108-unit grid, its bars a little larger here
        var moving = false
        for (i in 0 until 5) {
            val target = when {
                recording -> 10f + micLevel * 52f * barGain[i] + 3f * kotlin.math.sin(time * 9f + i * 1.7f)
                working -> 12f + 34f * (0.5f + 0.5f * kotlin.math.sin((time * 1.6f - i * 0.18f) * 2f * Math.PI.toFloat()))
                else -> barBase[i]
            }
            barNow[i] += (target - barNow[i]) * 0.3f
            if (abs(target - barNow[i]) > 0.3f) moving = true
            val bh = barNow[i].coerceIn(6f, 64f) * unit
            val bx = cx + (i - 2) * 11f * unit
            fill.color = when {
                recording -> t.accent
                working -> 0xFFFFFFFF.toInt()
                i == 3 -> t.accent
                else -> t.text
            }
            canvas.drawRoundRect(bx - 3f * unit, cy - bh / 2, bx + 3f * unit, cy + bh / 2, 3f * unit, 3f * unit, fill)
        }
        if (active || moving) postInvalidateOnAnimation()
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
        var dead = false      // a slide was abandoned: lifting the finger does nothing
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
                    // A finger that moved before the slide delay and then stopped still gets its slide.
                    if (key.code == KeyCode.DELETE || key.code == KeyCode.SPACE) handler.postDelayed(slideCheck, SLIDE_DELAY_MS + 1)
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
                    handler.removeCallbacks(slideCheck)
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pointers.values.forEach {
                    if (it.gliding) listener.onGlideEnd()
                    if (it.sliding || it.deleting) listener.onSlideEnd(cancelled = true)
                }
                handler.removeCallbacks(slideCheck)
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
        val part = stripParts().firstOrNull { x >= it.left && x < it.right } ?: return
        when {
            part.kind == TOOL -> listener.onTool(part.item ?: return)
            part.kind >= 0 && suggestions.getOrNull(part.kind) == null -> {}
            else -> listener.onStrip(part.kind)
        }
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
        if (p.dead) return
        val dx = x - p.downX
        val dy = y - p.downY
        val ready = SystemClock.uptimeMillis() - p.downAt >= SLIDE_DELAY_MS && abs(dx) >= slideStart && abs(dy) <= abs(dx)
        when {
            p.gliding -> {
                p.trail += x; p.trail += y
                listener.onGlideMove(toPageX(x), toPageY(y))
                invalidate()
            }
            (p.sliding || p.deleting) && strayed(key, y) -> abandon(p)
            p.sliding -> {
                val steps = (dx / cursorStep).toInt()
                if (steps != p.slideSteps) { listener.onCursorSlide(steps - p.slideSteps); p.slideSteps = steps }
            }
            p.deleting -> {
                val words = deleteWords(-dx)
                if (words != p.deleteWords) { p.deleteWords = words; listener.onDeleteSlide(words, false) }
            }
            key.code == KeyCode.SPACE && ready && !p.longDone -> {
                p.sliding = true; p.fired = true
                handler.removeCallbacks(longPress)
                p.slideSteps = (dx / cursorStep).toInt()
                listener.onCursorSlide(p.slideSteps)
                invalidate()
            }
            // Only once the finger has left the key, so a wobbly hold on backspace still repeats.
            key.code == KeyCode.DELETE && ready && dx < 0 && x < pageX(key.x) && !p.repeating -> {
                p.deleting = true; p.fired = true
                handler.removeCallbacks(longPress); handler.removeCallbacks(repeat)
                p.deleteWords = deleteWords(-dx)
                listener.onDeleteSlide(p.deleteWords, false)
                invalidate()
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

    /** Words a delete slide of [d] pixels to the left covers: one at 23 pt, two at 41 pt, then one per 16 pt. */
    private fun deleteWords(d: Float): Int = when {
        d < deleteFirst -> 0
        d < deleteSecond -> 1
        else -> 2 + ((d - deleteSecond) / deleteStep).toInt()
    }

    /** The finger went too far above or below the key's row for a slide to still count. */
    private fun strayed(key: Key, y: Float) = y < pageY(key.y) - slideSlack || y > pageY(key.y + key.h) + slideSlack

    private fun abandon(p: Pointer) {
        p.sliding = false; p.deleting = false
        p.dead = true; p.fired = true
        listener.onSlideEnd(cancelled = true)
        invalidate()
    }

    private val slideCheck = Runnable {
        for (p in pointers.values.toList()) if (!p.fired && (p.key?.code == KeyCode.SPACE || p.key?.code == KeyCode.DELETE)) move(p, p.x, p.y)
    }

    private fun up(p: Pointer) {
        if (p.dead) return
        val m = menu
        when {
            m != null && m.key === p.key -> {
                menu = null
                listener.onText(m.items[m.selected])
            }
            p.gliding -> listener.onGlideEnd()
            p.sliding -> listener.onSlideEnd(cancelled = false)
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
        handler.removeCallbacks(longPress); handler.removeCallbacks(repeat); handler.removeCallbacks(slideCheck)
        invalidate()
    }
}

/**
 * The keyboard's colours. [style]: "classic" (Gboard-like greys), "tinted" (keys tinted with the accent),
 * "borderless" (no key shapes, only the letters) or "contrast" (pure black or pure white). The accent goes
 * on enter, the mic, the swipe trail and the picked suggestion.
 */
class KeyboardTheme(
    val background: Int, val key: Int, val functionKey: Int, val keyPressed: Int, val text: Int, val hint: Int,
    val accent: Int, val onAccent: Int, val accentText: Int, val preview: Int, val divider: Int, val shadow: Int,
    val dark: Boolean, val borderless: Boolean = false,
) {
    companion object {
        const val CLASSIC = "classic"
        const val TINTED = "tinted"
        const val BORDERLESS = "borderless"
        const val CONTRAST = "contrast"
        val STYLES = listOf(CLASSIC, TINTED, BORDERLESS, CONTRAST)

        fun light(accent: Int) = make(dark = false, style = CLASSIC, accent = accent)
        fun dark(accent: Int) = make(dark = true, style = CLASSIC, accent = accent)

        fun make(dark: Boolean, style: String, accent: Int): KeyboardTheme {
            val accentText = darkerFor(accent, light = !dark)
            fun t(bg: Long, key: Long, fn: Long, pressed: Long, text: Long, hint: Long, preview: Long, divider: Long, borderless: Boolean = false) =
                KeyboardTheme(
                    bg.toInt(), key.toInt(), fn.toInt(), pressed.toInt(), text.toInt(), hint.toInt(),
                    accent, onColor(accent), accentText, preview.toInt(), divider.toInt(),
                    if (dark) 0x33000000 else 0x22000000, dark, borderless,
                )
            return when (style) {
                TINTED -> {
                    val base = if (dark) 0xFF1F1F21.toInt() else 0xFFFFFFFF.toInt()
                    KeyboardTheme(
                        mix(accent, base, if (dark) 0.16f else 0.14f),
                        mix(accent, if (dark) 0xFF3C3C40.toInt() else 0xFFFFFFFF.toInt(), if (dark) 0.10f else 0.03f),
                        mix(accent, base, if (dark) 0.28f else 0.32f),
                        mix(accent, base, 0.45f),
                        if (dark) 0xFFE8EAED.toInt() else 0xFF202124.toInt(),
                        if (dark) 0xFF9AA0A6.toInt() else 0xFF80868B.toInt(),
                        accent, onColor(accent), accentText,
                        if (dark) 0xFF4A4A50.toInt() else 0xFFFFFFFF.toInt(),
                        mix(accent, base, 0.4f),
                        if (dark) 0x33000000 else 0x22000000, dark,
                    )
                }
                CONTRAST -> if (dark) t(0xFF000000, 0xFF1A1A1A, 0xFF0D0D0D, 0xFF333333, 0xFFFFFFFF, 0xFFA0A0A0, 0xFF2A2A2A, 0xFF333333)
                    else t(0xFFFFFFFF, 0xFFF1F3F4, 0xFFDADCE0, 0xFFBDC1C6, 0xFF000000, 0xFF5F6368, 0xFFFFFFFF, 0xFFDADCE0)
                BORDERLESS -> if (dark) t(0xFF1F1F21, 0xFF1F1F21, 0xFF1F1F21, 0xFF3C3C40, 0xFFE8EAED, 0xFF9AA0A6, 0xFF4A4A50, 0xFF4A4A50, borderless = true)
                    else t(0xFFF1F3F4, 0xFFF1F3F4, 0xFFF1F3F4, 0xFFD5D8DC, 0xFF202124, 0xFF80868B, 0xFFFFFFFF, 0xFFC4C7CC, borderless = true)
                else -> if (dark) t(0xFF1F1F21, 0xFF3C3C40, 0xFF2C2C30, 0xFF55555B, 0xFFE8EAED, 0xFF9AA0A6, 0xFF4A4A50, 0xFF4A4A50)
                    else t(0xFFE8EAED, 0xFFFFFFFF, 0xFFCDD1D6, 0xFFBDC1C6, 0xFF202124, 0xFF80868B, 0xFFFFFFFF, 0xFFC4C7CC)
            }
        }

        /** [a] mixed into [b]: [amount] 0 is all [b], 1 all [a]. */
        fun mix(a: Int, b: Int, amount: Float): Int {
            fun ch(shift: Int) = (((a shr shift) and 0xFF) * amount + ((b shr shift) and 0xFF) * (1 - amount)).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

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
