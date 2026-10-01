package com.tokalot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextToolsTest {
    private val snips = listOf(
        Snippet("my email", "me@example.com"),
        Snippet("home address", "123 Main St, Springfield"),
    )

    @Test fun phantomPhrases() {
        assertTrue(TextTools.isPhantom(" Thank you. "))
        assertTrue(!TextTools.isPhantom("Thank you for the update."))
    }

    @Test fun stripsNoise() {
        assertEquals("hello there", TextTools.stripNoise(" [BLANK_AUDIO] hello (music) there "))
    }

    @Test fun basicCleanRemovesFillers() {
        assertEquals("So we could meet tuesday.", TextTools.basicClean("um so uh we could meet tuesday ."))
    }

    @Test fun snippetWithWhisperPunctuation() {
        val (p, map) = TextTools.protect("Send it to My, email. Thanks", snips)
        assertEquals("Send it to {{SNIP1}}. Thanks", p)
        assertEquals("Send it to me@example.com. Thanks", TextTools.restore(p, map))
    }

    @Test fun snippetNotInsideOtherWords() {
        val (p, map) = TextTools.protect("check my emails later", snips)
        assertTrue(map.isEmpty())
        assertEquals("check my emails later", p)
    }

    @Test fun droppedPlaceholderIsAppended() {
        val (_, map) = TextTools.protect("the home address is", snips)
        assertEquals("The address is 123 Main St, Springfield", TextTools.restore("The address is", map))
    }
}
