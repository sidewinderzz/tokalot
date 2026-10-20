package com.tokalot.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupTest {
    private fun prompt(cat: AppCategory) =
        Cleanup.systemPrompt(Style.CASUAL, cat, null, emptyList(), "", hasSnippets = false)

    @Test fun formattingRulesPresent() {
        val p = prompt(AppCategory.MESSAGING)
        assertTrue(p.contains("- Formatting ("))
        assertTrue(p.contains("Parentheses:"))
        assertTrue(p.contains("numbered list (1. 2. 3.)"))
        assertFalse(p.contains("BULLET"))
    }

    private fun prompt(cat: AppCategory, polish: Boolean) =
        Cleanup.systemPrompt(Style.CASUAL, cat, "Gmail", emptyList(), "", hasSnippets = false, polish = polish)

    @Test fun keepsOwnWordsByDefault() {
        // Default argument and explicit "off" are the same prompt.
        for (p in listOf(prompt(AppCategory.AI_CODE), prompt(AppCategory.AI_CODE, polish = false))) {
            assertTrue(p.contains("never swap a word for a better, more precise or more technical one"))
            assertTrue(p.contains("do the live flash drive boot thing or whatever?\""))
            assertFalse(p.contains("tighten the wording"))
        }
        // The extra rule comes right after the category rule...
        val lines = prompt(AppCategory.AI_CODE, polish = false).lines()
        val i = lines.indexOf("- ${AppCategory.AI_CODE.rule}")
        assertTrue(i >= 0)
        assertTrue(lines[i + 1].startsWith("- Use only the terms the user actually said."))
        // ...and is there when the category has no rule of its own.
        for (c in AppCategory.values()) {
            assertTrue(prompt(c, polish = false).contains("- Use only the terms the user actually said."))
        }
    }

    @Test fun polishAllowsRewording() {
        for (c in AppCategory.values()) {
            val p = prompt(c, polish = true)
            assertTrue(p.contains("You may also tighten the wording and make vague phrasing clear and precise"))
            assertFalse(p.contains("never swap a word"))
            assertFalse(p.contains("Use only the terms"))
        }
    }

    @Test fun onlyTheWordingRulesDiffer() {
        val off = prompt(AppCategory.EMAIL, polish = false).lines()
        val on = prompt(AppCategory.EMAIL, polish = true).lines()
        assertTrue((off - on.toSet()).size == 2) // the reworded rule and the "Use only the terms" rule
        assertTrue((on - off.toSet()).size == 1)
    }

    @Test fun ownWordingKeepsTheRawTranscript() {
        val e = History.ownWording(Entry(1, 1, "Create a live USB.", "um the live flash drive thing", 1500, true, "x"), "The live flash drive thing")
        assertTrue(e.text == "The live flash drive thing" && e.raw == "um the live flash drive thing")
        assertTrue(e.own && !e.cleaned)
        assertTrue(History.parse(History.toJson(listOf(e))) == listOf(e))
    }

    @Test fun bulletStylePerCategory() {
        assertTrue(prompt(AppCategory.MESSAGING).contains("starting each line with \"• \""))
        assertTrue(prompt(AppCategory.AI_CODE).contains("starting each line with \"- \""))
    }
}
