package com.tokalot.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The app-icon waveform (five bars) drawn live. Idle it sits still, which costs nothing.
 * While listening the bars follow your voice level; while transcribing they ripple.
 * Animation frames are only requested while listening/working, so idle uses no battery.
 */
class BarsView(context: Context) : View(context) {

    enum class Mode { IDLE, LISTENING, WORKING }

    var mode = Mode.IDLE
        set(v) {
            field = v
            startTime = System.nanoTime()
            invalidate()
        }

    /** Bar color (white when idle/working, the accent color while listening). */
    var barColor: Int = Color.WHITE
        set(v) { field = v; paint.color = v; invalidate() }

    /** Returns the current mic level 0..1 (RMS). */
    var level: (() -> Float)? = null

    // Same geometry as the launcher icon (108-unit grid), layout A.
    private val base = floatArrayOf(16f, 36f, 22f, 44f, 16f)
    private val xs = floatArrayOf(30f, 41f, 52f, 63f, 74f)
    private val gain = floatArrayOf(0.55f, 0.85f, 0.7f, 1f, 0.5f)
    private val cur = base.copyOf()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val r = RectF()
    private var startTime = System.nanoTime()
    private var smoothLevel = 0f

    override fun onDraw(canvas: Canvas) {
        val s = width / 108f
        val t = (System.nanoTime() - startTime) / 1e9f
        if (mode == Mode.LISTENING) {
            // sqrt makes quiet speech still visibly move the bars.
            val raw = sqrt(((level?.invoke() ?: 0f) * 6f).coerceIn(0f, 1f))
            smoothLevel += (raw - smoothLevel) * 0.4f
        }
        var settling = false
        for (i in 0 until 5) {
            val target = when (mode) {
                Mode.IDLE -> base[i]
                Mode.LISTENING -> 10f + smoothLevel * 52f * gain[i] + 3f * sin(t * 9f + i * 1.7f)
                Mode.WORKING -> 12f + 34f * (0.5f + 0.5f * sin((t * 1.6f - i * 0.18f) * 2f * PI.toFloat()))
            }
            cur[i] += (target - cur[i]) * 0.3f
            if (kotlin.math.abs(target - cur[i]) > 0.3f) settling = true
            val h = cur[i].coerceIn(6f, 64f) * s
            val cx = (xs[i] + 3f) * s
            r.set(cx - 3f * s, height / 2f - h / 2f, cx + 3f * s, height / 2f + h / 2f)
            canvas.drawRoundRect(r, 3f * s, 3f * s, paint)
        }
        if (mode != Mode.IDLE || settling) postInvalidateOnAnimation()
    }
}
