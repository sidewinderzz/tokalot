package com.tokalot.app

import com.tokalot.app.ButtonRules.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonRulesTest {
    private fun d(keyboard: Boolean, box: Box = Box.NONE, editing: Boolean? = null, ownApp: Boolean = false, dismissed: Boolean = false, looksLeft: Int = ButtonRules.LOOKS) =
        ButtonRules.decide(keyboard, box, editing, ownApp, dismissed, looksLeft)

    @Test fun showsOnKeyboardWithBox() {
        val r = d(keyboard = true, box = Box.FIELD)
        assertTrue(r.show); assertFalse(r.again)
    }

    @Test fun showsOnKeyboardWhenOnlyTheEditorInfoKnowsTheBox() {
        assertTrue(d(keyboard = true, box = Box.NONE, editing = true).show)
    }

    @Test fun neverOnPasswords() {
        assertFalse(d(keyboard = true, box = Box.PASSWORD).show)
        assertFalse(d(keyboard = true, box = Box.FIELD, editing = false).show)
        assertFalse(d(keyboard = true, box = Box.PASSWORD).again)
    }

    @Test fun neverInTokalotItself() {
        val r = d(keyboard = true, box = Box.FIELD, ownApp = true)
        assertFalse(r.show); assertFalse(r.again)
    }

    @Test fun keyboardWithNoBoxLooksAgainThenTrustsTheKeyboard() {
        val first = d(keyboard = true, looksLeft = ButtonRules.LOOKS)
        assertFalse(first.show); assertTrue(first.again); assertEquals(250L, first.delayMs)
        val later = d(keyboard = true, looksLeft = ButtonRules.LOOKS / 2)
        assertTrue(later.show); assertFalse(later.again)
        assertTrue(d(keyboard = true, looksLeft = 0).show)
    }

    @Test fun boxWithoutKeyboardKeepsLookingUntilTheLooksRunOut() {
        val r = d(keyboard = false, box = Box.FIELD, looksLeft = 3)
        assertFalse(r.show); assertTrue(r.again); assertEquals(500L, r.delayMs)
        assertFalse(d(keyboard = false, box = Box.FIELD, looksLeft = 0).again)
    }

    @Test fun nothingFoundLooksOnlyAFewTimes() {
        assertTrue(d(keyboard = false, looksLeft = ButtonRules.LOOKS).again)
        assertFalse(d(keyboard = false, looksLeft = ButtonRules.LOOKS / 2).again)
    }

    @Test fun draggedAwayStaysHiddenWhileTheKeyboardIsUp() {
        assertFalse(d(keyboard = true, box = Box.FIELD, dismissed = true).show)
    }

    @Test fun passwordInputTypes() {
        assertTrue(ButtonRules.isPasswordType(0x81))  // text password
        assertTrue(ButtonRules.isPasswordType(0x91))  // visible password
        assertTrue(ButtonRules.isPasswordType(0xe1))  // web password
        assertTrue(ButtonRules.isPasswordType(0x12))  // number password
        assertFalse(ButtonRules.isPasswordType(0x1))  // plain text
        assertFalse(ButtonRules.isPasswordType(0x20001)) // multi-line text
        assertFalse(ButtonRules.isPasswordType(0xa1)) // web edit text
        assertFalse(ButtonRules.isPasswordType(0x2))  // number
    }
}
