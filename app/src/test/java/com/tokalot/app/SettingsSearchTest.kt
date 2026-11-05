package com.tokalot.app

import com.tokalot.app.SettingsSearch.Row
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSearchTest {
    private val rows = listOf(
        Row("Theme", "Match phone", "Appearance", "Appearance"),
        Row("Prefer on-device in weak signal", "Use the phone's model when the signal drops", "Speech & AI", "Speech & AI › On-device model"),
        Row("Skip silence", "Jump over pauses with no talking", "Notes", "Notes"),
        Row("Skip ums", "Jump over um, uh and the like", "Notes", "Notes"),
        Row("Auto speed", "Speed up where the talking is slow", "Notes", "Notes"),
        Row("Get beta updates", "Try new features first", "About", "About"),
    )

    @Test fun everydayWords() {
        assertEquals("Theme", SettingsSearch.find("turn on dark mode", rows).first().title)
        assertEquals("Prefer on-device in weak signal", SettingsSearch.find("wifi", rows).first().title)
        assertEquals("Skip silence", SettingsSearch.find("pauses", rows).first().title)
        assertEquals("Skip ums", SettingsSearch.find("um", rows).first().title)
    }

    @Test fun titleFirstAndNotes() {
        val hits = SettingsSearch.find("skip", rows)
        assertEquals(listOf("Skip silence", "Skip ums"), hits.map { it.title })
        assertTrue(hits.first().note!!.contains("⋮"))
        assertEquals("Get beta updates", SettingsSearch.find("beta", rows).single().title)
        assertTrue(SettingsSearch.find("zzz", rows).isEmpty())
        assertTrue(SettingsSearch.find("  ", rows).isEmpty())
    }
}
