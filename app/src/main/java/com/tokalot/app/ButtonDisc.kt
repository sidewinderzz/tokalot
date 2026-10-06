package com.tokalot.app

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
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
 * square (inside the window) for that shadow; [corner] is its corner
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

    /**
     * While transcribing: how far along it is, 0..1, drawn as a thin line that travels round the edge of
     * the square from the top centre, clockwise. Negative hides it.
     */
    var progress = -1f
        set(v) {
            if (field != v) {
                field = v
                invalidateSelf()
            }
        }

    private val ringTrack = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x1FFFFFFF }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0xD9FFFFFF.toInt(); strokeCap = Paint.Cap.ROUND
    }
    private val ringPath = Path()
    private val ringPart = Path()
    private val ringMeasure = PathMeasure()

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
            else intArrayOf(0xFF505055.toInt(), 0xFF333336.toInt()), // resting: a lighter, softer graphite
            null, Shader.TileMode.CLAMP
        )
        // The edge of the square as one line, starting and ending at the top centre.
        val w = hairline * 2f
        ringTrack.strokeWidth = w
        ring.strokeWidth = w
        val l = box.left + w / 2f; val t = box.top + w / 2f; val r = box.right - w / 2f; val b = box.bottom - w / 2f
        val c = (radius - w / 2f).coerceAtLeast(0f)
        ringPath.reset()
        ringPath.moveTo((l + r) / 2f, t)
        ringPath.lineTo(r - c, t)
        ringPath.arcTo(r - 2 * c, t, r, t + 2 * c, -90f, 90f, false)
        ringPath.lineTo(r, b - c)
        ringPath.arcTo(r - 2 * c, b - 2 * c, r, b, 0f, 90f, false)
        ringPath.lineTo(l + c, b)
        ringPath.arcTo(l, b - 2 * c, l + 2 * c, b, 90f, 90f, false)
        ringPath.lineTo(l, t + c)
        ringPath.arcTo(l, t, l + 2 * c, t + 2 * c, 180f, 90f, false)
        ringPath.lineTo((l + r) / 2f, t)
        ringMeasure.setPath(ringPath, false)

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
        val spread = inset * 0.5f
        shadow.color = if (active) 0x04000000 else 0x03000000 // a faint shadow, just enough to lift it off light apps
        for (i in SHADOW_STEPS downTo 1) {
            val e = spread * i / SHADOW_STEPS
            tmp.set(box.left - e, box.top - e + drop, box.right + e, box.bottom + e + drop)
            canvas.drawRoundRect(tmp, radius + e, radius + e, shadow)
        }
        canvas.drawRoundRect(box, radius, radius, fill)
        if (!active) return // resting: no rim, so it sits quietly
        if (progress >= 0f) {
            canvas.drawPath(ringPath, ringTrack)
            ringPart.reset()
            ringMeasure.getSegment(0f, ringMeasure.length * progress.coerceIn(0f, 1f), ringPart, true)
            canvas.drawPath(ringPart, ring)
            return // the ring takes the rim's place
        }
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
