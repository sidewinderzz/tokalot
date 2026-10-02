package com.tokalot.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A thin ring of accent-colored light around the button that swells with your voice while
 * listening. It fills the margin the window keeps around the disc. Frames are only requested
 * while [listening], so it costs nothing the rest of the time.
 */
class HaloView(context: Context, private val discRadius: Float) : View(context) {

    /** Returns the current mic level 0..1 (RMS), the same source the bars use. */
    var level: (() -> Float)? = null

    var color: Int = Color.WHITE
        set(v) {
            field = v
            buildShader()
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
    private var smooth = 0f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = buildShader()

    private fun buildShader() {
        val outer = min(width, height) / 2f
        if (outer <= 0f) return
        val edge = (discRadius / outer).coerceIn(0.1f, 0.95f)
        val clear = color and 0x00FFFFFF // same hue at zero alpha, so the fade doesn't pass through grey
        paint.shader = RadialGradient(
            width / 2f, height / 2f, outer,
            intArrayOf(clear, clear, color or 0xFF000000.toInt(), clear),
            floatArrayOf(0f, edge - 0.02f, edge, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (!listening) return
        // sqrt makes quiet speech still visibly move the ring, same as the bars.
        val raw = sqrt(((level?.invoke() ?: 0f) * 6f).coerceIn(0f, 1f))
        smooth += (raw - smooth) * 0.3f
        paint.alpha = ((0.2f + 0.65f * smooth).coerceIn(0f, 0.85f) * 255).toInt()
        canvas.drawCircle(width / 2f, height / 2f, min(width, height) / 2f, paint)
        postInvalidateOnAnimation()
    }
}
