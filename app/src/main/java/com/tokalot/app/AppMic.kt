package com.tokalot.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast

/**
 * Recording from inside the app, for people who use Tokalot without the floating button or the keyboard
 * ([UseMode.APP]). Tap Record on Home, talk, tap Stop: the words land on Home with the other dictations and are
 * copied. Needs only the mic permission and a speech service, never the accessibility switch. The recording
 * notification's Stop button works too, so it keeps going if the app is left. Up to 5 minutes, like the mic button.
 */
object AppMic {
    enum class State { IDLE, RECORDING, WORKING }

    var state = State.IDLE
        private set

    /** When the recording started ([SystemClock.elapsedRealtime]), for the timer on the button. */
    var startedAt = 0L
        private set

    /** Called on the main thread whenever [state] changes. */
    var onChange: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var recorder: Recorder? = null
    private var dictation: Dictation? = null
    private lateinit var app: Context

    /** Why recording can't start yet, or null when it can. */
    fun missing(ctx: Context): String? {
        val p = Prefs(ctx)
        return when {
            ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> "Allow the microphone first (Settings › Setup)"
            !(p.cloudSttReady || ModelManager.isReady(ctx)) -> "Add a speech key or download the on-device model first (Settings › Setup)"
            else -> null
        }
    }

    fun toggle(ctx: Context) = when (state) {
        State.IDLE -> start(ctx)
        State.RECORDING -> stop()
        State.WORKING -> cancel()
    }

    private fun start(ctx: Context) {
        app = ctx.applicationContext
        missing(app)?.let { Toast.makeText(app, it, Toast.LENGTH_LONG).show(); return }
        val r = Recorder()
        r.onFull = { main.post { if (recorder === r) stop() } }
        r.onLost = { main.post { if (recorder === r) stop() } }
        r.cueSamples = if (Prefs(app).sounds) Sounds.CUE_SAMPLES else 0
        runCatching { RecordingService.start(app) }
        if (!r.start()) {
            RecordingService.stop(app)
            Haptics.play(app, Haptics.Kind.ERROR)
            Toast.makeText(app, "Couldn't open the microphone", Toast.LENGTH_SHORT).show()
            return
        }
        recorder = r
        startedAt = SystemClock.elapsedRealtime()
        (dictation ?: Dictation(app).also { dictation = it }).warmUp()
        Haptics.play(app, Haptics.Kind.START)
        set(State.RECORDING)
    }

    /** The recording notification's Stop button. */
    fun stopFromNotification() {
        main.post { if (state == State.RECORDING) stop() }
    }

    private fun stop() {
        val r = recorder ?: return
        recorder = null
        Haptics.play(app, Haptics.Kind.STOP)
        val speech = r.speechSamples
        val taken = r.stopMic()
        RecordingService.stop(app)
        set(State.WORKING)
        // A long recording is put together off the main thread.
        Thread {
            val audio = taken.audio()
            main.post { transcribe(audio, speech) }
        }.start()
    }

    private fun cancel() {
        dictation?.cancel()
    }

    private fun transcribe(audio: Recorder.Audio, speech: Int) {
        if (state != State.WORKING) return
        val samples = audio.samples
        val heard = speech >= Recorder.SAMPLE_RATE * 3 / 10
        val problem = when {
            samples.size < Recorder.SAMPLE_RATE / 2 -> "Too short. Tap Record, talk, then tap Stop."
            audio.peak < 0.0005f -> "The mic only picked up silence"
            !heard && samples.size < Recorder.SAMPLE_RATE * 5 / 2 -> "Didn't catch anything"
            else -> null
        }
        if (problem != null) {
            set(State.IDLE)
            Toast.makeText(app, problem, Toast.LENGTH_SHORT).show()
            return
        }
        val d = dictation ?: Dictation(app).also { dictation = it }
        d.process(samples, null, sparse = !heard) { outcome, err ->
            set(State.IDLE)
            when {
                err == Dictation.CANCELLED -> Haptics.play(app, Haptics.Kind.CANCEL)
                err == Dictation.TIMED_OUT -> { Haptics.play(app, Haptics.Kind.ERROR); toast("Timed out. It's on Home to try again.") }
                err != null -> { Haptics.play(app, Haptics.Kind.ERROR); toast(err) }
                outcome == null || outcome.text.isEmpty() -> toast("Didn't catch anything")
                else -> {
                    app.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Tokalot", outcome.text))
                    Haptics.play(app, Haptics.Kind.DONE)
                    toast(outcome.warning ?: "Done. Copied, and saved on Home.")
                }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(app, msg, Toast.LENGTH_LONG).show()

    private fun set(s: State) {
        state = s
        onChange?.invoke()
    }
}
