package com.tokalot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fitting a dictation into the sentence around the cursor (Style › Fit into the sentence). */
class FitTest {

    private fun fit(before: String, text: String, after: String, keep: List<String> = emptyList(), lowercase: Boolean = true) =
        TextTools.fit(text, TextTools.spot(before, after), keep, lowercase)

    @Test fun whereTheCursorIs() {
        val start = TextTools.spot("", "")
        assertFalse(start.mid); assertFalse(start.continues); assertTrue(start.plain)
        assertFalse(TextTools.spot("Done. ", "").mid)
        assertFalse(TextTools.spot("Really? ", "").mid)
        assertFalse(TextTools.spot("Line one\n", "").mid)
        assertFalse(TextTools.spot("He said \"", "").mid) // after an opening quote: leave it be
        assertTrue(TextTools.spot("I fixed the ", "").mid)
        assertTrue(TextTools.spot("Thanks, ", "").mid)
        assertTrue(TextTools.spot("the list: ", "").mid)
        assertTrue(TextTools.spot("", " and the gate").continues)
        assertTrue(TextTools.spot("", ", then").continues)
        assertTrue(TextTools.spot("", ".").continues)
        assertFalse(TextTools.spot("", " The next one.").continues) // a new sentence follows
        assertFalse(TextTools.spot("", "\nand more").continues)    // a new line follows
    }

    @Test fun midSentenceLosesTheCapitalAndThePeriod() {
        assertEquals("the blue one", fit("I want ", "The blue one.", " and the red one."))
        assertEquals("also the signup page.", fit("Fixed the login bug, ", "Also the signup page.", "")) // end of the box: the period stays
        assertEquals("the blue one.", fit("I want ", "The blue one.", "")) // end of the box: keep the period
        assertEquals("The blue one", fit("Done. ", "The blue one.", " and more"))
    }

    @Test fun aNewSentenceIsLeftAlone() {
        assertEquals("Call me later.", fit("Done. ", "Call me later.", ""))
        assertEquals("Call me later.", fit("Done. ", "Call me later.", " Thanks."))
        assertEquals("Call me later.", fit("", "Call me later.", ""))
    }

    @Test fun namesAndAcronymsKeepTheirCapital() {
        assertEquals("I think so", fit("and ", "I think so.", " too"))
        assertEquals("I'll check", fit("and ", "I'll check.", " later"))
        assertEquals("NASA said so", fit("and ", "NASA said so.", " too"))
        assertEquals("McDonald's", fit("at ", "McDonald's.", " today"))
        assertEquals("iPhone", fit("my ", "iPhone.", " is"))
        assertEquals("Kowalski report", fit("the ", "Kowalski report.", " is late", keep = listOf("kowalski")))
        assertEquals("John said so", fit("and ", "John said so.", " too", lowercase = false)) // the AI already chose
    }

    @Test fun punctuationIsOnlyTrimmedWhenItWouldDouble() {
        assertEquals("the blue one", fit("I want ", "The blue one.", "."))
        assertEquals("is it ready?", fit("So ", "Is it ready?", " he asked"))
        assertEquals("wait...", fit("and ", "Wait...", " then"))
        assertEquals("42 files", fit("about ", "42 files.", " left"))
    }

    @Test fun cleanupIsToldOnlyWhereTheTextGoes() {
        val plain = Cleanup.systemPrompt(Style.CASUAL, AppCategory.OTHER, null, emptyList(), "", hasSnippets = false)
        assertFalse(plain.contains("middle of a sentence"))
        assertFalse(plain.contains("carries on after"))
        val mid = Cleanup.systemPrompt(
            Style.CASUAL, AppCategory.OTHER, null, emptyList(), "", hasSnippets = false,
            spot = TextTools.spot("I fixed the ", " and the gate"),
        )
        assertTrue(mid.contains("middle of a sentence"))
        assertTrue(mid.contains("do not end it with a period"))
        assertFalse(mid.contains("I fixed the")) // the text around the cursor is never sent
        assertFalse(mid.contains("and the gate"))
    }
}
