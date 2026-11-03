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

    // ---------- reminders and checklists (beta) ----------

    private val now = java.time.ZonedDateTime.of(2026, 11, 3, 7, 40, 0, 0, java.time.ZoneId.of("America/Chicago"))

    @Test fun remindMeWithATimeIsAReminder() {
        assertEquals(Plans.Kind.REMINDER to "At 7:30 to lock the shop", TextTools.planTrigger("Remind me at 7:30 to lock the shop.", now)
            ?.let { it.first to it.second.trimEnd('.') })
        assertEquals(Plans.Kind.REMINDER, TextTools.planTrigger("Set a reminder for tomorrow to call the vet", now)?.first)
    }

    /** A text to a friend that starts "Remind me…" stays a text: with no time in it, it isn't a reminder. */
    @Test fun remindMeWithoutATimeStaysText() {
        assertNull(TextTools.planTrigger("Remind me what time the game starts?", now))
        assertNull(TextTools.planTrigger("Can you remind me at 6 to call?", now)) // not at the start
        // Said into the Voice note tile, it's a reminder waiting for a time.
        assertEquals(Plans.Kind.REMINDER, TextTools.planTrigger("Remind me to call Mike", now, anyReminder = true)?.first)
    }

    @Test fun makeAListIsAChecklist() {
        val (kind, rest) = TextTools.planTrigger("Make a list for the trip: chargers, cooler, dog food.", now)!!
        assertEquals(Plans.Kind.LIST, kind)
        assertEquals("For the trip: chargers, cooler, dog food.", rest)
        assertNull(TextTools.planTrigger("I'll make a list later", now))
    }
}
