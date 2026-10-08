package com.tokalot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "Note this…": a dictation that starts with a note phrase becomes a voice note. */
class NoteTriggerTest {
    private val phrases = TextTools.DEFAULT_NOTE_PHRASES.split(',')
    private fun note(text: String) = TextTools.noteTrigger(text, phrases)

    @Test fun aPhraseAtTheStartMakesANote() {
        assertEquals("Call Joe about the baler.", note("Make a note to call Joe about the baler."))
        assertEquals("The gate latch is broken.", note("Note this, the gate latch is broken."))
        assertEquals("Buy hydraulic oil.", note("Note this: buy hydraulic oil."))
        assertEquals("The north field needs lime.", note("New note. The north field needs lime."))
        assertEquals("The meeting moved to Tuesday.", note("Take a note that the meeting moved to Tuesday."))
        assertEquals("Fence posts.", note("make a note about fence posts."))
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
        assertEquals("Grease the PTO.", TextTools.noteTrigger("Jot down grease the PTO.", listOf("jot down", " ")))
        assertNull(TextTools.noteTrigger("Note this, grease the PTO.", listOf("jot down")))
    }
}
