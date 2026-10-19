package com.tokalot.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundsTest {

    @Test fun startSoundsEndInsideTheCueWindow() {
        // The recorder ignores the first 600 ms when sounds are on, so the start tone is never taken for speech.
        for (st in Sounds.styles) assertTrue(st.id, Sounds.length(st.id, Haptics.Kind.START) < 600)
    }

    @Test fun everySoundIsQuietAndNotEmpty() {
        for (st in Sounds.styles) for (k in Haptics.Kind.values().filter { it != Haptics.Kind.TICK }) {
            val pcm = Sounds.pcm(st.id, k)
            assertTrue("${st.id} $k", pcm.isNotEmpty())
            assertTrue("${st.id} $k", pcm.maxOf { kotlin.math.abs(it.toInt()) } < Short.MAX_VALUE / 5)
        }
    }

    @Test fun unknownStyleFallsBackToSoft() {
        assertEquals("soft", Sounds.valid("marimba"))
        assertEquals("soft", Sounds.valid(null))
        assertEquals("retro", Sounds.valid("retro"))
    }
}
