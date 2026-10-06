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

    /**
     * Where speech was last heard in the finished recording (a sample index), when the recorder knows.
     * If that is before the last cut, nothing was said after it and the tail isn't sent at all.
     */
    var lastVoice = -1

    /** Call a few times a second while recording (always from the same thread). */
    fun feed(count: Int, lastVoice: Int, snapshot: (Int, Int) -> FloatArray) {
        // A piece already failed: the whole recording will be transcribed at the end, so more pieces are wasted uploads.
        if (failed) return
        val cut = nextCut(sent, count, lastVoice)
        if (cut < 0) return
        val piece = snapshot(sent, cut)
        if (piece.isEmpty()) return
        sent += piece.size
        pieces.add(exec.submit<String> { one(piece) })
    }

    @Volatile private var failed = false

    /**
     * How long the latest piece took, in milliseconds per second of its audio (0 until one is back).
     * A measurement of this connection during this very recording, so the progress ring trusts it over anything older.
     */
    @Volatile var lastRate = 0f
        private set

    /**
     * tail: what was recorded after the last cut. That can be nothing but room noise and the tap on the
     * button, which the speech model turns into "Thank you.", so it needs half a second of sound to be sent
     * and a stock phrase from it is dropped. Earlier pieces were cut because speech was heard in them.
     */
    private fun one(piece: FloatArray, tail: Boolean = false): String {
        if (tail && voiced(piece) < 10) return ""
        val began = System.nanoTime()
        val text = try {
            TextTools.stripNoise(
                try {
                    transcribe(piece, call)
                } catch (e: Exception) {
                    // One more try for a passing error (a dropped connection, a busy server). A timeout has cost enough already.
                    if (call.cancelled || e is java.net.SocketTimeoutException) throw e
                    transcribe(piece, call)
                }
            )
        } catch (e: Exception) {
            failed = true
            throw e
        }
        if (!tail) lastRate = (System.nanoTime() - began) / 1_000_000f / (piece.size / Recorder.SAMPLE_RATE.toFloat())
        return if (tail && TextTools.isPhantom(text)) "" else text
    }

    /**
     * all: the finished recording. Transcribes what's left after the last cut and returns the whole text,
     * or null when nothing was sent early or a piece failed (the caller then transcribes the recording whole).
     * Blocks until the pieces are in; call it off the main thread, after the last feed().
     */
    fun finish(all: FloatArray): String? {
        if (pieces.isEmpty()) return null
        // A piece has already failed: don't make the user wait for the tail before starting over.
        if (failed) { cancel(); exec.shutdown(); return null }
        return try {
            if (sent < all.size && (lastVoice < 0 || lastVoice > sent)) {
                val tail = all.copyOfRange(sent, all.size)
                pieces.add(exec.submit<String> { one(tail, tail = true) })
            }
            pieces.map { it.get() }.filter { it.isNotEmpty() }.joinToString(" ")
        } catch (_: Exception) {
            cancel() // stop the pieces still on their way, so they don't compete with the whole recording
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
