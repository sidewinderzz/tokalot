package com.tokalot.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View

/**
 * The faint "slide up to cancel" guide shown while recording: a dotted line rising from the button
 * to a small X. As the button is slid toward it ([progress] 0..1) the line brightens and the X
 * grows and turns red; past [ARM] a release cancels.
 *
 * Drawn in its own untouchable window that sits directly above the button (or below it, when
 * [up] is false because the button is too close to the top of the screen).
 */
class CancelTrackView(context: Context, private val startGap: Float, private val xRadius: Float) : View(context) {

    var progress = 0f
        set(v) { if (field != v) { field = v; invalidate() } }

    var up = true
        set(v) { if (field != v) { field = v; invalidate() } }

    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(0.1f, 6f * d), 0f) // round dots
    }
    private val disc = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        strokeCap = Paint.Cap.ROUND
        color = 0xFFF2F2F5.toInt()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val armed = progress >= ARM
        // Ends of the track: from just past the button's edge to the X's centre.
        val near = if (up) height - startGap else startGap
        // Leave room for the X at full size (it grows 30%) plus its shadow, so it stays perfectly round.
        val room = xRadius * 1.3f + 6 * d
        val far = if (up) room else height - room

        // Dots: dim overall, brighter along the part the button has already travelled.
        line.color = 0x59FFFFFF   // ~35% white
        line.setShadowLayer(2f * d, 0f, 0f, 0x66000000) // keeps the dots visible on light apps
        canvas.drawLine(cx, near, cx, far + (if (up) xRadius * 1.3f else -xRadius * 1.3f), line)

        // The X: a small dark disc that grows and turns red as you get close.
        val grow = 1f + 0.3f * progress
        val r = xRadius * grow
        disc.color = if (armed) 0xF2D93A3A.toInt() else blend(0xB31C1C1E.toInt(), 0xE6B23A3A.toInt(), progress)
        disc.setShadowLayer(3f * d, 0f, d, 0x55000000)
        canvas.drawCircle(cx, far, r, disc)
        val s = r * 0.38f
        cross.alpha = (150 + 105 * progress).toInt().coerceAtMost(255)
        canvas.drawLine(cx - s, far - s, cx + s, far + s, cross)
        canvas.drawLine(cx + s, far - s, cx - s, far + s, cross)
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        fun ch(shift: Int) = (((a ushr shift) and 0xFF) + (((b ushr shift) and 0xFF) - ((a ushr shift) and 0xFF)) * t).toInt() and 0xFF
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    companion object {
        /** How far along the track a release cancels. */
        const val ARM = 0.85f
    }
}
