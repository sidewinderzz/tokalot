package com.tokalot.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The desktop app's default "Ripple pill", ported unchanged: a slim dark pill that sits [GAP] units
 * off a screen edge, whose top (the side facing into the screen) swells and ripples with your voice.
 * Idle it is a small quiet capsule; listening it is wider with a light top; transcribing it ripples slowly.
 *
 * Like the desktop, shapes are built in "edge space" (x along the edge, y = 0 at the edge, negative y
 * pointing into the screen) and then laid onto the bottom, left or right [dock]. Frames are only
 * requested while listening or transcribing.
 */
class PillView(context: Context) : View(context) {

    enum class Mode { IDLE, LISTENING, WORKING }

    var mode = Mode.IDLE
        set(v) { field = v; invalidate() }

    /** "bottom", "left" or "right": which screen edge the pill is attached to. */
    var dock = "bottom"
        set(v) { field = v; invalidate() }

    /** Returns the current mic level 0..1 (RMS). */
    var level: (() -> Float)? = null

    private val k = resources.displayMetrics.density * SCALE   // pixels per edge-space unit
    private val start = System.nanoTime()
    private var lv = 0f
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f / SCALE }
    private val path = Path()
    private val bounds = RectF()
    private val m = Matrix()
    private val v = FloatArray(9)

    override fun onDraw(canvas: Canvas) {
        val t = (System.nanoTime() - start) / 1e9f
        val idle = mode == Mode.IDLE
        val work = mode == Mode.WORKING
        if (mode == Mode.LISTENING) {
            val target = min(1f, sqrt(max(0f, level?.invoke() ?: 0f) * 8f))
            lv += (target - lv) * 0.35f
        } else lv = 0f
        val sp = if (work) 0.35f else 1f
        val amp = if (idle) 0f else if (work) 3.5f else 2f + lv * 18f
        val w = if (idle) 40f else 100f
        val h = if (idle) 6f else 16f

        // The pill, top edge rippling (same construction as the desktop's Pill()).
        val r = h / 2f
        val top = -GAP - h
        val cy = top + r
        path.rewind()
        var x = r
        var first = true
        while (x <= w - r + 0.01f) {
            val y = top - wave(x, w, r, amp, t, sp)
            if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            x += 3f
        }
        for (i in 0..14) {
            val a = -PI.toFloat() / 2 + PI.toFloat() * i / 14
            path.lineTo(w - r + r * cos(a), cy + r * sin(a))
        }
        for (i in 0..14) {
            val a = PI.toFloat() / 2 + PI.toFloat() * i / 14
            path.lineTo(r + r * cos(a), cy + r * sin(a))
        }
        path.close()
        path.computeBounds(bounds, true)

        fill.shader = null
        if (idle) {
            fill.color = 0xFF2E2E33.toInt()
        } else {
            val colors = if (work) intArrayOf(0xFF4A4A50.toInt(), 0xFF1C1C20.toInt(), 0xFF0F0F11.toInt())
            else intArrayOf(0xE6F2F2F7.toInt(), 0xFF232327.toInt(), 0xFF0F0F11.toInt())
            fill.shader = LinearGradient(0f, bounds.top, 0f, bounds.bottom, colors, floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        }
        rim.color = Color.argb(((if (idle) 0.12f else 0.1f) * 255).toInt(), 255, 255, 255)

        // Lay edge space onto the window for this dock (same transforms as the desktop).
        val l = SPAN
        val d = DEPTH
        when (dock) {
            "left" -> v.setAll(0f, -1f, 0f, 1f, 0f, (l - w) / 2f)
            "right" -> v.setAll(0f, 1f, d, -1f, 0f, (l + w) / 2f)
            else -> v.setAll(1f, 0f, (l - w) / 2f, 0f, 1f, d)
        }
        m.setValues(v)
        canvas.save()
        canvas.scale(k, k)
        canvas.concat(m)
        canvas.drawPath(path, fill)
        canvas.drawPath(path, rim)
        canvas.restore()

        if (!idle) postInvalidateOnAnimation()
    }

    private fun FloatArray.setAll(a: Float, c: Float, tx: Float, b: Float, d: Float, ty: Float) {
        this[0] = a; this[1] = c; this[2] = tx
        this[3] = b; this[4] = d; this[5] = ty
        this[6] = 0f; this[7] = 0f; this[8] = 1f
    }

    private fun wave(x: Float, w: Float, r: Float, amp: Float, t: Float, sp: Float): Float {
        val taper = sin(PI.toFloat() * (x - r) / (w - 2 * r))
        val s = 0.55f * sin(x * 0.11f - t * 10f * sp) + 0.3f * sin(x * 0.23f + t * 7f * sp) + 0.15f * sin(x * 0.05f - t * 4f * sp)
        return amp * taper * (0.45f + 0.55f * s)
    }

    companion object {
        const val SCALE = 1.3f   // dp per unit, the desktop's default scale
        const val GAP = 8f       // units between the edge and the pill
        const val SPAN = 124f    // window length along the edge: the wide pill (100) plus room
        const val DEPTH = 50f    // window depth into the screen: gap + pill + the deepest swell
    }
}
