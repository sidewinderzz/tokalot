package com.tokalot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.random.Random

/** The keyboard's word list, autocorrect, swipe typing and layout, on the real word list. */
class KeyboardTest {
    companion object {
        /** Loaded once: it's the same file the keyboard reads (the tests run in the app folder). */
        val words: KeyboardWords by lazy {
            GZIPInputStream(File("src/main/assets/keyboard/en_US.txt.gz").inputStream()).bufferedReader().use { KeyboardWords.load(it) }
        }
    }

    private fun fixed(typed: String, prev: String? = null, history: TypingHistory? = null) =
        Autocorrect(words, history).suggest(typed, prev).correction

    @Test fun wordListLoads() {
        assertTrue(words.size > 150_000)
        assertTrue(words.probOf("the") > 200)
        assertTrue(words.probOf("tractor") > 0)
        // Farm, shop and texting words were added to the list.
        assertTrue(words.probOf("swather") > 0)
        assertTrue(words.probOf("lol") > 100)
        assertTrue(words.next("thank")!!.contains("you"))
    }

    @Test fun commonTyposAreFixed() {
        assertEquals("the", fixed("teh"))
        assertEquals("hello", fixed("helo"))
        assertEquals("don't", fixed("dont"))
        assertEquals("I'm", fixed("im"))
        assertEquals("I", fixed("i"))
        assertEquals("receive", fixed("recieve"))
        assertEquals("tractor", fixed("tractr"))
        assertEquals("definitely", fixed("definately"))
        assertEquals("Wednesday", fixed("wenesday"))
        assertEquals("Kubota", fixed("kubota"))
    }

    @Test fun realWordsAndSlangAreLeftAlone() {
        for (w in listOf("its", "were", "nut", "lol", "ok", "thx", "swather", "hay", "baler")) assertNull(w, fixed(w))
        // Numbers, addresses and shouting aren't touched.
        assertNull(fixed("4wd"))
        assertNull(fixed("www.deere.com"))
        assertNull(fixed("STOP"))
    }

    @Test fun contextHelps() {
        assertEquals("going", fixed("goin", prev = "I'm"))
    }

    @Test fun capitalsAreKept() {
        assertEquals("The", fixed("Teh"))
    }

    @Test fun aWordUsedTwiceIsNoLongerCorrected() {
        val h = TypingHistory()
        h.used("shelbs", null); h.used("shelbs", null)
        assertNull(fixed("shelbs", history = h))
        assertTrue(KeyboardLearn.worth("shelbs", words))
        assertFalse(KeyboardLearn.worth("tractor", words))
        assertFalse(KeyboardLearn.worth("4wd", words))
    }

    @Test fun suggestionBar() {
        val ac = Autocorrect(words)
        val r = ac.suggest("teh", null)
        // What was typed, the correction (picked, so bold), and one more.
        assertEquals("teh", r.shown[0])
        assertEquals("the", r.shown[r.pick])
        val known = ac.suggest("tract", null)
        assertTrue(known.known)
        assertEquals(-1, known.pick)
        assertTrue(ac.predict("thank", 3).contains("you"))
    }

    @Test fun userWordsBecomeKnown() {
        val w = GZIPInputStream(File("src/main/assets/keyboard/en_US.txt.gz").inputStream()).bufferedReader().use { KeyboardWords.load(it) }
        w.addUserWords(listOf("Shelbyz"))
        assertNull(Autocorrect(w).suggest("Shelbyz", null).correction)
        assertEquals("Shelbyz", Autocorrect(w).suggest("shelbyz", null).correction)
    }

    @Test fun swipeFindsTheWord() {
        val g = Glide(words)
        val kw = 100f; val kh = 135f
        val centers = Qwerty.centers.mapValues { floatArrayOf(it.value[0] * kw, it.value[1] * kh) }
        g.setLayout(centers, kw, kh)
        val rnd = Random(4)
        val list = listOf("hello", "the", "tractor", "going", "because", "farm", "thanks", "tomorrow", "weather", "field",
            "would", "could", "think", "truck", "coffee", "morning", "sounds", "great", "parts", "running")
        var right = 0
        for (word in list) {
            val pts = ArrayList<Pair<Float, Float>>()
            val keys = word.map { c -> centers.getValue(c).let { floatArrayOf(it[0] + rnd.nextFloat() * 50 - 25, it[1] + rnd.nextFloat() * 50 - 25) } }
            for (i in 0 until keys.size - 1) {
                val a = keys[i]; val b = keys[i + 1]
                val steps = maxOf(2, (kotlin.math.hypot(b[0] - a[0], b[1] - a[1]) / 15).toInt())
                for (s in 0 until steps) {
                    val t = s.toFloat() / steps
                    pts += (a[0] + (b[0] - a[0]) * t) to (a[1] + (b[1] - a[1]) * t)
                }
            }
            pts += keys.last()[0] to keys.last()[1]
            g.start(pts[0].first, pts[0].second)
            pts.drop(1).forEach { g.add(it.first, it.second) }
            if (g.words(4, null).firstOrNull()?.lowercase() == word) right++
        }
        assertTrue("$right of ${list.size}", right >= list.size - 2)
    }

    @Test fun layoutMatchesTheTouchModel() {
        val page = Layouts.letters(numberRow = false, symbolHints = true)
        page.layout(1080f, 150f, 10f)
        val c = page.letterCenters()
        // Key widths and rows, the same frame as Qwerty.centers: q is half a key in, a is one key in, a row down.
        assertEquals(0.5f, c.getValue('q')[0], 0.01f)
        assertEquals(0f, c.getValue('q')[1], 0.01f)
        assertEquals(1.0f, c.getValue('a')[0], 0.01f)
        assertEquals(1f, c.getValue('a')[1], 0.01f)
        assertEquals(2.0f, c.getValue('z')[0], 0.01f)
        // Every touch lands on a key: the gaps and the indented row's ends belong to their neighbours.
        assertEquals('a'.code, page.keyAt(1f, 150f + 75f)?.code)
        assertEquals('l'.code, page.keyAt(1079f, 150f + 75f)?.code)
        assertEquals(KeyCode.SPACE, page.keyAt(540f, 3 * 150f + 75f)?.code)
        assertNull(page.keyAt(540f, 4 * 150f + 1f))
        // The number row puts the letters a row lower, but their centres stay in the same frame.
        val withNumbers = Layouts.letters(numberRow = true, symbolHints = true)
        withNumbers.layout(1080f, 150f, 10f)
        assertEquals(0f, withNumbers.letterCenters().getValue('q')[1], 0.01f)
        // The number row is shorter than a letter row, and touches still land on the right row.
        assertEquals(150f * (4 + Layouts.NUMBER_ROW), withNumbers.height, 0.01f)
        assertEquals('1'.code, withNumbers.keyAt(5f, 150f * Layouts.NUMBER_ROW - 1f)?.code)
        assertEquals('q'.code, withNumbers.keyAt(5f, 150f * Layouts.NUMBER_ROW + 1f)?.code)
        assertEquals(150f * Layouts.NUMBER_ROW, withNumbers.lettersTop, 0.01f)
    }

    @Test fun toolbar() {
        val t = KeyboardToolbar
        assertEquals(t.DEFAULT, t.parse(null))
        // Unknown names dropped, duplicates once, and the mic is always there.
        assertEquals(listOf("clipboard", "fix", "mic"), t.parse("clipboard,bogus,fix,clipboard"))
        assertEquals(listOf("mic", "fix", "clipboard"), t.move(t.DEFAULT, "mic", -5))
        assertEquals(t.DEFAULT, t.remove(t.DEFAULT, "mic"))
        assertEquals(listOf("fix", "mic"), t.remove(t.DEFAULT, "clipboard"))
        assertEquals(t.DEFAULT + "emoji", t.add(t.DEFAULT, "emoji"))
        assertTrue(t.valid("mic") && !t.valid("fix") && !t.valid("mic,mic"))
        // Clipboard sits in the middle by default, with the mic on the right beside suggestions.
        assertEquals(1, t.DEFAULT.indexOf("clipboard"))
        assertFalse(t.micOnLeft(t.DEFAULT))
        assertTrue(t.micOnLeft(listOf("mic", "fix", "clipboard")))
    }

    @Test fun typingHistoryRoundTrips() {
        val h = TypingHistory()
        h.used("hay", null); h.used("bales", "hay"); h.used("bales", "hay"); h.used("bales", "hay")
        val back = TypingHistory.parse(h.serialize())
        assertEquals(3, back.count("bales"))
        assertEquals(3, back.pairCount("hay", "bales"))
        assertEquals(listOf("bales"), back.after("hay", 3))
        back.forget("bales")
        assertEquals(0, back.count("bales"))
    }

    @Test fun emoji() {
        val e = File("src/main/assets/keyboard/emoji.txt").bufferedReader().use { EmojiData.load(it) }
        assertTrue(e.groups.size >= 8)
        assertTrue(e.groups.sumOf { it.emoji.size } > 1500)
        assertEquals("👍🏽", EmojiData.withTone("👍", 3))
        assertEquals("🧑🏽‍🌾", EmojiData.withTone("🧑‍🌾", 3))
        assertEquals("👍", EmojiData.withTone("👍", 0))
        assertTrue(e.search("tractor").any { it.text == "🚜" })
        assertTrue(e.search("thumbs up").any { it.text == "👍" })
        assertTrue(e.groups.flatMap { it.emoji }.any { it.text == "#️⃣" })
    }

    @Test fun cursorStepsOverWholeCharacters() {
        val family = "👨‍👩‍👧"
        val text = "a${family}b🇺🇸c👍🏽de\u0301f"
        // Right from the start: a, the family, b, the flag, c, the thumb with its skin tone, d, é (e + accent), f.
        val stops = ArrayList<Int>()
        var pos = 0
        while (true) { val next = TextSteps.move(text, pos, 1); if (next == pos) break; stops += next; pos = next }
        assertEquals(listOf(1, 1 + family.length, 2 + family.length, 6 + family.length, 7 + family.length,
            11 + family.length, 12 + family.length, 14 + family.length, 15 + family.length), stops)
        // Back again lands on the same places, and several steps at once is the same as one at a time.
        assertEquals(1 + family.length, TextSteps.move(text, 6 + family.length, -2))
        assertEquals(0, TextSteps.move(text, 3, -5))
        assertEquals(text.length, TextSteps.move(text, text.length, 3))
        // A position inside an emoji moves to its edge, never further inside.
        assertEquals(1 + family.length, TextSteps.move(text, 2, 1))
    }

    @Test fun deleteSlideWords() {
        assertEquals("tractor ".length, TextSteps.wordsBack("the tractor ", 1))
        assertEquals("the tractor ".length, TextSteps.wordsBack("the tractor ", 2))
        assertEquals("tractor".length, TextSteps.wordsBack("the tractor", 1))
        // Punctuation and emoji go as a word of their own, and an emoji is never cut in half.
        assertEquals("!!".length, TextSteps.wordsBack("done!!", 1))
        assertEquals("👍🏽 ".length, TextSteps.wordsBack("done 👍🏽 ", 1))
        assertEquals("don't".length, TextSteps.wordsBack("I don't", 1))
        assertEquals("I don't".length, TextSteps.wordsBack("I don't", 5))
        assertEquals(0, TextSteps.wordsBack("hay", 0))
    }

    @Test fun clipboardList() {
        val h = ClipHistory()
        val hour = ClipHistory.KEEP_MS
        h.add("first", 0)
        h.add("second", 1000)
        h.add("first", 2000) // copied again: back to the top, not twice
        assertEquals(listOf("first", "second"), h.items.map { it.text })
        h.pin("second", true)
        h.prune(hour + 2001)
        // Unpinned items go after an hour; pinned ones stay.
        assertEquals(listOf("second"), h.items.map { it.text })
        repeat(ClipHistory.MAX + 5) { h.add("item $it", hour + 3000L + it) }
        assertEquals(ClipHistory.MAX + 1, h.items.size)
        assertTrue(h.items.any { it.text == "second" && it.pinned })
        assertFalse(h.add("   ", 0))
        // Fresh only for a short while after copying.
        val now = hour + 3000L + ClipHistory.MAX + 4
        assertEquals("item ${ClipHistory.MAX + 4}", h.fresh(now)?.text)
        assertNull(h.fresh(now + ClipHistory.FRESH_MS + 1))
        val back = ClipHistory.parse(h.serialize())
        assertEquals(h.items.map { it.text to it.pinned }, back.items.map { it.text to it.pinned })
        back.clear()
        assertEquals(listOf("second"), back.items.map { it.text })
    }

    @Test fun clipboardChips() {
        assertEquals(listOf("https://deere.com/parts"), ClipHistory.smart("https://deere.com/parts"))
        assertEquals(listOf("https://deere.com/parts", "Parts are at https://deere.com/parts."),
            ClipHistory.smart("Parts are at https://deere.com/parts."))
        assertEquals(listOf("shelby@example.com", "(555) 123-4567", "Call (555) 123-4567 or mail shelby@example.com"),
            ClipHistory.smart("Call (555) 123-4567 or mail shelby@example.com"))
        // Plain numbers and dates aren't phone numbers.
        assertEquals(listOf("Bale count 240 on 10/20"), ClipHistory.smart("Bale count 240 on 10/20"))
    }

    @Test fun codesFromTexts() {
        assertEquals("123456", CodeFinder.find("Your Google verification code is 123456"))
        assertEquals("482913", CodeFinder.find("G-482913 is your Google verification code."))
        assertEquals("7731", CodeFinder.find("Use 7731 to log in to Deere Operations Center"))
        assertEquals("123456", CodeFinder.find("Your one-time passcode: 123 456. Don't share it."))
        assertEquals("90210", CodeFinder.find("Chase: 90210 is your security code. Expires in 10 min"))
        // Messages that don't talk about a code, and numbers that aren't one, are left alone.
        assertNull(CodeFinder.find("Meet at 1234 Main St at 5"))
        assertNull(CodeFinder.find("Your order total is $1234.56, code-free shipping"))
        assertNull(CodeFinder.find("Your code expires in 2026"))
        assertNull(CodeFinder.find(null))
    }
}
