package com.tokalot.app

import org.junit.Assert.assertEquals
import org.junit.Test

class UseModeTest {
    @Test fun neverPickedKeepsWhatIsSwitchedOn() {
        assertEquals(UseMode.FLOATING, UseMode.guess(accessibilityOn = true, keyboardOn = false))
        assertEquals(UseMode.FLOATING, UseMode.guess(accessibilityOn = true, keyboardOn = true))
        assertEquals(UseMode.KEYBOARD, UseMode.guess(accessibilityOn = false, keyboardOn = true))
        // Nothing on yet: the old setup, so people who updated see what they saw before.
        assertEquals(UseMode.FLOATING, UseMode.guess(accessibilityOn = false, keyboardOn = false))
    }

    @Test fun onlyTheFloatingButtonMentionsTheAccessibilitySwitchAsNeeded() {
        assertEquals(true, UseMode.FLOATING.blurb.contains("Needs the accessibility switch"))
        assertEquals(false, UseMode.KEYBOARD.blurb.contains("Needs"))
        assertEquals(false, UseMode.APP.blurb.contains("Needs"))
    }
}
