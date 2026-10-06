package com.tokalot.app

/**
 * Learning names and jargon from the user's own corrections (off unless switched on in Dictionary).
 * After Tokalot types a dictation, the text box is looked at again a few times. If exactly one word of
 * what was typed has been respelled, and the new spelling looks like a name or a term rather than an
 * ordinary word, that spelling goes into the dictionary. Only the comparison below sees the text, in
 * memory; nothing but the learned word is kept.
 * The desktop app has the same rules (Learn.cs).
 */
object Learn {
    /** What a look at the text box found. */
    class Seen(
        /** The dictation is still there, whole or with one word changed. False: gone, sent or rewritten. */
        val found: Boolean,
        /** The corrected spelling worth learning, if one word was changed into one. */
        val word: String?,
    )

    private val NOT_FOUND = Seen(false, null)
    private const val MIN_WORDS = 4      // fewer words than this is too little to recognise the dictation by
    private const val MAX_FIELD = 20_000 // only the end of a very long document is compared

    // Some apps turn ' into ’ as you type; that is not a correction.
    private fun tokens(s: String) = s.replace('’', '\'').split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** A word without the punctuation around it or a possessive 's. */
    fun bare(token: String): String {
        var w = token.trim { !it.isLetterOrDigit() }
        for (end in arrayOf("'s", "’s")) if (w.endsWith(end, ignoreCase = true)) w = w.dropLast(2)
        return w
    }

    /**
     * inserted: what Tokalot typed. field: what the text box holds now. known: the dictionary.
     */
    fun look(inserted: String, field: String, known: Collection<String>, english: Boolean = true): Seen {
        val typed = tokens(inserted)
        if (typed.size < MIN_WORDS) return NOT_FOUND
        val now = tokens(if (field.length > MAX_FIELD) field.takeLast(MAX_FIELD) else field)
        var oneOff: Seen? = null
        for (start in 0..now.size - typed.size) {
            var changed = -1
            var misses = 0
            for (j in typed.indices) {
                if (typed[j] != now[start + j]) {
                    misses++
                    if (misses > 1) break
                    changed = j
                }
            }
            if (misses == 0) return Seen(true, null) // untouched
            if (misses == 1 && oneOff == null) {
                val at = start + changed
                val sentenceStart = at == 0 || now[at - 1].last() in ".!?:"
                val word = bare(now[at])
                oneOff = Seen(true, word.takeIf { worth(bare(typed[changed]), it, sentenceStart, known, english) })
            }
        }
        return oneOff ?: NOT_FOUND
    }

    /**
     * Is [new] a respelling of [old] that belongs in the dictionary? Yes for names and terms
     * (Caitlyn, GitHub, K8s, NASA); no for ordinary words, different words, and plain capitalisation.
     */
    fun worth(old: String, new: String, sentenceStart: Boolean, known: Collection<String>, english: Boolean = true): Boolean {
        if (new.length !in 2..40 || old.isEmpty() || new == old) return false
        if (new.none { it.isLetter() } || new.any { !(it.isLetterOrDigit() || it in "'’-.") }) return false
        if (known.any { it.equals(new, ignoreCase = true) }) return false

        val rest = new.drop(1)
        val mixed = rest.any { it.isUpperCase() } && new.any { it.isLowerCase() } // iPhone, GitHub, McKay
        val digits = new.any { it.isDigit() }                                      // K8s, GPT4
        val acronym = new.length >= 3 && new.all { it.isUpperCase() || it.isDigit() } // NASA
        val capital = new[0].isUpperCase() && rest.none { it.isUpperCase() }       // Caitlyn

        // Only the capitals changed: that's worth keeping for GitHub or iPhone, not for "Apple" or "STOP".
        if (old.equals(new, ignoreCase = true)) return mixed
        // Unusual capitals or digits can only be a term. A plain capital mid-sentence means a name in English,
        // but not in a language such as German, where every noun has one.
        val term = mixed || digits || acronym
        val name = english && capital && !sentenceStart
        if (!term && !name) return false

        // A respelling of what was heard, not a different word put in its place: Katelyn to Caitlyn is,
        // Sam to Tom or Boston to Austin is not. Terms get more room (kates to K8s). Days and months never count.
        val a = old.lowercase()
        val b = new.lowercase()
        if (b in CALENDAR) return false
        val longest = maxOf(a.length, b.length)
        return distance(a, b) <= if (term) maxOf(2, longest * 2 / 3) else maxOf(1, longest * 3 / 7)
    }

    private val CALENDAR = setOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
    )

    /** How many single-letter edits turn [a] into [b]. */
    fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val swap = prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(swap, prev[j] + 1, cur[j - 1] + 1)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
