package com.tokalot.app

import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow

/**
 * What the user says, for the keyboard: worked out from the dictation history each time the keyboard loads
 * (and again when a dictation is added or deleted), kept in memory only. Nothing is written or sent; delete a
 * dictation and its words leave with it.
 *
 * It holds how often each word was said, which word followed which (and which followed two words), which words
 * start sentences, how each word is written ("iPhone", "Priya") and when it was last said. [Autocorrect] and
 * [Glide] use it so that:
 * - a word the user says isn't "corrected" into another word, and a typo close to one is fixed to it;
 * - names and terms the word list doesn't have, said a few times, count as words (see [unknown]);
 * - the bar offers what the user usually says next, and finishes a word they often say;
 * - something said in the last few hours ranks higher still, so typing a follow-up to a dictation knows its words.
 *
 * Pure Kotlin, no Android, so it's unit-tested.
 */
class SpokenWords private constructor() {
    private val uni = HashMap<String, Int>()
    private val bi = HashMap<String, Int>()
    private val tri = HashMap<String, Int>()
    /** How a word is written, where that isn't all lower case. */
    private val written = HashMap<String, String>()
    private val last = HashMap<String, Long>()
    /** What follows a word, or two words ("a b"), most said first: only what was said at least [PAIR_MIN] times. */
    private val following = HashMap<String, Array<String>>()
    private var first: List<String> = emptyList()
    /** Every word by its [KeyboardWords.key], sorted, for finishing a word. */
    private var keys: Array<String> = emptyArray()
    private var byKey: Array<String> = emptyArray()

    /** Dictations read. */
    var dictations = 0
        private set
    /** Different words in them. */
    val size get() = uni.size

    companion object {
        /** A pair or triple counts once it was said this often. */
        const val PAIR_MIN = 2
        /** A word the list doesn't know counts as a word once it was said this often. */
        const val UNKNOWN_MIN = 3
        /** Words at least this common in the word list (0-255: "the", "now", "for") get nothing extra for being said. */
        private const val COMMON = 200
        /** How long ago "just said" halves, and when it stops counting. */
        private const val HALF_LIFE_MS = 2 * 60 * 60 * 1000L
        private const val RECENT_MS = 12 * 60 * 60 * 1000L
        private const val SENTENCE_END = ".!?\n"

        /** [dictations]: each one's text and when it was said. */
        fun build(dictations: List<Pair<String, Long>>): SpokenWords {
            val s = SpokenWords()
            val forms = HashMap<String, HashMap<String, Int>>()
            val opening = HashMap<String, String>()
            val starts = HashMap<String, Int>()
            for ((text, time) in dictations) {
                if (text.isBlank()) continue
                s.dictations++
                s.read(text, time, forms, opening, starts)
            }
            // How each word is written: the way it mostly is mid-sentence, where a capital means something.
            for (w in s.uni.keys) {
                val form = forms[w]?.maxByOrNull { it.value }?.key ?: opening[w]?.takeIf { f -> f.drop(1).any { it.isUpperCase() } } ?: w
                if (form != w) s.written[w] = form
            }
            val next = HashMap<String, ArrayList<Pair<String, Int>>>()
            for ((k, n) in s.bi) if (n >= PAIR_MIN) { val i = k.indexOf(' '); next.getOrPut(k.substring(0, i)) { ArrayList() } += k.substring(i + 1) to n }
            for ((k, n) in s.tri) if (n >= PAIR_MIN) { val i = k.lastIndexOf(' '); next.getOrPut(k.substring(0, i)) { ArrayList() } += k.substring(i + 1) to n }
            for ((k, list) in next) s.following[k] = list.sortedByDescending { it.second }.take(8).map { it.first }.toTypedArray()
            // Said once, a pair or triple tells nothing and there are a great many of them.
            s.bi.values.removeAll { it < PAIR_MIN }
            s.tri.values.removeAll { it < PAIR_MIN }
            s.first = starts.entries.filter { it.value >= 3 }.sortedByDescending { it.value }.take(8).map { it.key }
            val sorted = s.uni.keys.map { KeyboardWords.key(it) to it }.filter { it.first.isNotEmpty() }.sortedBy { it.first }
            s.keys = Array(sorted.size) { sorted[it].first }
            s.byKey = Array(sorted.size) { sorted[it].second }
            return s
        }

        private fun wordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-'
    }

    private fun read(text: String, time: Long, forms: HashMap<String, HashMap<String, Int>>, opening: HashMap<String, String>, starts: HashMap<String, Int>) {
        var prev: String? = null
        var prev2: String? = null
        var start = true
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (!wordChar(c)) {
                // Only a space joins two words: after a comma or a bracket the next word starts afresh, which is
                // also how the keyboard sees the word before the cursor.
                if (c != ' ') { prev = null; prev2 = null }
                if (c in SENTENCE_END) start = true
                i++
                continue
            }
            var j = i
            while (j < n && wordChar(text[j])) j++
            val token = text.substring(i, j).replace('’', '\'').trim('\'', '-')
            i = j
            if (token.isEmpty() || token.length > 30 || token.any { it.isDigit() }) { prev = null; prev2 = null; start = false; continue }
            val w = token.lowercase()
            uni[w] = (uni[w] ?: 0) + 1
            if ((last[w] ?: 0L) < time) last[w] = time
            if (start) { starts[w] = (starts[w] ?: 0) + 1; opening.putIfAbsent(w, token) }
            else { val f = forms.getOrPut(w) { HashMap(2) }; f[token] = (f[token] ?: 0) + 1 }
            if (prev != null) {
                val pair = "$prev $w"
                bi[pair] = (bi[pair] ?: 0) + 1
                if (prev2 != null) { val three = "$prev2 $pair"; tri[three] = (tri[three] ?: 0) + 1 }
            }
            prev2 = prev; prev = w
            start = false
        }
    }

    /** How often [word] was said, any capitalisation. */
    fun count(word: String) = uni[word.lowercase()] ?: 0

    /** How often [word] was said straight after [prev] (0 if only once). */
    fun pairCount(prev: String, word: String) = bi["${prev.lowercase()} ${word.lowercase()}"] ?: 0

    /** How often [word] was said straight after [prev2] then [prev] (0 if only once). */
    fun tripleCount(prev2: String, prev: String, word: String) = tri["${prev2.lowercase()} ${prev.lowercase()} ${word.lowercase()}"] ?: 0

    /** How [word] is written when the user dictates it: "iPhone", "Priya", "I'm". */
    fun form(word: String): String = word.lowercase().let { written[it] ?: it }

    /**
     * What being said adds to a word's score (see [Autocorrect]): more the more often it was said, more again if it
     * was said in the last few hours, and nothing for the words everybody says all day. [prob]: how common the
     * word list has it (0-255).
     */
    fun weight(word: String, prob: Int, now: Long): Float {
        val w = word.lowercase()
        val n = uni[w] ?: return 0f
        val own = ((COMMON - prob) / 100f).coerceIn(0f, 1f)
        if (own == 0f) return 0f
        return own * (min(2f, 0.5f * ln(1f + n)) + recent(w, now))
    }

    /** 1 for a word said just now, halving every two hours, 0 after twelve. */
    private fun recent(w: String, now: Long): Float {
        val age = now - (last[w] ?: return 0f)
        if (age >= RECENT_MS) return 0f
        return 0.5.pow(age.coerceAtLeast(0L).toDouble() / HALF_LIFE_MS).toFloat()
    }

    /** What the user says after [prev] (and after [prev2] then [prev], which comes first), as written, with how often. */
    fun after(prev2: String?, prev: String, n: Int): List<Pair<String, Int>> {
        val p = prev.lowercase()
        val out = ArrayList<Pair<String, Int>>()
        if (prev2 != null) {
            val two = "${prev2.lowercase()} $p"
            following[two]?.forEach { w -> if (out.size < n) out += form(w) to 3 * (tri["$two $w"] ?: 0) }
        }
        following[p]?.forEach { w -> if (out.size < n && out.none { it.first == form(w) }) out += form(w) to (bi["$p $w"] ?: 0) }
        return out
    }

    /** The words the user most often starts a sentence with, as written mid-sentence. */
    fun starters(n: Int): List<String> = first.take(n).map { form(it) }

    /** Up to [n] words said at least twice whose [KeyboardWords.key] starts with [prefix], most said first, as written. */
    fun complete(prefix: String, n: Int): List<String> {
        if (prefix.isEmpty()) return emptyList()
        var lo = 0
        var hi = keys.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (keys[mid] < prefix) lo = mid + 1 else hi = mid }
        val best = ArrayList<String>(n + 1)
        var i = lo
        while (i < keys.size && keys[i].startsWith(prefix)) {
            val w = byKey[i++]
            val c = uni[w] ?: 0
            if (c < 2 || keys[i - 1].length == prefix.length) continue
            var at = best.size
            while (at > 0 && (uni[best[at - 1]] ?: 0) < c) at--
            if (at < n) { best.add(at, w); if (best.size > n) best.removeAt(n) }
        }
        return best.map { form(it) }
    }

    /**
     * Names and terms said at least [UNKNOWN_MIN] times that [words] doesn't have, as written, each with how
     * common to count it (0-255): below a word from the user's own dictionary, above a rare word.
     */
    fun unknown(words: KeyboardWords): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        for ((w, n) in uni) {
            if (n < UNKNOWN_MIN) continue
            val f = form(w)
            if (!KeyboardLearn.worth(f, words)) continue
            out += f to min(150, (80 + 20 * ln(n.toFloat())).toInt())
        }
        return out
    }
}
