package com.tokalot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The keyboard following what's said in dictations: [SpokenWords] on its own, and through [Autocorrect]. */
class SpokenWordsTest {
    private val words get() = KeyboardTest.words
    private val now = 1_000_000_000_000L
    private val hour = 60 * 60 * 1000L

    /** A few weeks of dictations, all older than "just now". */
    private fun said(vararg text: String, age: Long = 30 * 24 * hour) = text.map { it to now - age }

    private val history = said(
        "I'll send the Zorblat report to Priyanka after lunch.",
        "Can you ask Priyanka if the Zorblat numbers are in?",
        "Priyanka said the zorblat export is broken again, so I'm going to mow the lawn instead.",
        "Remind me to mow before the weekend. Sounds good to me.",
        "Sounds good, see you at the standup on Thursday.",
        "Sounds good. I'm on my way to the standup now.",
        "I'm on my way to the office, see you at the standup.",
    )

    private fun engine(model: SpokenWords?, at: Long = now) = Autocorrect(words).also { it.spoken = model; it.now = { at } }

    @Test fun countsWordsPairsAndHowTheyAreWritten() {
        val s = SpokenWords.build(history)
        assertEquals(7, s.dictations)
        assertEquals(3, s.count("priyanka"))
        assertEquals(3, s.count("Zorblat"))
        assertEquals("Priyanka", s.form("priyanka"))
        // Written the way it mostly is mid-sentence, not the way it happens to start one.
        assertEquals("Zorblat", s.form("zorblat"))
        assertEquals("sounds", s.form("Sounds"))
        assertEquals("I'm", s.form("i'm"))
        assertEquals(3, s.pairCount("the", "standup"))
        assertEquals(2, s.tripleCount("on", "my", "way"))
        // A comma or a full stop ends the run of words, as it does for the keyboard.
        assertEquals(0, s.pairCount("good", "see"))
        assertEquals(0, s.pairCount("lunch", "can"))
    }

    @Test fun offersWhatIsUsuallySaidNext() {
        val s = SpokenWords.build(history)
        assertTrue("standup" in s.after(null, "the", 3).map { it.first })
        assertEquals("way", s.after("on", "my", 3).first().first)
        assertEquals(listOf("sounds"), s.starters(3))
        val a = engine(s)
        assertEquals("way", a.predict("my", 3, "on").first())
        assertEquals("Sounds", a.predict(null, 3).first())
        // Without it, the usual openers.
        assertEquals(listOf("I", "The", "Thanks"), engine(null).predict(null, 3))
    }

    @Test fun namesSaidOftenBecomeWords() {
        val s = SpokenWords.build(history)
        val list = KeyboardTest.fresh()
        assertTrue(list.probOf("Priyanka") < 0)
        val names = s.unknown(list).toMap()
        assertTrue("Priyanka" in names && "Zorblat" in names)
        // Said once or twice isn't enough, and ordinary words aren't names.
        assertFalse("standup" in names && list.probOf("standup") > 0)
        assertFalse(names.keys.any { it.equals("lawn", ignoreCase = true) })
        list.addWords(names.toList())
        val a = Autocorrect(list).also { it.spoken = s; it.now = { now } }
        // Typed right it stays, a slip is fixed to it, and it finishes on the bar.
        assertNull(a.suggest("Priyanka", null).correction)
        assertEquals("Priyanka", a.suggest("priyanka", null).correction)
        assertEquals("Priyanka", a.suggest("Priyanak", null).correction)
        assertTrue("Priyanka" in a.suggest("Priy", null).shown)
        // Without the dictations the same typing isn't touched or offered.
        assertFalse("Priyanka" in Autocorrect(words).suggest("Priy", null).shown)
    }

    @Test fun aWordThatIsSaidIsNotSwappedForACommonerOne() {
        val a = engine(SpokenWords.build(history))
        for (w in listOf("mow", "lawn", "export", "standup")) assertNull(w, a.suggest(w, "to").correction)
    }

    @Test fun sayingAWordALotNeverSwapsARealWordForIt() {
        // "form" said a hundred times: "from" typed is still "from", and so is every other real word.
        val s = SpokenWords.build(said(*Array(100) { "Fill in the form and send the form back." }))
        val a = engine(s)
        for (w in listOf("from", "for", "farm", "firm", "forms", "fork", "ford", "norm")) assertNull(w, a.suggest(w, null).correction)
        // And what everybody says all day gets nothing extra for it.
        assertEquals(0f, s.weight("the", words.probOf("the"), now), 0f)
    }

    @Test fun whatWasJustSaidRanksHigher() {
        val old = SpokenWords.build(said("We should book the venue in Lisbon for the offsite."))
        val fresh = SpokenWords.build(said("We should book the venue in Lisbon for the offsite.", age = 10 * 60 * 1000L))
        val p = words.probOf("venue")
        assertTrue(fresh.weight("venue", p, now) > old.weight("venue", p, now) + 0.5f)
        // Twelve hours on it's an ordinary said word again.
        assertEquals(old.weight("venue", p, now), fresh.weight("venue", p, now + 13 * hour), 0.001f)
        // "ven" finishes as the word from the dictation, ahead of commoner ones.
        val shown = engine(fresh).suggest("ven", "the").shown
        assertTrue(shown.toString(), "venue" in shown)
    }

    @Test fun contextPicksTheWordUsuallySaidThere() {
        val s = SpokenWords.build(history)
        // "on my wat": "way" is what follows "on my" in the dictations.
        assertEquals("way", engine(s).suggest("wat", "my", prev2 = "on").correction)
        assertEquals("standup", engine(s).suggest("standpu", "the").correction)
    }

    @Test fun completesWordsByWhatIsSaid() {
        val s = SpokenWords.build(history)
        assertEquals(listOf("standup"), s.complete("stan", 3))
        assertEquals(emptyList<String>(), s.complete("", 3))
        // Said once isn't offered; the word itself isn't its own completion.
        assertEquals(emptyList<String>(), s.complete("weeken", 3))
        assertEquals(emptyList<String>(), s.complete("standup", 3))
    }

    @Test fun numbersAndEmptyDictationsAreSkipped() {
        val s = SpokenWords.build(said("", "   ", "Call 555 0199 at 4pm about lot 22B"))
        assertEquals(1, s.dictations)
        assertEquals(0, s.count("555"))
        assertEquals(0, s.count("4pm"))
        assertEquals(1, s.count("call"))
        assertEquals(0, s.pairCount("at", "about"))
    }
}
