package com.tokalot.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Feedback sounds for the floating button, synthesized in code (no audio files to ship or license).
 * The same sets as the desktop apps' Tones.cs, so a style sounds the same on every device. Off by default.
 */
object Sounds {
    class Style(val id: String, val label: String, val note: String)

    val styles = listOf(
        Style("soft", "Soft", "Gentle beeps."),
        Style("chime", "Chime", "Little bells that ring out."),
        Style("retro", "Retro", "8-bit game blips."),
        Style("bubble", "Bubble", "Pops that slide up and down."),
    )

    fun valid(id: String?): String = if (styles.any { it.id == id }) id!! else styles[0].id

    private const val RATE = 44100

    /** How much of the start of a recording the start sound can reach the mic in, in Recorder samples. */
    const val CUE_SAMPLES = Recorder.SAMPLE_RATE * 6 / 10

    private enum class Voice { SINE, BELL, SQUARE, GLIDE }

    /** One note: start and end pitch in Hz (they differ only for a glide; 0 is a rest) and its length. */
    private class Note(val f: Double, val f2: Double, val ms: Int) {
        constructor(f: Double, ms: Int) : this(f, f, ms)
    }

    private val cache = HashMap<String, ShortArray>()

    fun play(ctx: Context, kind: Haptics.Kind, style: String? = null) {
        if (kind == Haptics.Kind.TICK) return
        val prefs = Prefs(ctx)
        if (!prefs.sounds) return
        val set = valid(style ?: prefs.soundSet)
        Thread {
            runCatching {
                val pcm = synchronized(cache) { cache.getOrPut("$set-$kind") { pcm(set, kind) } }
                val track = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(pcm.size * 2)
                    .build()
                try {
                    track.write(pcm, 0, pcm.size)
                    track.play()
                    Thread.sleep(pcm.size * 1000L / RATE + 50)
                } finally {
                    track.release()
                }
            }
        }.start()
    }

    /** Length of a sound in ms. */
    fun length(set: String, kind: Haptics.Kind): Int = score(valid(set), kind).second.sumOf { it.ms }

    fun pcm(set: String, kind: Haptics.Kind): ShortArray {
        val (voice, notes) = score(valid(set), kind)
        val out = ShortArray(notes.sumOf { RATE * it.ms / 1000 })
        var at = 0
        for (n in notes) at = render(out, at, voice, n)
        return out
    }

    private fun score(set: String, kind: Haptics.Kind): Pair<Voice, List<Note>> = when (set) {
        "chime" -> Voice.BELL to when (kind) {
            Haptics.Kind.START -> listOf(Note(1319.0, 70), Note(1976.0, 200))
            Haptics.Kind.STOP -> listOf(Note(1568.0, 160))
            Haptics.Kind.DONE -> listOf(Note(1319.0, 70), Note(1568.0, 70), Note(2093.0, 260))
            Haptics.Kind.CANCEL -> listOf(Note(1175.0, 90), Note(880.0, 200))
            else -> listOf(Note(622.0, 160), Note(587.0, 260))
        }
        "retro" -> Voice.SQUARE to when (kind) {
            Haptics.Kind.START -> listOf(Note(988.0, 45), Note(1319.0, 80))
            Haptics.Kind.STOP -> listOf(Note(1319.0, 40), Note(988.0, 50))
            Haptics.Kind.DONE -> listOf(Note(1047.0, 45), Note(1319.0, 45), Note(1568.0, 45), Note(2093.0, 90))
            Haptics.Kind.CANCEL -> listOf(Note(659.0, 60), Note(494.0, 80))
            else -> listOf(Note(196.0, 110), Note(0.0, 40), Note(147.0, 160))
        }
        "bubble" -> Voice.GLIDE to when (kind) {
            Haptics.Kind.START -> listOf(Note(400.0, 900.0, 110))
            Haptics.Kind.STOP -> listOf(Note(900.0, 500.0, 90))
            Haptics.Kind.DONE -> listOf(Note(500.0, 1000.0, 70), Note(0.0, 25), Note(700.0, 1400.0, 90))
            Haptics.Kind.CANCEL -> listOf(Note(700.0, 300.0, 140))
            else -> listOf(Note(300.0, 180.0, 140), Note(0.0, 40), Note(300.0, 160.0, 160))
        }
        else -> Voice.SINE to when (kind) {
            Haptics.Kind.START -> listOf(Note(660.0, 55), Note(990.0, 70))
            Haptics.Kind.STOP -> listOf(Note(880.0, 45))
            Haptics.Kind.DONE -> listOf(Note(784.0, 50), Note(1175.0, 80))
            Haptics.Kind.CANCEL -> listOf(Note(520.0, 70))
            else -> listOf(Note(330.0, 90), Note(0.0, 40), Note(330.0, 90))
        }
    }

    private fun render(out: ShortArray, start: Int, voice: Voice, note: Note): Int {
        val n = RATE * note.ms / 1000
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            val f = if (note.f == note.f2) note.f else note.f * (note.f2 / note.f).pow(i.toDouble() / n)
            phase += 2 * PI * f / RATE
            val v = if (note.f == 0.0) 0.0 else when (voice) {
                // Gentle and quiet: soft attack and release.
                Voice.SINE -> sin(phase) * ease(i, n, 0.006, 0.03) * 0.12
                // Struck, then rings down; the inharmonic overtone is what makes it a bell.
                Voice.BELL -> (sin(phase) + 0.35 * sin(phase * 2.76) * exp(-t / 0.04)) * exp(-t / 0.09) * ease(i, n, 0.002, 0.012) * 0.1
                // A few odd harmonics: square-ish without the harsh top end. Squares sound louder, so it's quieter.
                Voice.SQUARE -> (sin(phase) + sin(3 * phase) / 3 + sin(5 * phase) / 5) * ease(i, n, 0.003, 0.015) * 0.07
                // A sine whose pitch slides, rounded off at both ends.
                Voice.GLIDE -> sin(phase) * sin(PI * i / n) * 0.13
            }
            out[start + i] = (v * Short.MAX_VALUE).toInt().toShort()
        }
        return start + n
    }

    /** 0..1 ramp in over attack seconds and out over release seconds. */
    private fun ease(i: Int, n: Int, attack: Double, release: Double): Double =
        min(1.0, min(i / (RATE * attack), (n - i) / (RATE * release)))
}
