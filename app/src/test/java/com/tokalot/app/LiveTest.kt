package com.tokalot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest
import kotlin.math.sin

/** The compressed upload and transcribing-while-talking. The same cases run in the desktop app's self-checks. */
class LiveTest {
    private val r = Recorder.SAMPLE_RATE

    // ---------- FLAC: must match the desktop encoder byte for byte (which is checked against a real decoder) ----------

    private fun signal(n: Int): FloatArray {
        val a = FloatArray(n)
        var x = 12345
        var prev = 0
        for (i in 0 until n) {
            x = x * 1664525 + 1013904223
            val step = ((x ushr 16) and 0x3FF) - 512
            prev = (prev + step).coerceIn(-30000, 30000)
            val v = when {
                i in 9001..11999 -> 0
                i in 15001..15199 -> if (x and 1 == 0) 32767 else -32768
                else -> prev
            }
            a[i] = v / 32768f
        }
        return a
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun flacMatchesTheDesktopEncoder() {
        assertEquals("14c8ac4b60638aa634db92bf2e0e685a64196dfc97ed767b41e79605a1bd9605", sha(Flac.encode(signal(1))))
        assertEquals("dfd8a5a015e8845161b93fdd74b1d427080ea7c1e3bb487cedb67f76d09e5360", sha(Flac.encode(signal(5))))
        assertEquals("b0cd8f61d7e2d39cc8acadb9d975310133f2805170e8572db26192f18e69596c", sha(Flac.encode(signal(4096))))
        assertEquals("5b3f1c3d0dcc08de3ce266f5b58cd54fa9c9017244663ab79e41972f93503843", sha(Flac.encode(signal(4097))))
        assertEquals("f533ae6b777698b0ead7ac79467d202d8283d3f098690ba83afb23a082568a8c", sha(Flac.encode(signal(20000))))
        assertEquals("dcbad31c9f2f8d4282131d65a543dbfcea1b72f14ef832c58b9036a36e95264e", sha(Flac.encode(signal(100000))))
    }

    @Test fun flacIsSmallerThanWav() {
        val tone = FloatArray(r * 5) { (sin(it * 0.05) * 0.2).toFloat() }
        assertTrue(Flac.encode(tone).size < tone.size) // under half the 2 bytes a sample WAV takes
    }

    // ---------- where the recording is cut ----------

    @Test fun cutsOnlyInAPauseAfterTenSeconds() {
        assertEquals(-1, LiveStt.nextCut(0, r * 20, r * 20 - 100))  // still talking
        assertEquals(-1, LiveStt.nextCut(0, r * 9, r * 8))          // too little said
        assertEquals(r * 12 + r / 4, LiveStt.nextCut(0, r * 13, r * 12))
        assertEquals(-1, LiveStt.nextCut(r * 12, r * 20, r * 19))   // counts from the last cut
        assertEquals(r * 23 + r / 4, LiveStt.nextCut(r * 12, r * 24, r * 23))
    }

    // ---------- a 40 s "recording": an 11 s phrase then a 1 s pause, repeated, silent for the last 6 s ----------

    private fun speaking(i: Int) = i < r * 34 && (i / r) % 12 < 11
    private val audio = FloatArray(r * 40) { if (speaking(it)) (sin(it * 0.2) * 0.2).toFloat() else 0f }
    private fun take(from: Int, to: Int) = audio.copyOfRange(from, minOf(to, audio.size))
    private fun lastVoice(count: Int): Int { var i = count - 1; while (i > 0 && !speaking(i)) i--; return i + 1 }

    private fun record(live: LiveStt) {
        var count = r / 4
        while (count <= audio.size) { live.feed(count, lastVoice(count), ::take); count += r / 4 }
    }

    @Test fun piecesAreSentWhileTalkingAndJoinedInOrder() {
        val live = LiveStt { piece, _ -> Thread.sleep(20); "Piece of %.2f seconds.".format(java.util.Locale.US, piece.size / r.toDouble()) }
        record(live)
        assertEquals(3, live.pieceCount)
        // The silent tail is not sent at all: silence makes the speech model invent words.
        assertEquals("Piece of 11.25 seconds. Piece of 12.00 seconds. Piece of 11.00 seconds.", live.finish(audio))
    }

    @Test fun aFailedPieceFallsBackToTheWholeRecording() {
        val live = LiveStt { piece, _ -> if (piece.size > r * 11.5) throw IOException("down") else "ok" }
        record(live)
        assertNull(live.finish(audio))
    }

    @Test fun aShortRecordingIsLeftAlone() {
        val live = LiveStt { _, _ -> "x" }
        live.feed(r * 5, r * 4, ::take)
        assertNull(live.finish(audio.copyOf(r * 5)))
    }

    @Test fun aPhantomPhraseFromANearSilentPieceIsDropped() {
        // 0.2 s of sound in 12 s: the model's "Thank you." for that is not something the user said.
        val quiet = FloatArray(r * 12) { if (it < r / 5) 0.2f * sin(it * 0.2).toFloat() else 0f }
        val live = LiveStt { piece, _ -> if (LiveStt.voiced(piece) < 6) "Thank you." else "Hello." }
        live.feed(r * 12, r * 11, ::take)                         // first piece: real speech
        assertEquals(1, live.pieceCount)
        assertEquals("Hello.", live.finish(take(0, r * 11 + r / 4) + quiet))
    }
}
