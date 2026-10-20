package com.tokalot.app

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Suggestions and autocorrect for the keyboard, all on the phone.
 *
 * Each candidate word gets a score, in rough log-odds:
 *   how common the word is (the dictionary's 0-255 scale / 16, so 32 steps ≈ 7× as common)
 * − 3.5 × how far the typing is from the word (see [EditCosts]: a slip onto the next key costs about
 *   half an edit, a letter typed twice or two letters swapped a little more, anything else a whole one)
 * + what came before (the dictionary's likeliest next words, and pairs the user has typed before)
 * + how often the user types the word themselves.
 * Where a letter was touched matters, not just which key it landed on: a tap on the edge between O and I
 * makes both nearly free.
 *
 * Autocorrect replaces the word when space or punctuation follows it only if the typing isn't a word at all
 * and something close is, or (rarely) it is a rare word and the context strongly wants a near miss of it
 * ("fir sure"). Names, numbers, web addresses and words in capitals are left alone.
 */
class Autocorrect(private val words: KeyboardWords, private val history: TypingHistory? = null) {

    /** Where each letter's key sits, in key widths (rows are [ROW_ASPECT] key widths apart). */
    var centers: Map<Char, FloatArray> = Qwerty.centers

    class Result(
        /** Up to three words for the suggestion bar, left to right. */
        val shown: List<String>,
        /** Which of [shown] space would put in, or -1 to keep what was typed. */
        val pick: Int,
        /** What was typed is a word already (any capitalisation). */
        val known: Boolean,
    ) {
        val correction: String? get() = if (pick >= 0) shown[pick] else null
    }

    /** [cost]: as typed so far (a completion counts its missing letters lightly); [whole]: as a finished word, null if it isn't one within reach. */
    private class Cand(val id: Int, var cost: Float, var whole: Float?, var score: Float = 0f, var wholeScore: Float = 0f)

    companion object {
        const val ROW_ASPECT = 1.35f
        private const val EDIT = 3.5f
        private const val INS = 1.0f
        private const val DEL = 0.9f
        private const val TRANSPOSE = 0.6f

        /** Most edits a word of this many letters can be corrected by. */
        fun budget(n: Int) = when {
            n <= 1 -> 0f
            n == 2 -> 0.6f
            n == 3 -> 1.0f
            n == 4 -> 1.45f
            n <= 6 -> 1.9f
            else -> 2.4f
        }

        /** Typing that autocorrect leaves alone whatever it looks like. */
        fun leaveAlone(typed: String): Boolean =
            typed.any { it.isDigit() || it == '@' || it == '/' || it == '_' || it == '#' } ||
                typed.contains("www") || typed.contains(".com") ||
                (typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } && typed.any { it.isLetter() })

        /** [form] written the way [typed] was capitalised: "Hello" for "Helo", "HELLO" for "HELO". */
        fun matchCase(form: String, typed: String): String {
            if (typed.isEmpty() || form.isEmpty()) return form
            val letters = typed.filter { it.isLetter() }
            if (letters.length > 1 && letters.all { it.isUpperCase() }) return form.uppercase()
            if (typed[0].isUpperCase() && form[0].isLowerCase()) return form[0].uppercaseChar() + form.substring(1)
            return form
        }
    }

    // ------------------------------------------------------------------ suggestions while typing

    /**
     * Suggestions for the word being typed. [xs]/[ys]: where each letter was touched, in key widths
     * (NaN where unknown, e.g. a word picked back up from the text). [prev]: the word before it, if any.
     */
    fun suggest(typed: String, prev: String?, xs: FloatArray? = null, ys: FloatArray? = null, auto: Boolean = true): Result {
        if (typed.isEmpty()) return Result(predict(prev, 3), -1, false)
        val k = KeyboardWords.key(typed)
        val lowerTyped = typed.lowercase()
        val exact = words.formsOf(k)
        // A form the list has but never offers ("america", "monday" in lower case) doesn't count when a proper one exists.
        val typedForm = exact.firstOrNull { (words.form(it) == typed || words.form(it) == lowerTyped) && (words.prob(it) > 0 || exact.none { o -> words.prob(o) > 0 }) }
        val known = typedForm != null || history?.count(typed)?.let { it >= 2 } == true
        val leave = leaveAlone(typed)

        val cands = LinkedHashMap<Int, Cand>()
        if (!leave && k.isNotEmpty()) {
            fuzzy(k, xs, ys) { id, cost -> keep(cands, id, cost, true) }
            for (id in words.complete(k, 6)) keep(cands, id, 0.12f * (words.form(id).length - typed.length).coerceAtLeast(0), false)
        } else if (k.isNotEmpty()) {
            for (id in exact) keep(cands, id, 0f, true)
        }
        for (c in cands.values) {
            c.score = score(c.id, c.cost, prev, typed)
            c.wholeScore = c.whole?.let { score(c.id, it, prev, typed) } ?: Float.NEGATIVE_INFINITY
        }

        // The typed word's own score, to compare corrections with.
        val typedScore = typedForm?.let { score(it, 0f, prev) }
        var pickId = -1
        if (auto && !leave && typed.length >= 1) pickId = choose(typed, typedForm, typedScore, cands.values, prev)

        val pickWord = if (pickId >= 0) matchCase(words.form(pickId), typed) else null
        val others = cands.values.asSequence()
            .filter { it.id != pickId && !words.offensive(it.id) && words.prob(it.id) > 0 }
            .sortedByDescending { it.score }
            .map { matchCase(words.form(it.id), typed) }
            .filter { it != typed && it != pickWord }
            .distinct().take(3).toList()
        val shown: List<String>
        val pick: Int
        when {
            pickWord != null && pickWord != typed -> { shown = listOfNotNull(typed, pickWord, others.firstOrNull()); pick = 1 }
            known -> { shown = listOfNotNull(others.getOrNull(0), typed, others.getOrNull(1)); pick = -1 }
            else -> { shown = listOf(typed) + others.take(2); pick = -1 }
        }
        return Result(shown, if (pick >= 0 && pick < shown.size) pick else -1, known)
    }

    /** The next words to offer after [prev] (nothing typed yet). */
    fun predict(prev: String?, n: Int): List<String> {
        if (prev == null) return listOf("I", "The", "Thanks").take(n)
        val out = ArrayList<String>()
        history?.after(prev, n)?.forEach { if (it !in out) out += it }
        words.next(prev)?.forEach { if (out.size < n && it !in out && words.probOf(it) > 0) out += it }
        return out.take(n)
    }

    private fun keep(m: LinkedHashMap<Int, Cand>, id: Int, cost: Float, complete: Boolean) {
        val c = m.getOrPut(id) { Cand(id, cost, null) }
        if (cost < c.cost) c.cost = cost
        if (complete && (c.whole == null || cost < c.whole!!)) c.whole = cost
    }

    private fun score(id: Int, cost: Float, prev: String?, typed: String = ""): Float {
        val form = words.form(id)
        var s = words.prob(id) / 16f - EDIT * cost
        // A name offered for something typed in lower case ("Baker" for "baler") needs to be clearly better.
        if (typed.isNotEmpty() && typed[0].isLowerCase() && form[0].isUpperCase() && !form.equals(typed, ignoreCase = true)) s -= 1.5f
        val h = history
        if (h != null) {
            val n = h.count(form)
            if (n > 0) s += min(2.5f, 0.8f * ln(1f + n))
        }
        if (prev != null) s += context(prev, form)
        return s
    }

    /** How much [prev] makes [form] more likely to come next. */
    private fun context(prev: String, form: String): Float {
        var bonus = 0f
        val list = words.next(prev)
        if (list != null) {
            val i = list.indexOfFirst { it.equals(form, ignoreCase = true) }
            if (i >= 0) bonus = 2.5f - 0.5f * i
        }
        val n = history?.pairCount(prev, form) ?: 0
        if (n > 0) bonus = max(bonus, min(3.5f, 1.5f * ln(1f + n) + 1f))
        return bonus
    }

    /** What autocorrect would put in place of [typed] (an id), or -1 to leave it. */
    private fun choose(typed: String, typedForm: Int?, typedScore: Float?, cands: Collection<Cand>, prev: String?): Int {
        // A word the user has kept typing is theirs.
        if ((history?.count(typed) ?: 0) >= 2) return -1
        val lowerTyped = typed.lowercase()
        val sc = words.shortcut(typed)
        if (sc != null && sc.second >= 14) {
            val target = words.idOf(sc.first)
            val typedProb = typedForm?.let { words.prob(it) } ?: -1
            if (target >= 0 && (typedForm == null || typedProb < 100 && words.prob(target) > typedProb + 60)) return target
        }
        val whole = cands.filter { it.whole != null && !words.offensive(it.id) && words.prob(it.id) > 0 }
        if (typedForm != null) {
            // Already a word. Only the same word written properly ("i" -> "I", "kubota" -> "Kubota" when only the
            // capitalised one exists is handled below), or a rare word where context strongly wants a near miss.
            val typedProb = words.prob(typedForm)
            val best = whole.filter { it.id != typedForm && it.whole!! <= 0.65f }.maxByOrNull { it.wholeScore } ?: return -1
            val lead = best.wholeScore - (typedScore ?: 0f)
            val supported = prev != null && context(prev, words.form(best.id)) > context(prev, words.form(typedForm))
            return if (lead >= 4.5f && (typedProb < 90 || supported && typedProb < 140)) best.id else -1
        }
        // Not a word as typed. The same letters in another form (don't, I'm, Kubota, café) win outright.
        val same = whole.filter { it.whole == 0f && KeyboardWords.key(words.form(it.id)) == KeyboardWords.key(typed) }
        if (same.isNotEmpty()) {
            val lower = same.firstOrNull { words.form(it.id).lowercase() == lowerTyped }
            return (lower ?: same.maxByOrNull { it.wholeScore }!!).id
        }
        if (typed.length < 2) return -1
        val best = whole.maxByOrNull { it.wholeScore } ?: return -1
        return if (best.whole!! <= budget(typed.length)) best.id else -1
    }

    // ------------------------------------------------------------------ the fuzzy match

    /**
     * Calls [found] with every word within [budget] edits of [k] (a key), and how far it is. A weighted
     * Damerau-Levenshtein distance computed down the trie one row per letter, so a whole branch is dropped as
     * soon as every way through it costs too much.
     */
    fun fuzzy(k: String, xs: FloatArray?, ys: FloatArray?, found: (id: Int, cost: Float) -> Unit) {
        val n = k.length
        val limit = budget(n)
        val costs = EditCosts(k, xs, ys, centers)
        val maxDepth = n + 4
        val rows = Array(maxDepth + 2) { FloatArray(n + 1) }
        val path = CharArray(maxDepth + 2)
        for (j in 0..n) rows[0][j] = if (j == 0) 0f else rows[0][j - 1] + costs.insert(j - 1)

        fun visit(node: Int, depth: Int) {
            if (depth >= maxDepth) return
            words.walk(node) { child, c ->
                val prev = rows[depth]
                val row = rows[depth + 1]
                path[depth] = c
                val before = if (depth > 0) path[depth - 1] else '\u0000'
                row[0] = prev[0] + costs.delete(c, before)
                var best = row[0]
                for (j in 1..n) {
                    var v = prev[j - 1] + costs.substitute(j - 1, c)
                    v = min(v, prev[j] + costs.delete(c, before))
                    v = min(v, row[j - 1] + costs.insert(j - 1))
                    if (j > 1 && depth > 0 && c == k[j - 2] && before == k[j - 1] && c != before) {
                        v = min(v, rows[depth - 1][j - 2] + TRANSPOSE)
                    }
                    row[j] = v
                    if (v < best) best = v
                }
                if (best <= limit) {
                    if (row[n] <= limit) words.eachWordAt(child) { id -> found(id, row[n]) }
                    visit(child, depth + 1)
                }
            }
        }
        visit(0, 0)
    }

    /** The price of each kind of slip, for one typed word. */
    class EditCosts(private val k: String, private val xs: FloatArray?, private val ys: FloatArray?, private val centers: Map<Char, FloatArray>) {
        /** The typed letter [i] stood for [c]: free if it's the same letter, cheap if [c]'s key is close to where the finger landed. */
        fun substitute(i: Int, c: Char): Float {
            val t = k[i]
            if (t == c) return 0f
            val target = centers[c] ?: return 1f
            val (x, y) = touch(i) ?: return 1f
            val dx = x - target[0]
            val dy = (y - target[1]) * ROW_ASPECT
            val d = sqrt(dx * dx + dy * dy)
            return if (d <= 0.5f) 0.25f else min(1f, 0.3f + (d - 0.5f) * 0.5f)
        }

        /** The typed letter [i] shouldn't be there: cheaper when it repeats the letter before (a double tap). */
        fun insert(i: Int): Float {
            if (i > 0 && k[i] == k[i - 1]) return 0.5f
            // A stray last letter is rarer than one left out ("tractr" is tractor, not tract).
            return if (i == k.length - 1) INS + 0.1f else INS
        }

        /** The word has [c] (after [before]) but it wasn't typed: cheap for a doubled letter or a hyphen. */
        fun delete(c: Char, before: Char): Float = when {
            c == '-' || c == '.' -> 0.3f
            c == before -> 0.35f
            else -> DEL
        }

        private fun touch(i: Int): Pair<Float, Float>? {
            val x = xs?.getOrNull(i)
            val y = ys?.getOrNull(i)
            if (x != null && y != null && !x.isNaN() && !y.isNaN()) return x to y
            val c = centers[k[i]] ?: return null
            return c[0] to c[1]
        }
    }
}

/** The standard QWERTY letter keys, in key widths: each row is offset as on a phone keyboard. */
object Qwerty {
    val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val offsets = floatArrayOf(0.5f, 1.0f, 2.0f)
    val centers: Map<Char, FloatArray> = HashMap<Char, FloatArray>().also { m ->
        rows.forEachIndexed { r, row -> row.forEachIndexed { i, c -> m[c] = floatArrayOf(offsets[r] + i, r.toFloat()) } }
    }
}

/**
 * Deciding which typed words go into Tokalot's dictionary (so they sync, and stop being "corrected").
 * Only words the word list doesn't know: names, farm and trade terms, slang. An ordinary word is never
 * added, so the dictionary stays a list of the user's own vocabulary.
 */
object KeyboardLearn {
    /** Typed and kept this many times, a word not in the list is learned. */
    const val TIMES = 2

    fun worth(word: String, words: KeyboardWords): Boolean {
        if (word.length < 2 || word.length > 30) return false
        if (!word.any { it.isLetter() } || Autocorrect.leaveAlone(word)) return false
        if (word.any { !(it.isLetter() || it == '\'' || it == '-') }) return false
        return words.formsOf(KeyboardWords.key(word)).none { words.form(it).equals(word, ignoreCase = true) }
    }
}
