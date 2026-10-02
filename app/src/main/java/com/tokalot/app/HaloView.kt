package com.tokalot.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A thin glow of accent color around the button that swells with your voice while listening.
 * It follows the button's rounded-square outline ([inset] = the margin the window keeps
 * around it, [corner] = its corner radius; a large corner makes a capsule) and fills the margin the window keeps around it. Frames are only requested
 * while [listening], so it costs nothing the rest of the time.
 */
class HaloView(
    context: Context,
    private val inset: Float,
    private val corner: Float,
) : View(context) {

    /** Returns the current mic level 0..1 (RMS), the same source the bars use. */
    var level: (() -> Float)? = null

    var color: Int = Color.WHITE
        set(v) {
            field = v
            paint.color = v
            invalidate()
        }

    var listening = false
        set(v) {
            if (field == v) return
            field = v
            smooth = 0f
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val disc = Path()   // the button itself, kept clear so the glow never tints it
    private val box = RectF()
    private val tmp = RectF()
    private var smooth = 0f
    private var radius = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        box.set(inset, inset, w - inset, h - inset)
        radius = min(corner, min(box.width(), box.height()) / 2f)
        disc.rewind()
        disc.addRoundRect(box, radius, radius, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        if (!listening) return
        // sqrt makes quiet speech still visibly move the glow, same as the bars.
        val raw = sqrt(((level?.invoke() ?: 0f) * 6f).coerceIn(0f, 1f))
        smooth += (raw - smooth) * 0.3f
        // Total opacity right at the button's edge: 0.2 when quiet up to 0.85 when loud.
        val total = (0.2f + 0.65f * smooth).coerceIn(0f, 0.85f)
        // Stacked, slightly larger layers whose opacity fades outward add up to a smooth falloff.
        // Layer i has opacity p0 * (1 - (i - 1) / STEPS); p0 is chosen so they sum to `total`.
        val p0 = (-ln(1f - total) * 2f / (STEPS + 1)).coerceAtMost(1f)
        val reach = inset * 0.92f // stay just inside the window edge
        canvas.save()
        canvas.clipOutPath(disc)
        for (i in STEPS downTo 1) {
            val e = reach * i / STEPS
            paint.alpha = (p0 * (1f - (i - 1f) / STEPS) * 255f).toInt().coerceIn(0, 255)
            tmp.set(box.left - e, box.top - e, box.right + e, box.bottom + e)
            canvas.drawRoundRect(tmp, radius + e, radius + e, paint)
        }
        canvas.restore()
        postInvalidateOnAnimation()
    }

    private companion object {
        const val STEPS = 10
    }
}
