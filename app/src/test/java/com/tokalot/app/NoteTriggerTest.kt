package com.tokalot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "Note this…": a dictation that starts with a note phrase becomes a voice note. */
class NoteTriggerTest {
    private val phrases = TextTools.DEFAULT_NOTE_PHRASES.split(',')
    private fun note(text: String) = TextTools.noteTrigger(text, phrases)

    @Test fun aPhraseAtTheStartMakesANote() {
        assertEquals("Call Joe about the invoice.", note("Make a note to call Joe about the invoice."))
        assertEquals("The printer is jammed again.", note("Note this, the printer is jammed again."))
        assertEquals("Buy printer ink.", note("Note this: buy printer ink."))
        assertEquals("The report is due Friday.", note("New note. The report is due Friday."))
        assertEquals("The meeting moved to Tuesday.", note("Take a note that the meeting moved to Tuesday."))
        assertEquals("Parking.", note("make a note about parking."))
    }

    @Test fun theSameWordsLaterOnAreJustWords() {
        assertNull(note("I need to make a note of that for later."))
        assertNull(note("Can you note this down for me?"))
        assertNull(note("Notes from today's meeting are attached."))
        assertNull(note("Thanks, see you then."))
    }

    @Test fun onlyThePhraseLeavesNothing() {
        assertEquals("", note("Make a note."))
    }

    @Test fun ownPhrasesWork() {
        assertEquals("Renew the domain.", TextTools.noteTrigger("Jot down renew the domain.", listOf("jot down", " ")))
        assertNull(TextTools.noteTrigger("Note this, renew the domain.", listOf("jot down")))
    }
}
