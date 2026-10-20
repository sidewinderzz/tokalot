package com.tokalot.app

import java.text.BreakIterator

/**
 * Moving through text the way a person sees it: by whole characters, so an emoji with a skin tone, a
 * family emoji joined with ZWJs, a flag or a letter with a combining accent is one step, never split in
 * half. Uses the platform's grapheme breaking (ICU on Android).
 */
object TextSteps {
    /** The position [steps] characters from [from] in [text] (negative: back), stopping at either end. */
    fun move(text: CharSequence, from: Int, steps: Int): Int {
        val s = text.toString()
        val bi = BreakIterator.getCharacterInstance().apply { setText(s) }
        var pos = from.coerceIn(0, s.length)
        // preceding/following land on the nearest edge, so a position inside an emoji is never kept.
        repeat(kotlin.math.abs(steps)) {
            val next = if (steps < 0) bi.preceding(pos) else bi.following(pos)
            if (next == BreakIterator.DONE) return pos
            pos = next
        }
        return pos
    }

    /**
     * How many characters (UTF-16 units) the last [n] words of [before] take, with the spaces after them:
     * what a slide left from backspace selects. A run of punctuation or an emoji counts as a word too.
     */
    fun wordsBack(before: CharSequence, n: Int): Int {
        val s = before.toString()
        val bi = BreakIterator.getCharacterInstance().apply { setText(s) }
        var i = s.length
        fun prev(at: Int) = bi.preceding(at).let { p -> if (p == BreakIterator.DONE) 0 else p }
        fun wordy(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’'
        repeat(n) {
            while (i > 0 && s[i - 1].isWhitespace()) i = prev(i)
            if (i == 0) return s.length
            if (wordy(s[i - 1])) {
                while (i > 0 && wordy(s[i - 1])) i = prev(i)
            } else {
                while (i > 0 && !s[i - 1].isWhitespace() && !wordy(s[i - 1])) i = prev(i)
            }
        }
        return s.length - i
    }
}
