package com.tokalot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Learning words from corrections. The same cases run in the desktop app's self-checks. */
class LearnTest {
    private val typed = "I talked to Kaitlin about the get hub repo on Tuesday."
    private fun learned(field: String, known: List<String> = emptyList()) = Learn.look(typed, field, known).word

    @Test fun aRespelledNameIsLearned() {
        assertEquals("Caitlyn", learned("I talked to Caitlyn about the get hub repo on Tuesday."))
        assertEquals("Caitlyn", learned("Earlier text. I talked to Caitlyn about the get hub repo on Tuesday. And more after."))
        assertEquals("Caitlyn", learned("I talked to Caitlyn's about the get hub repo on Tuesday."))
    }

    @Test fun untouchedOrRewrittenTextTeachesNothing() {
        assertNull(learned(typed))
        assertTrue(Learn.look(typed, "before $typed after", emptyList()).found)
        assertNull(learned("I spoke with Caitlyn about the repo."))            // rewritten
        assertFalse(Learn.look(typed, "", emptyList()).found)                   // sent, box now empty
        assertFalse(Learn.look("Hi Kaitlin", "Hi Caitlyn", emptyList()).found)  // too short to recognise
    }

    @Test fun ordinaryWordsAreNotLearned() {
        assertNull(learned("I talked to Kaitlin about the git hub repo on Tuesday."))   // lower-case respelling
        assertNull(learned("I spoke to Kaitlin about the get hub repo on Tuesday."))    // a different word
        assertNull(learned("I talked to Kaitlin about The get hub repo on Tuesday."))   // only a capital
        assertNull(learned("I talked to Kaitlin about the get hub repo on Thursday."))  // changed their mind
        assertNull(learned("I talked to Robert about the get hub repo on Tuesday."))    // a different person
        assertNull(learned("We talked to Kaitlin about the get hub repo on Tuesday."))  // capital only because it starts the sentence
    }

    @Test fun termsWithUnusualCapitalsOrDigitsAreLearned() {
        assertTrue(Learn.worth("github", "GitHub", false, emptyList()))
        assertTrue(Learn.worth("iphone", "iPhone", true, emptyList()))
        assertTrue(Learn.worth("kates", "K8s", false, emptyList()))
        assertTrue(Learn.worth("nasa's", "NASA", false, emptyList()))
        assertFalse(Learn.worth("stop", "STOP", false, emptyList()))
        assertFalse(Learn.worth("apple", "Apple", false, emptyList()))
    }

    @Test fun aDifferentNameIsNotARespelling() {
        assertTrue(Learn.worth("Katelyn", "Caitlyn", false, emptyList()))
        assertFalse(Learn.worth("Sam", "Tom", false, emptyList()))
        assertFalse(Learn.worth("Boston", "Austin", false, emptyList()))
        assertFalse(Learn.worth("Mike", "Mark", false, emptyList()))
    }

    @Test fun otherLanguagesOnlyLearnUnmistakableTerms() {
        assertFalse(Learn.worth("Haus", "Maus", false, emptyList(), english = false)) // every German noun has a capital
        assertTrue(Learn.worth("github", "GitHub", false, emptyList(), english = false))
    }

    @Test fun aCurlyApostropheIsNotACorrection() {
        val typed = "I'm sure that it's what Kaitlin said."
        assertNull(Learn.look(typed, "I’m sure that it’s what Kaitlin said.", emptyList()).word)
        assertTrue(Learn.look(typed, "I’m sure that it’s what Kaitlin said.", emptyList()).found)
    }

    @Test fun wordsAlreadyKnownAreSkipped() {
        assertNull(learned("I talked to Caitlyn about the get hub repo on Tuesday.", listOf("caitlyn")))
    }

    @Test fun editDistance() {
        assertEquals(0, Learn.distance("same", "same"))
        assertEquals(2, Learn.distance("kaitlin", "caitlyn"))
        assertEquals(3, Learn.distance("kitten", "sitting"))
    }

    // ---------- words spelled out while dictating ----------

    @Test fun spelledWordsAreFound() {
        assertEquals(listOf("Kowalski"), Learn.spelled("Ask Kowalski, K-O-W-A-L-S-K-I, about the report.", emptyList()))
        assertEquals(listOf("Kowalski"), Learn.spelled("ask Kovalski K O W A L S K I today", emptyList())) // misheard, then spelled
        assertEquals(listOf("Stewart"), Learn.spelled("Ask Stuart, spelled S-T-E-W-A-R-T.", emptyList()))
        assertEquals(listOf("NASA"), Learn.spelled("Like NASA, N. A. S. A.", emptyList()))
    }

    @Test fun lettersThatArentSpellingAreLeftAlone() {
        assertEquals(emptyList<String>(), Learn.spelled("Order part A-B-C and two filters.", emptyList()))
        assertEquals(emptyList<String>(), Learn.spelled("The call sign is K-D-9.", emptyList()))
        assertEquals(emptyList<String>(), Learn.spelled("Ask Kowalski, K-O-W-A-L-S-K-I.", listOf("kowalski"))) // already known
        assertEquals(emptyList<String>(), Learn.spelled("Plain words with nothing spelled.", emptyList()))
    }
}
