package com.tokalot.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The emoji page, drawn like [KeyboardView]: a search bar on top, the emoji in a scrolling grid by group
 * (recently used first), and along the bottom ABC, a button per group and delete. A long press on an emoji
 * that comes in skin tones offers them; the one picked is remembered for that emoji.
 *
 * While searching it shrinks to one row of results that sits above the letter keys (see [results]).
 */
@SuppressLint("ViewConstructor")
class EmojiPanel(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onEmoji(text: String)
        fun onEmojiDelete()
        fun onEmojiAbc()
        /** The search bar was tapped. */
        fun onEmojiSearch()
        fun onPress(code: Int)
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    var theme = KeyboardTheme.light(Accents.DEFAULT)
        set(v) { field = v; invalidate() }

    /** The groups, with emoji this phone can't draw left out. */
    private var groups: List<EmojiData.Group> = emptyList()
    var data: EmojiData? = null
        set(v) {
            field = v
            val p = Paint()
            groups = v?.groups?.map { g -> EmojiData.Group(g.name, g.emoji.filter { p.hasGlyph(it.text) }) }?.filter { it.emoji.isNotEmpty() } ?: emptyList()
            rebuild()
        }

    /** Recently used, newest first (already in the tone that was used). */
    var recents: List<String> = emptyList()
        set(v) { field = v; rebuild() }

    /** The skin tone picked for each emoji (by its plain form), 1-5. */
    var tones: Map<String, Int> = emptyMap()
        set(v) { field = v; invalidate() }

    /** Search results; non-null while searching, when the panel is one row high. */
    var results: List<EmojiData.Emoji>? = null
        set(v) { field = v; scrollXPos = 0f; requestLayout(); invalidate() }

    /** The panel's height outside search: the keyboard's, so switching pages doesn't make the screen jump. */
    var gridHeight = 0
        set(v) { if (field != v) { field = v; requestLayout() } }

    private val searchBar get() = dp(44f)
    private val bottomBar get() = dp(46f)
    private val resultsRow get() = dp(48f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (results != null) resultsRow.toInt() else max(gridHeight, dp(260f).toInt())
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuild()

    // ------------------------------------------------------------------ the grid's layout

    /** One section of the grid: its title, its emoji, and where it starts (in content pixels). */
    private class Section(val title: String, val emoji: List<String>, val tones: List<Boolean>, val icon: String?) {
        var top = 0f
        var bottom = 0f
    }

    private var sections: List<Section> = emptyList()
    private var cols = 8
    private var cell = 1f
    private val header get() = dp(28f)
    private var contentHeight = 0f
    private var scroll = 0f
    private var scrollXPos = 0f

    private fun rebuild() {
        val list = ArrayList<Section>()
        if (recents.isNotEmpty()) list += Section("Recently used", recents, recents.map { false }, null)
        for (g in groups) list += Section(g.name, g.emoji.map { it.text }, g.emoji.map { it.tones }, g.emoji.first().text)
        sections = list
        if (width == 0) return
        cols = max(6, (width / dp(46f)).toInt())
        cell = width.toFloat() / cols
        var y = 0f
        for (s in sections) {
            s.top = y
            y += header + ((s.emoji.size + cols - 1) / cols) * cell
            s.bottom = y
        }
        contentHeight = y
        scroll = scroll.coerceIn(0f, maxScroll())
        invalidate()
    }

    private val gridTop get() = searchBar
    private val gridBottom get() = height - bottomBar
    private fun maxScroll() = max(0f, contentHeight - (gridBottom - gridTop))

    /** What an emoji looks like here: in the tone picked for it, if it takes one. */
    private fun shown(s: Section, i: Int): String {
        val e = s.emoji[i]
        if (!s.tones[i]) return e
        return EmojiData.withTone(e, tones[e] ?: 0)
    }

    // ------------------------------------------------------------------ drawing

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()
    private val icons = HashMap<Int, Drawable>()
    private fun icon(res: Int): Drawable = icons.getOrPut(res) { context.getDrawable(res)!!.mutate() }

    private fun drawIcon(canvas: Canvas, res: Int, cx: Float, cy: Float, size: Float, color: Int) {
        val d = icon(res)
        d.setTint(color)
        val s = size / 2
        d.setBounds((cx - s).toInt(), (cy - s).toInt(), (cx + s).toInt(), (cy + s).toInt())
        d.draw(canvas)
    }

    private fun centred(canvas: Canvas, s: String, cx: Float, cy: Float) =
        canvas.drawText(s, cx, cy - (text.descent() + text.ascent()) / 2, text)

    override fun onDraw(canvas: Canvas) {
        val t = theme
        canvas.drawColor(t.background)
        val r = results
        if (r != null) { drawResults(canvas, r); return }

        // The search bar.
        rect.set(dp(8f), dp(6f), width - dp(8f), searchBar - dp(4f))
        fill.color = t.key
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, fill)
        drawIcon(canvas, R.drawable.ic_kb_search, rect.left + dp(20f), rect.centerY(), dp(18f), t.hint)
        text.color = t.hint
        text.textSize = dp(15f)
        text.textAlign = Paint.Align.LEFT
        canvas.drawText("Search emoji", rect.left + dp(38f), rect.centerY() - (text.descent() + text.ascent()) / 2, text)
        text.textAlign = Paint.Align.CENTER

        // The grid, clipped between the bars.
        canvas.save()
        canvas.clipRect(0f, gridTop, width.toFloat(), gridBottom.toFloat())
        val top = gridTop - scroll
        for (s in sections) {
            if (top + s.bottom < gridTop || top + s.top > gridBottom) continue
            text.color = t.hint
            text.textSize = dp(13f)
            text.textAlign = Paint.Align.LEFT
            canvas.drawText(s.title, dp(12f), top + s.top + header * 0.68f, text)
            text.textAlign = Paint.Align.CENTER
            text.color = t.text
            text.textSize = cell * 0.6f
            for (i in s.emoji.indices) {
                val cy = top + s.top + header + (i / cols) * cell + cell / 2
                if (cy + cell < gridTop || cy - cell > gridBottom) continue
                centred(canvas, shown(s, i), (i % cols) * cell + cell / 2, cy)
            }
        }
        canvas.restore()

        drawBottomBar(canvas)
        drawToneMenu(canvas)
    }

    /** Where the bottom bar's parts are: ABC, one button per section, delete. */
    private fun barSlots(): FloatArray {
        val side = dp(56f)
        val n = sections.size
        val each = if (n > 0) (width - 2 * side) / n else 0f
        return FloatArray(n + 3) { i ->
            when (i) {
                0 -> 0f
                n + 2 -> width.toFloat()
                else -> side + (i - 1) * each
            }
        }
    }

    private fun drawBottomBar(canvas: Canvas) {
        val t = theme
        val y0 = gridBottom.toFloat()
        val cy = y0 + bottomBar / 2
        fill.color = t.functionKey
        canvas.drawRect(0f, y0, width.toFloat(), height.toFloat(), fill)
        val s = barSlots()
        text.color = t.text
        text.textSize = dp(14f)
        text.typeface = android.graphics.Typeface.DEFAULT_BOLD
        centred(canvas, "ABC", (s[0] + s[1]) / 2, cy)
        text.typeface = android.graphics.Typeface.DEFAULT
        val active = activeSection()
        sections.forEachIndexed { i, sec ->
            val cx = (s[i + 1] + s[i + 2]) / 2
            if (i == active) {
                fill.color = t.accent
                canvas.drawRect(cx - dp(12f), height - dp(4f), cx + dp(12f), height - dp(1.5f), fill)
            }
            val ic = sec.icon
            if (ic == null) drawIcon(canvas, R.drawable.ic_clock, cx, cy, dp(20f), t.text)
            else { text.textSize = dp(19f); centred(canvas, ic, cx, cy) }
        }
        drawIcon(canvas, R.drawable.ic_kb_delete, (s[s.size - 2] + s[s.size - 1]) / 2, cy, dp(22f), t.text)
    }

    private fun activeSection(): Int {
        val y = scroll + dp(1f)
        val i = sections.indexOfLast { it.top <= y }
        return if (scroll >= maxScroll() - 1f && sections.isNotEmpty() && maxScroll() > 0f) sections.size - 1 else max(0, i)
    }

    private fun drawResults(canvas: Canvas, r: List<EmojiData.Emoji>) {
        val t = theme
        val cy = height / 2f
        if (r.isEmpty()) {
            text.color = t.hint
            text.textSize = dp(14f)
            centred(canvas, "No emoji found", width / 2f, cy)
            return
        }
        val c = resultCell()
        text.color = t.text
        text.textSize = c * 0.6f
        r.forEachIndexed { i, e ->
            val cx = i * c + c / 2 - scrollXPos
            if (cx + c < 0 || cx - c > width) return@forEachIndexed
            centred(canvas, if (e.tones) EmojiData.withTone(e.text, tones[e.text] ?: 0) else e.text, cx, cy)
        }
    }

    private fun resultCell() = dp(46f)

    // ---- the skin tone menu

    private class ToneMenu(val base: String, val items: List<String>, val left: Float, val top: Float, val cell: Float) {
        var selected = 0
    }
    private var toneMenu: ToneMenu? = null

    private fun drawToneMenu(canvas: Canvas) {
        val m = toneMenu ?: return
        rect.set(m.left, m.top, m.left + m.cell * m.items.size, m.top + m.cell)
        fill.color = theme.shadow
        canvas.drawRoundRect(rect.left, rect.top + dp(2f), rect.right, rect.bottom + dp(2f), dp(8f), dp(8f), fill)
        fill.color = theme.preview
        canvas.drawRoundRect(rect, dp(8f), dp(8f), fill)
        text.textSize = m.cell * 0.6f
        m.items.forEachIndexed { i, s ->
            val l = m.left + i * m.cell
            if (i == m.selected) {
                fill.color = theme.keyPressed
                canvas.drawRoundRect(l + dp(3f), m.top + dp(3f), l + m.cell - dp(3f), m.top + m.cell - dp(3f), dp(6f), dp(6f), fill)
            }
            centred(canvas, s, l + m.cell / 2, m.top + m.cell / 2)
        }
    }

    // ------------------------------------------------------------------ touch

    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false
    private var downOn: Hit? = null
    private var deleteRepeated = false

    /** What a touch landed on. */
    private sealed class Hit {
        class Emoji(val section: Int, val index: Int) : Hit()
        class Result(val index: Int) : Hit()
        object Search : Hit()
        object Abc : Hit()
        class Group(val section: Int) : Hit()
        object Delete : Hit()
    }

    private fun hit(x: Float, y: Float): Hit? {
        if (results != null) {
            val i = ((x + scrollXPos) / resultCell()).toInt()
            return if (i in results!!.indices) Hit.Result(i) else null
        }
        if (y < gridTop) return Hit.Search
        if (y >= gridBottom) {
            val s = barSlots()
            return when {
                x < s[1] -> Hit.Abc
                x >= s[s.size - 2] -> Hit.Delete
                else -> {
                    val i = (1 until s.size - 2).lastOrNull { x >= s[it] } ?: return null
                    Hit.Group(i - 1)
                }
            }
        }
        val cy = y - gridTop + scroll
        val si = sections.indexOfFirst { cy >= it.top && cy < it.bottom }
        if (si < 0) return null
        val s = sections[si]
        val inside = cy - s.top - header
        if (inside < 0) return null
        val i = (inside / cell).toInt() * cols + (x / cell).toInt().coerceIn(0, cols - 1)
        return if (i in s.emoji.indices) Hit.Emoji(si, i) else null
    }

    private val longPress = Runnable {
        val h = downOn as? Hit.Emoji ?: return@Runnable
        if (dragging) return@Runnable
        val s = sections[h.section]
        if (!s.tones[h.index]) return@Runnable
        val base = s.emoji[h.index]
        val items = listOf(base) + (1..5).map { EmojiData.withTone(base, it) }
        val c = cell
        val cx = (h.index % cols) * cell + cell / 2
        val cy = gridTop - scroll + s.top + header + (h.index / cols) * cell
        val left = (cx - c * items.size / 2).coerceIn(dp(2f), width - c * items.size - dp(2f))
        val top = max(dp(2f), cy - c - dp(4f))
        toneMenu = ToneMenu(base, items, left, top, c).also { it.selected = tones[base] ?: 0 }
        listener.onPress(KeyCode.SHIFT)
        invalidate()
    }

    private val repeatDelete = object : Runnable {
        override fun run() {
            if (downOn !is Hit.Delete) return
            deleteRepeated = true
            listener.onEmojiDelete()
            handler.postDelayed(this, 55)
        }
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            if (results != null) scrollXPos = scroller.currX.toFloat() else scroll = scroller.currY.toFloat()
            invalidate()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                dragging = false
                deleteRepeated = false
                downOn = hit(e.x, e.y)
                when (downOn) {
                    is Hit.Emoji -> handler.postDelayed(longPress, 400)
                    is Hit.Delete -> { listener.onPress(KeyCode.DELETE); handler.postDelayed(repeatDelete, 400) }
                    null -> {}
                    else -> listener.onPress(0)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(e)
                val m = toneMenu
                if (m != null) {
                    val i = ((e.x - m.left) / m.cell).toInt().coerceIn(0, m.items.size - 1)
                    if (i != m.selected) { m.selected = i; invalidate() }
                    return true
                }
                val inGrid = downOn is Hit.Emoji || (downOn == null && downY in gridTop..gridBottom.toFloat()) || downOn is Hit.Result
                if (!dragging && inGrid && (abs(e.y - downY) > slop || abs(e.x - downX) > slop)) {
                    dragging = true
                    handler.removeCallbacks(longPress)
                }
                if (dragging) {
                    if (results != null) {
                        val maxX = max(0f, (results?.size ?: 0) * resultCell() - width)
                        scrollXPos = (scrollXPos - (e.x - lastX)).coerceIn(0f, maxX)
                    } else {
                        scroll = (scroll - (e.y - lastY)).coerceIn(0f, maxScroll())
                    }
                    invalidate()
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                handler.removeCallbacks(repeatDelete)
                val m = toneMenu
                val on = downOn
                downOn = null
                if (m != null) {
                    toneMenu = null
                    val tone = m.selected
                    tones = tones + (m.base to tone)
                    listener.onEmoji(m.items[tone])
                    invalidate()
                } else if (dragging) {
                    velocity?.let { v ->
                        v.computeCurrentVelocity(1000)
                        if (results != null) {
                            val maxX = max(0f, (results?.size ?: 0) * resultCell() - width).toInt()
                            scroller.fling(scrollXPos.toInt(), 0, -v.xVelocity.toInt(), 0, 0, maxX, 0, 0)
                        } else {
                            scroller.fling(0, scroll.toInt(), 0, -v.yVelocity.toInt(), 0, 0, 0, maxScroll().toInt())
                        }
                        postInvalidateOnAnimation()
                    }
                } else when (on) {
                    is Hit.Emoji -> listener.onEmoji(shown(sections[on.section], on.index))
                    is Hit.Result -> results?.getOrNull(on.index)?.let { r ->
                        listener.onEmoji(if (r.tones) EmojiData.withTone(r.text, tones[r.text] ?: 0) else r.text)
                    }
                    is Hit.Search -> listener.onEmojiSearch()
                    is Hit.Abc -> listener.onEmojiAbc()
                    is Hit.Group -> { scroll = min(sections[on.section].top, maxScroll()); invalidate() }
                    is Hit.Delete -> if (!deleteRepeated) listener.onEmojiDelete()
                    null -> {}
                }
                dragging = false
                velocity?.recycle(); velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                handler.removeCallbacks(repeatDelete)
                toneMenu = null; downOn = null; dragging = false
                velocity?.recycle(); velocity = null
                invalidate()
            }
        }
        return true
    }

    /** Back to the top (each time the page is opened). */
    fun reset() {
        scroller.forceFinished(true)
        scroll = 0f; scrollXPos = 0f
        toneMenu = null; downOn = null
        handler.removeCallbacks(longPress); handler.removeCallbacks(repeatDelete)
        invalidate()
    }
}
