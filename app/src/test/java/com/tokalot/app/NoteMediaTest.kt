package com.tokalot.app

import com.tokalot.app.NoteMedia.Segment
import com.tokalot.app.NoteMedia.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Shared voice notes: word times lined up with the text, what playback skips, and the index. */
class NoteMediaTest {
    private val segments = listOf(
        Segment(0.0, 2.0, " Hey, it's me."),
        Segment(3.5, 6.0, " So, um, grab the hose for the 4020."),
    )
    private val timed = listOf(
        Word("Hey", 0.1, 0.4), Word("it's", 0.5, 0.7), Word("me", 0.8, 1.0),
        Word("So", 3.6, 3.8), Word("grab", 4.4, 4.6), Word("the", 4.6, 4.7),
        Word("hose", 4.7, 5.0), Word("for", 5.0, 5.1), Word("the", 5.1, 5.2), Word("4020", 5.3, 5.9),
    )

    @Test fun wordsKeepTheirPunctuationAndTakeWhispersTimes() {
        val w = NoteMedia.align(segments, timed)
        assertEquals(listOf("Hey,", "it's", "me.", "So,", "um,", "grab", "the", "hose", "for", "the", "4020."), w.map { it.text })
        assertEquals(0.1, w[0].start, 1e-9)
        assertEquals(4.4, w[5].start, 1e-9)
        // "um" isn't in Whisper's word list: it sits between its neighbours.
        assertTrue(w[4].start >= w[3].end && w[4].end <= w[5].start)
    }

    @Test fun withNoWordTimesEachSegmentIsSharedOut() {
        val w = NoteMedia.align(listOf(Segment(2.0, 4.0, "one two")), emptyList())
        assertEquals(listOf(Word("one", 2.0, 3.0), Word("two", 3.0, 4.0)), w)
    }

    @Test fun aLongPauseStartsANewParagraph() {
        assertEquals("Hey, it's me.\n\nSo, um, grab the hose for the 4020.", NoteMedia.text(NoteMedia.align(segments, timed)))
    }

    @Test fun findsTheWordPlaying() {
        val w = NoteMedia.align(segments, timed)
        assertEquals(-1, NoteMedia.wordAt(w, 0.05))
        assertEquals(0, NoteMedia.wordAt(w, 0.2))
        assertEquals(2, NoteMedia.wordAt(w, 2.5)) // in the pause, still on "me."
        assertEquals(10, NoteMedia.wordAt(w, 9.0))
    }

    @Test fun silenceBetweenWordsIsSkipped() {
        val w = NoteMedia.align(segments, timed)
        val cuts = NoteMedia.gapCuts(w, duration = 8.0)
        assertEquals(1.06, cuts[0].start, 1e-9) // after "me." plus the pad
        assertEquals(3.54, cuts[0].endInclusive, 1e-9)
        assertEquals(8.0, cuts.last().endInclusive, 1e-9) // the quiet tail
        assertEquals(3.54, NoteMedia.skipTo(cuts, 2.0)!!, 1e-9)
        assertNull(NoteMedia.skipTo(cuts, 0.3))
    }

    @Test fun umsAndSetOffFillersAreSkippedButRealWordsStay() {
        val w = NoteMedia.align(segments, timed)
        val fillers = NoteMedia.fillerIndices(w)
        assertEquals(setOf(3, 4), fillers) // "So," and "um,"
        val like = listOf(Word("I", 0.0, 0.1), Word("like", 0.1, 0.3), Word("that", 0.3, 0.5))
        assertTrue(NoteMedia.fillerIndices(like).isEmpty())
    }

    @Test fun overlappingCutsAreJoined() {
        val m = NoteMedia.merge(listOf(2.0..3.0, 0.0..1.0, 0.9..1.5, 5.0..5.01))
        assertEquals(listOf(0.0..1.5, 2.0..3.0), m)
    }

    @Test fun indexLinesBecomePointsInOrder() {
        val raw = """
            2|Parts|Grab the hose for the 4020.
            - 1|Hello|It's me calling.
            not an index line
            9|Fuel?
        """.trimIndent()
        val p = NoteMedia.parseIndex(raw, segments)
        assertEquals(3, p.size)
        assertEquals(NoteMedia.Point(0.0, "Hello", "It's me calling."), p[0])
        assertEquals(NoteMedia.Point(3.5, "Parts", "Grab the hose for the 4020."), p[1])
        assertEquals(NoteMedia.Point(3.5, "", "Fuel?"), p[2]) // out of range: the last segment
    }

    @Test fun summaryShapeFollowsTheLevel() {
        assertTrue("single sentence" in NoteMedia.summarySystem(1, voice = true))
        assertTrue("up to 9 lines" in NoteMedia.summarySystem(5, voice = false))
        assertEquals(1200, NoteMedia.summaryMaxTokens(7)) // unknown level: medium
    }

    @Test fun clockReadsMinutesAndSeconds() {
        assertEquals("0:05", NoteMedia.clock(5.9))
        assertEquals("4:12", NoteMedia.clock(252.0))
    }

    @Test fun voiceIsFoundOverSteadyNoise() {
        // 1 s of tractor-like hum at -40 dB, 1 s of speech at -15 dB, 2 s of hum, 1 s of speech.
        val db = FloatArray(250) { i -> if (i in 50 until 100 || i in 200 until 250) -15f else -40f }
        val speech = NoteMedia.speechFrames(db)
        assertTrue(speech[75] && speech[225])
        assertTrue(!speech[150])
        val cuts = NoteMedia.silenceCuts(speech, emptyList(), 5.0)
        assertEquals(1, cuts.filter { it.start > 1.5 && it.endInclusive < 4.5 }.size)
    }

    @Test fun silenceNeverCutsAWordWhisperHeard() {
        val speech = BooleanArray(250) { it < 50 }
        val cuts = NoteMedia.silenceCuts(speech, listOf(Word("soft", 2.0, 2.4)), 5.0)
        assertTrue(cuts.none { it.start < 2.3 && it.endInclusive > 2.1 })
    }

    @Test fun shortPausesStay() {
        val speech = BooleanArray(100) { it !in 40 until 60 } // a 0.4 s pause
        assertTrue(NoteMedia.silenceCuts(speech, emptyList(), 2.0).isEmpty())
    }
}
