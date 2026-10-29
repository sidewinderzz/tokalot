package com.tokalot.app

/**
 * Moving through text the way a person sees it: by whole characters, so an emoji with a skin tone, a
 * family emoji joined with ZWJs, a flag, a keycap or a letter with a combining accent is one step, never
 * split in half. Done here rather than with java.text.BreakIterator, which only knows these on newer
 * Java and ICU versions, so every phone (and the tests) step the same way.
 */
object TextSteps {
    /** The position [steps] characters from [from] in [text] (negative: back), stopping at either end. */
    fun move(text: CharSequence, from: Int, steps: Int): Int {
        val b = edges(text)
        var pos = from.coerceIn(0, text.length)
        // A position inside a character first moves to that character's edge in the direction of travel.
        repeat(kotlin.math.abs(steps)) {
            pos = if (steps < 0) b.lastOrNull { it < pos } ?: return pos else b.firstOrNull { it > pos } ?: return pos
        }
        return pos
    }

    /**
     * How many characters (UTF-16 units) the last [n] words of [before] take, with the spaces after them:
     * what a slide left from backspace selects. A run of punctuation or an emoji counts as a word too.
     */
    fun wordsBack(before: CharSequence, n: Int): Int {
        val s = before.toString()
        val b = edges(s)
        var i = s.length
        fun prev(at: Int) = b.lastOrNull { it < at } ?: 0
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

    /** Where each character starts in [text], plus its end. */
    fun edges(text: CharSequence): List<Int> {
        val out = ArrayList<Int>()
        out += 0
        var i = 0
        var prev = -1
        var regional = 0 // flags are pairs of regional indicator letters
        while (i < text.length) {
            val cp = Character.codePointAt(text, i)
            if (i > 0 && !joins(prev, cp, regional)) out += i
            regional = if (cp in 0x1F1E6..0x1F1FF) (if (i > 0 && joins(prev, cp, regional)) regional + 1 else 1) else 0
            prev = cp
            i += Character.charCount(cp)
        }
        if (text.isNotEmpty()) out += text.length
        return out
    }

    /** Whether [cp] belongs to the same character as the code point before it, [prev]. */
    private fun joins(prev: Int, cp: Int, regional: Int): Boolean {
        if (prev == '\r'.code && cp == '\n'.code) return true
        if (prev == 0x200D) return true // whatever follows a zero-width joiner joins it
        if (cp == 0x200D || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF || cp in 0xE0020..0xE007F) return true
        if (cp in 0x1F1E6..0x1F1FF && prev in 0x1F1E6..0x1F1FF) return regional % 2 == 1
        return when (Character.getType(cp).toByte()) {
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK -> true
            else -> false
        }
    }
}
