package com.tokalot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tidying and checking pasted API keys. */
class KeysTest {

    @Test fun cleanDropsWhitespaceLineBreaksAndInvisibleCharacters() {
        assertEquals("gsk_abc123", Keys.clean("  gsk_abc\n123 \t"))
        assertEquals("gsk_abc123", Keys.clean("​gsk_abc‍123﻿"))
    }

    @Test fun cleanDropsQuotesAroundAKeyCopiedFromCode() {
        assertEquals("sk-abc", Keys.clean("\"sk-abc\""))
        assertEquals("sk-abc", Keys.clean("“sk-abc”"))
        assertEquals("sk-abc", Keys.clean("'sk-abc'"))
    }

    @Test fun rightKeysPass() {
        assertNull(Keys.problem("groq", "gsk_" + "a".repeat(40)))
        assertNull(Keys.problem("anthropic", "sk-ant-api03-" + "a".repeat(40)))
        assertNull(Keys.problem("openai", "sk-proj-" + "a".repeat(40)))
        assertNull(Keys.problem("gemini", "AIza" + "a".repeat(35)))
        assertNull(Keys.problem("groq", ""))
    }

    @Test fun keyForAnotherProviderIsNamed() {
        assertEquals("That looks like a key for Groq. This box is for OpenAI.", Keys.problem("openai", "gsk_" + "a".repeat(40)))
        // sk-ant- also starts with OpenAI's sk-, but it's an Anthropic key.
        assertEquals(
            "That looks like a key for Anthropic (Claude). This box is for OpenAI.",
            Keys.problem("openai", "sk-ant-" + "a".repeat(40))
        )
    }

    @Test fun unknownStartAndShortKeysWarn() {
        assertTrue(Keys.problem("groq", "abc" + "a".repeat(40))!!.startsWith("Groq keys usually start with gsk_"))
        assertTrue(Keys.problem("groq", "gsk_abc")!!.startsWith("That looks too short"))
    }
}
