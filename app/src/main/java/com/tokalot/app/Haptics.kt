package com.tokalot.app

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Small haptic vocabulary for the floating button. Uses the vibration motor directly
 * (View.performHapticFeedback is often muted or very faint on Samsung phones).
 */
object Haptics {
    enum class Kind { START, STOP, DONE, CANCEL, ERROR, TICK }

    fun play(ctx: Context, kind: Kind) {
        if (!Prefs(ctx).haptics) return
        @Suppress("DEPRECATION")
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = if (Build.VERSION.SDK_INT >= 29) {
            when (kind) {
                Kind.START -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                Kind.STOP, Kind.TICK -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                Kind.DONE -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
                Kind.CANCEL -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
                Kind.ERROR -> VibrationEffect.createWaveform(longArrayOf(0, 40, 70, 40), -1)
            }
        } else {
            when (kind) {
                Kind.START -> VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE)
                Kind.STOP, Kind.TICK -> VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE)
                Kind.DONE -> VibrationEffect.createWaveform(longArrayOf(0, 18, 60, 18), -1)
                Kind.CANCEL -> VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE)
                Kind.ERROR -> VibrationEffect.createWaveform(longArrayOf(0, 40, 70, 40), -1)
            }
        }
        runCatching { v.vibrate(effect) }
    }
}
