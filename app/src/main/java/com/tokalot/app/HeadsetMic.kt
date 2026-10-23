package com.tokalot.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * A Bluetooth headset's mic (Settings › Recording › Microphone). Android only lets an app record from a headset over
 * the "phone call" link (SCO), so while it's on, music pauses and the sound is call quality, which is plenty for
 * speech. Opening the link takes about a second; with no headset connected, or if it doesn't open, the phone's own mic
 * is used as before.
 */
object HeadsetMic {
    private const val WAIT_MS = 4000L
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var open = false

    private fun am(ctx: Context) = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** The connected headset's mic, if there is one. */
    fun find(ctx: Context): AudioDeviceInfo? =
        am(ctx).getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

    /**
     * Opens the link to the headset and hands its mic to [done] on the main thread, or null when there's no headset or
     * the link didn't open in time (nothing is left open then).
     */
    fun connect(ctx: Context, done: (AudioDeviceInfo?) -> Unit) {
        val app = ctx.applicationContext
        val am = am(app)
        if (find(app) == null) { done(null); return }
        var finished = false
        fun finish(ok: Boolean) {
            if (finished) return
            finished = true
            main.removeCallbacksAndMessages(null)
            if (!ok) { release(app); done(null); return }
            open = true
            done(find(app))
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val out = am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            if (out == null || !runCatching { am.setCommunicationDevice(out) }.getOrDefault(false)) { done(null); return }
            open = true // so a failure below clears it
            val start = System.currentTimeMillis()
            fun check() {
                when {
                    am.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> main.postDelayed({ finish(true) }, 300)
                    System.currentTimeMillis() - start > WAIT_MS -> finish(false)
                    else -> main.postDelayed({ check() }, 100)
                }
            }
            check()
            return
        }
        @Suppress("DEPRECATION")
        run {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    when (i.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)) {
                        AudioManager.SCO_AUDIO_STATE_CONNECTED -> { runCatching { app.unregisterReceiver(this) }; am.isBluetoothScoOn = true; finish(true) }
                        AudioManager.SCO_AUDIO_STATE_ERROR -> { runCatching { app.unregisterReceiver(this) }; finish(false) }
                    }
                }
            }
            app.registerReceiver(receiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
            open = true
            if (runCatching { am.startBluetoothSco() }.isFailure) { runCatching { app.unregisterReceiver(receiver) }; finish(false); return }
            main.postDelayed({ runCatching { app.unregisterReceiver(receiver) }; finish(false) }, WAIT_MS)
        }
    }

    /** Closes the link to the headset, if this opened one. Music carries on. */
    fun release(ctx: Context) {
        if (!open) return
        open = false
        val am = am(ctx.applicationContext)
        if (Build.VERSION.SDK_INT >= 31) runCatching { am.clearCommunicationDevice() }
        else @Suppress("DEPRECATION") runCatching { am.isBluetoothScoOn = false; am.stopBluetoothSco() }
    }
}
