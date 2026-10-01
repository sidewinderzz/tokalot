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

    @Test fun bulletStylePerCategory() {
        assertTrue(prompt(AppCategory.MESSAGING).contains("starting each line with \"• \""))
        assertTrue(prompt(AppCategory.AI_CODE).contains("starting each line with \"- \""))
    }
}
