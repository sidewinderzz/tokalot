package com.tokalot.app

import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.sqrt

/**
 * Transcribes a long dictation while it is still being spoken. Each time the speaker pauses, the part
 * said so far is sent to the speech service in the background; when they stop, only the last few seconds
 * are left to do, so the wait no longer grows with the length of the dictation. Nothing is typed early:
 * the pieces are joined and cleaned up once, at the end, exactly like a whole recording.
 * If any piece fails, finish() returns null and the whole recording is transcribed the usual way.
 *
 * [transcribe] sends one piece to the speech service (on a background thread) and returns its text.
 */
class LiveStt(private val transcribe: (FloatArray, Call) -> String) {
    companion object {
        /** A piece is at least this long. Groq bills every request as 10 s or more, so shorter pieces would cost extra. */
        const val MIN_PIECE = Recorder.SAMPLE_RATE * 10
        /** How long the speaker must have been quiet before the audio is cut there. */
        const val PAUSE = Recorder.SAMPLE_RATE / 2
        const val CLEANUP_NOTE =
            "Note: the transcript was recognized in pieces, split where the speaker paused. A full stop followed by a capital " +
                "letter may therefore fall in the middle of a sentence. Where the sentence clearly carries on, join the pieces and " +
                "fix the punctuation and capitalization."

        /** Where to cut next (a sample index in the middle of the current pause), or -1 for "not yet". */
        fun nextCut(sent: Int, count: Int, lastVoice: Int): Int {
            if (count - lastVoice < PAUSE) return -1    // still talking
            if (lastVoice - sent < MIN_PIECE) return -1 // not enough said since the last cut
            return lastVoice + PAUSE / 2
        }

        /** How many 50 ms stretches have sound in them. */
        fun voiced(a: FloatArray): Int {
            val win = Recorder.SAMPLE_RATE / 20
            var n = 0
            var at = 0
            while (at + win <= a.size) {
                var sum = 0.0
                for (i in at until at + win) sum += a[i] * a[i]
                if (sqrt(sum / win) > 0.008) n++
                at += win
            }
            return n
        }
    }

    private val exec = Executors.newCachedThreadPool()
    private val pieces = ArrayList<Future<String>>()
    private val call = Call()
    private var sent = 0 // samples already handed to a piece

    val pieceCount: Int get() = pieces.size

    /** Call a few times a second while recording (always from the same thread). */
    fun feed(count: Int, lastVoice: Int, snapshot: (Int, Int) -> FloatArray) {
        val cut = nextCut(sent, count, lastVoice)
        if (cut < 0) return
        val piece = snapshot(sent, cut)
        if (piece.isEmpty()) return
        sent += piece.size
        pieces.add(exec.submit<String> { one(piece) })
    }

    private fun one(piece: FloatArray): String {
        val v = voiced(piece)
        if (v < 3) return "" // silence makes the speech model invent words
        val text = TextTools.stripNoise(transcribe(piece, call))
        return if (v < 6 && TextTools.isPhantom(text)) "" else text
    }

    /**
     * all: the finished recording. Transcribes what's left after the last cut and returns the whole text,
     * or null when nothing was sent early or a piece failed (the caller then transcribes the recording whole).
     * Blocks until the pieces are in; call it off the main thread, after the last feed().
     */
    fun finish(all: FloatArray): String? {
        if (pieces.isEmpty()) return null
        return try {
            if (sent < all.size) {
                val tail = all.copyOfRange(sent, all.size)
                pieces.add(exec.submit<String> { one(tail) })
            }
            pieces.map { it.get() }.filter { it.isNotEmpty() }.joinToString(" ")
        } catch (_: Exception) {
            null
        } finally {
            exec.shutdown()
        }
    }

    /** The recording was thrown away, or the dictation was cancelled: stop the uploads. */
    fun cancel() {
        call.cancel()
        exec.shutdownNow()
    }
}
