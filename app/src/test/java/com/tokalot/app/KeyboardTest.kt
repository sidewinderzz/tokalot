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
}
