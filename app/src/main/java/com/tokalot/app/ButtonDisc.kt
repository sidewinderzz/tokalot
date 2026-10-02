package com.tokalot.app

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.min

/**
 * The floating button's surface: a graphite rounded square (or capsule, with a large corner) lit from above, with a fine rim that
 * catches light along its top edge and a soft shadow underneath.
 *
 * It is drawn by hand rather than with view elevation, because the button lives in an overlay
 * window where elevation shadows are unreliable. [inset] is the margin kept free around the
 * square (inside the window) for that shadow and for the listening halo; [corner] is its corner
 * radius.
 */
class ButtonDisc(
    private val inset: Float,
    private val hairline: Float,
    private val corner: Float,
) : Drawable() {

    /** Listening / transcribing: the surface goes a shade darker, as if pressed in. */
    var active = false
        set(v) {
            if (field != v) {
                field = v
                rebuild()
                invalidateSelf()
            }
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    // One very faint layer; SHADOW_STEPS of them, each a little larger, build a smooth falloff
    // (a few big steps would show as visible bands on light backgrounds).
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x08000000 }
    private val box = RectF()   // the visible square
    private val tmp = RectF()
    private var half = 0f
    private var radius = 0f

    override fun onBoundsChange(bounds: Rect) {
        box.set(
            bounds.left + inset, bounds.top + inset,
            bounds.right - inset, bounds.bottom - inset
        )
        half = min(box.width(), box.height()) / 2f
        radius = min(corner, half) // a pill passes a huge corner and gets full capsule ends
        rebuild()
    }

    private fun rebuild() {
        if (half <= 0f) return
        fill.shader = LinearGradient(
            0f, box.top, 0f, box.bottom,
            if (active) intArrayOf(0xFF2A2A2E.toInt(), 0xFF0F0F11.toInt())
            else intArrayOf(0xFF3C3C41.toInt(), 0xFF1F1F22.toInt()),
            null, Shader.TileMode.CLAMP
        )
        rim.strokeWidth = hairline
        rim.shader = LinearGradient(
            0f, box.top, 0f, box.bottom,
            intArrayOf(0x80FFFFFF.toInt(), 0x14FFFFFF),
            null, Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        if (half <= 0f) return
        // The shadow sits a little below the square, and ends inside the window so it is never clipped.
        val drop = inset * 0.3f
        val spread = inset * 0.7f
        for (i in SHADOW_STEPS downTo 1) {
            val e = spread * i / SHADOW_STEPS
            tmp.set(box.left - e, box.top - e + drop, box.right + e, box.bottom + e + drop)
            canvas.drawRoundRect(tmp, radius + e, radius + e, shadow)
        }
        canvas.drawRoundRect(box, radius, radius, fill)
        tmp.set(box)
        tmp.inset(hairline / 2f, hairline / 2f)
        canvas.drawRoundRect(tmp, radius - hairline / 2f, radius - hairline / 2f, rim)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        const val SHADOW_STEPS = 12
    }
}
