package com.tokalot.app

import android.graphics.Canvas
import android.graphics.RadialGradient
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.min

/**
 * The floating button's surface: a graphite disc lit from above, with a fine rim that catches
 * light along its top edge and a soft shadow underneath.
 *
 * It is drawn by hand rather than with view elevation, because the button lives in an overlay
 * window where elevation shadows are unreliable. [inset] is the margin kept free around the disc
 * (inside the window) for that shadow and for the listening halo.
 */
class ButtonDisc(private val inset: Float, private val hairline: Float) : Drawable() {

    /** Listening / transcribing: the disc goes a shade darker, as if pressed in. */
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
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG)
    private var cx = 0f
    private var cy = 0f
    private var r = 0f
    private val drop get() = inset * 0.3f          // the shadow sits a little below the disc
    private val shadowR get() = r + inset * 0.7f   // and ends inside the window, so it is never clipped

    override fun onBoundsChange(bounds: Rect) {
        cx = bounds.exactCenterX()
        cy = bounds.exactCenterY()
        r = min(bounds.width(), bounds.height()) / 2f - inset
        rebuild()
    }

    private fun rebuild() {
        if (r <= 0f) return
        val top = cy - r
        val bottom = cy + r
        fill.shader = LinearGradient(
            0f, top, 0f, bottom,
            if (active) intArrayOf(0xFF2A2A2E.toInt(), 0xFF0F0F11.toInt())
            else intArrayOf(0xFF3C3C41.toInt(), 0xFF1F1F22.toInt()),
            null, Shader.TileMode.CLAMP
        )
        rim.strokeWidth = hairline
        rim.shader = LinearGradient(
            0f, top, 0f, bottom,
            intArrayOf(0x80FFFFFF.toInt(), 0x14FFFFFF),
            null, Shader.TileMode.CLAMP
        )
        // One smooth falloff (a stack of discs shows visible steps on light backgrounds).
        val span = shadowR - r
        shadow.shader = RadialGradient(
            cx, cy + drop, shadowR,
            intArrayOf(0x4D000000, 0x4D000000, 0x2B000000, 0x0F000000, 0x00000000),
            floatArrayOf(0f, r / shadowR, (r + span * 0.25f) / shadowR, (r + span * 0.55f) / shadowR, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        if (r <= 0f) return
        canvas.drawCircle(cx, cy + drop, shadowR, shadow)
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r - hairline / 2f, rim)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Suppress("OVERRIDE_DEPRECATION")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
