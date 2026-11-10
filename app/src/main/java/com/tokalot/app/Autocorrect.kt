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
 * + how often the user types the word themselves
 * + how often they say it in dictations, and whether they just did (see [SpokenWords]).
 * Where a letter was touched matters, not just which key it landed on: a tap on the edge between O and I
 * makes both nearly free.
 *
 * Autocorrect replaces the word when space or punctuation follows it only if the typing isn't a word at all
 * and something close is, or (rarely) it is a rare word and the context strongly wants a near miss of it
 * ("fir sure"). Two words run together ("ofthe", or "thebdog" where the thumb hit B instead of space) are
 * split. Names, numbers, web addresses and words in capitals are left alone, and a name or abbreviation
 * ("Mich", "NAD") never wins over an ordinary word that is much likelier ("much", "and").
 */
class Autocorrect(private val words: KeyboardWords, private val history: TypingHistory? = null) {

    /** How good a correction's score must be to replace a word the list doesn't know (see [choose]). */
    var sureEnough = SURE_ENOUGH
    /** A correction this close (a slip onto a neighbouring key or two, letters swapped) is made whatever its score. */
    var closeEnough = CLOSE_ENOUGH

    /**
     * What the user says in dictations, or null where that isn't used (switched off, or a private box). It only
     * ever protects a real word from being swapped; it never talks autocorrect into swapping one (see [choose]).
     */
    var spoken: SpokenWords? = null
    /** The time, for "said in the last few hours" (the tests set it). */
    var now: () -> Long = { System.currentTimeMillis() }

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
        /** How much likelier an ordinary word must be to beat a name with exactly the typed letters. */
        private const val NAME_EDGE = 1f
        /** What a missed space costs, against a single word; more when a letter was typed in its place. */
        private const val SPLIT = 2f
        /** A correction's score (word frequency / 16 minus 3.5 per edit, plus context) needed to replace unknown typing. */
        const val SURE_ENOUGH = 6f
        const val CLOSE_ENOUGH = 1.0f
        /** ...and the score a close correction still needs: a common word, not any word at all. */
        private const val CLOSE_SCORE = 4f
        /** The most a wrong letter can cost, for a key far across the keyboard. */
        private const val FAR = 1.3f
        /** With caps lock on, words this long are fixed. */
        private const val CAPS_MIN = 4
        private val POSSESSIVE = setOf("s", "d", "ll", "ve", "re")
        /** A split ("of the") must score this well: only very common pairs, not compounds ("low key", "block chain"). */
        private const val SPLIT_SURE = 7f
        /** ...or this, when no single word is near at all... */
        private const val SPLIT_ALONE = 6.5f
        /** ...and this when the two words aren't known to follow each other. */
        private const val SPLIT_UNKNOWN = 7.75f
        private const val SPACE_ROW = "cvbnm"
        private const val VOWELS = "aeiou"
        private const val VOWEL = 0.65f

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
                typed.contains("www") || typed.contains(".com") || shouted(typed)

        /** All in capitals ("STOP", "NASA"). */
        fun shouted(typed: String) = typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } && typed.any { it.isLetter() }

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
    fun suggest(
        typed: String, prev: String?, xs: FloatArray? = null, ys: FloatArray? = null, auto: Boolean = true,
        /** The first letter's capital was put there by the keyboard (the start of a sentence), not by the user. */
        autoCap: Boolean = false,
        /** Typed with caps lock on. */
        capsLock: Boolean = false,
        /** The word before [prev], when only a space parts them. */
        prev2: String? = null,
    ): Result {
        if (typed.isEmpty()) return Result(predict(prev, 3, prev2), -1, false)
        // A capital the keyboard added says nothing about the word: "Mich" at the start of a sentence is "much"
        // mistyped, not the name, and is worked out in lower case.
        if (autoCap && typed[0].isUpperCase() && typed.drop(1).none { it.isUpperCase() }) {
            val r = suggest(typed.lowercase(), prev, xs, ys, auto, prev2 = prev2)
            fun cap(w: String) = if (w.drop(1).any { it.isUpperCase() }) w else w.replaceFirstChar { it.uppercaseChar() }
            val shown = r.shown.map { cap(it) }
            // "i" to "I" is no change once the capital is back: the word is simply right.
            if (r.pick >= 0 && shown[r.pick] == typed) return Result(shown.distinct(), -1, true)
            return Result(shown.distinct(), if (r.pick >= 0) shown.distinct().indexOf(shown[r.pick]) else -1, r.known)
        }
        // With caps lock on, a longer word is fixed like any other and stays in capitals. Short ones are left
        // alone (they're mostly abbreviations), and so is anything capitalised one letter at a time.
        if (capsLock && typed.length >= CAPS_MIN && typed.all { it.isUpperCase() || it == '\'' }) {
            val r = suggest(typed.lowercase(), prev, xs, ys, auto, prev2 = prev2)
            return Result(r.shown.map { it.uppercase() }, r.pick, r.known)
        }
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
            // Words the user often says that start this way, however rare the list has them.
            spoken?.complete(k, 4)?.forEach { said ->
                val lower = said.lowercase()
                for (id in words.formsOf(KeyboardWords.key(said))) {
                    if (words.form(id).lowercase() == lower) keep(cands, id, 0.12f * (lower.length - typed.length).coerceAtLeast(0), false)
                }
            }
        } else if (k.isNotEmpty()) {
            for (id in exact) keep(cands, id, 0f, true)
        }
        for (c in cands.values) {
            c.score = score(c.id, c.cost, prev, typed, prev2)
            c.wholeScore = c.whole?.let { score(c.id, it, prev, typed, prev2) } ?: Float.NEGATIVE_INFINITY
        }

        // The typed word's own score, to compare corrections with.
        val typedScore = typedForm?.let { score(it, 0f, prev, prev2 = prev2) }
        val pickForm = if (auto && !leave && typed.length >= 1) choose(typed, typedForm, typedScore, cands.values, prev, prev2) else null

        val pickWord = pickForm?.let { matchCase(it, typed) }
        val others = cands.values.asSequence()
            .filter { words.form(it.id) != pickForm && !words.offensive(it.id) && words.prob(it.id) > 0 }
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

    /**
     * The next words to offer after [prev] (nothing typed yet): what the user types or says after it (and after
     * [prev2] then [prev]) first, then the dictionary's likeliest. At the start of a sentence, how they usually begin one.
     */
    fun predict(prev: String?, n: Int, prev2: String? = null): List<String> {
        val sp = spoken
        if (prev == null) {
            val out = ArrayList<String>()
            sp?.starters(n)?.forEach { if (offerable(it)) out += it.replaceFirstChar { c -> c.uppercaseChar() } }
            for (w in listOf("I", "The", "Thanks")) if (out.size < n && w !in out) out += w
            return out.take(n)
        }
        // Typed pairs count double: there are far fewer of them than spoken ones, and they are about typing.
        val own = LinkedHashMap<String, Int>()
        history?.after(prev, n)?.forEach { own[it] = 2 * (history.pairCount(prev, it)) }
        sp?.after(prev2, prev, n + 2)?.forEach { (w, c) ->
            if (!offerable(w)) return@forEach
            val same = own.keys.firstOrNull { it.equals(w, ignoreCase = true) }
            own[same ?: w] = (own[same] ?: 0) + c
        }
        val out = ArrayList(own.entries.sortedByDescending { it.value }.map { it.key }.take(n))
        words.next(prev)?.forEach { if (out.size < n && out.none { o -> o.equals(it, ignoreCase = true) } && words.probOf(it) > 0) out += it }
        return out.take(n)
    }

    /** Fit for the bar: not a word the list marks as offensive. */
    private fun offerable(word: String): Boolean {
        val lower = word.lowercase()
        return words.formsOf(KeyboardWords.key(word)).none { words.offensive(it) && words.form(it).lowercase() == lower }
    }

    private fun keep(m: LinkedHashMap<Int, Cand>, id: Int, cost: Float, complete: Boolean) {
        val c = m.getOrPut(id) { Cand(id, cost, null) }
        if (cost < c.cost) c.cost = cost
        if (complete && (c.whole == null || cost < c.whole!!)) c.whole = cost
    }

    private fun score(id: Int, cost: Float, prev: String?, typed: String = "", prev2: String? = null): Float {
        val form = words.form(id)
        var s = words.prob(id) / 16f - EDIT * cost
        // A name offered for something typed in lower case ("Jack" for "hack") needs to be clearly better,
        // an abbreviation ("UE" for "ur") more so.
        if (typed.isNotEmpty() && typed[0].isLowerCase() && form[0].isUpperCase() && !form.equals(typed, ignoreCase = true)) {
            s -= if (form.length > 1 && form.all { !it.isLetter() || it.isUpperCase() }) 3.5f else 1.5f
        }
        val h = history
        if (h != null) {
            val n = h.count(form)
            if (n > 0) s += min(2.5f, 0.8f * ln(1f + n))
        }
        s += said(id)
        if (prev != null) s += context(prev, form, prev2)
        return s
    }

    /** What being said in dictations adds to a word's score. */
    private fun said(id: Int): Float = spoken?.weight(words.form(id), words.prob(id), now()) ?: 0f

    /** How much [prev] (and [prev2] before it) makes [form] more likely to come next. */
    private fun context(prev: String, form: String, prev2: String? = null): Float {
        var bonus = 0f
        val list = words.next(prev)
        if (list != null) {
            val i = list.indexOfFirst { it.equals(form, ignoreCase = true) }
            if (i >= 0) bonus = max(1.5f, 2.5f - 0.5f * i)
        }
        if (prev2 != null) {
            // The same three words in a row before, typed or said, says more than any pair.
            val t = (history?.tripleCount(prev2, prev, form) ?: 0) + (spoken?.tripleCount(prev2, prev, form) ?: 0)
            if (t > 0) bonus = max(bonus, min(4f, 1.5f * ln(1f + t) + 1.5f))
        }
        val n = history?.pairCount(prev, form) ?: 0
        if (n > 0) bonus = max(bonus, min(3.5f, 1.5f * ln(1f + n) + 1f))
        val m = spoken?.pairCount(prev, form) ?: 0
        if (m >= SpokenWords.PAIR_MIN) bonus = max(bonus, min(3f, 1.2f * ln(1f + m) + 0.6f))
        return bonus
    }

    /** What autocorrect would put in place of [typed], or null to leave it. */
    private fun choose(typed: String, typedForm: Int?, typedScore: Float?, cands: Collection<Cand>, prev: String?, prev2: String? = null): String? {
        // A word the user has kept typing is theirs.
        if ((history?.count(typed) ?: 0) >= 2) return null
        val lowerTyped = typed.lowercase()
        val sc = words.shortcut(typed)
        if (sc != null && sc.second >= 14) {
            val typedProb = typedForm?.let { words.prob(it) } ?: -1
            // "alot" -> "a lot", "andteh" -> "and the": only ever for something that isn't a word itself.
            if (sc.first.contains(' ')) { if (typedForm == null) return sc.first }
            else {
                // A real word is never swapped for another by this list ("mow" stays "mow", not "now").
                if (typedForm == null && words.idOf(sc.first) >= 0) return sc.first
            }
        }
        val whole = cands.filter { it.whole != null && !words.offensive(it.id) && words.prob(it.id) > 0 }
        if (typedForm != null) {
            // Already a word. Only the same word written properly ("i" -> "I", "toyota" -> "Toyota" when only the
            // capitalised one exists is handled below), or a rare word where context strongly wants a near miss.
            val typedProb = words.prob(typedForm)
            // An apostrophe that was typed was meant ("time's" isn't "times").
            if (typed.contains('\'')) return null
            // A real word the user says in dictations is theirs, however rare the list has it.
            if ((spoken?.count(typed) ?: 0) >= 2) return null
            val best = whole.filter { it.id != typedForm && it.whole!! <= 0.65f }.maxByOrNull { it.wholeScore } ?: return null
            // Saying the other word a lot is no reason to swap a real word for it; only what came before is.
            val lead = best.wholeScore - (typedScore ?: 0f) - max(0f, said(best.id) - said(typedForm))
            val supported = prev != null && context(prev, words.form(best.id), prev2) > context(prev, words.form(typedForm), prev2)
            return if (lead >= 4.5f && (typedProb < 90 || supported && typedProb < 140)) words.form(best.id) else null
        }
        // A hyphen between two words was meant ("re-enter", "set-up" stay as typed).
        if (typed.contains('-')) {
            val parts = typed.split('-').filter { it.isNotEmpty() }
            if (parts.isNotEmpty() && parts.all { it.length <= 2 || words.probOf(it) > 0 }) return null
        }
        // An apostrophe that was typed stays, when the word before it is one ("time's", "minute's").
        val cut = typed.lastIndexOf('\'')
        if (cut > 0 && typed.length - cut <= 3 && words.probOf(typed.substring(0, cut)) > 0 &&
            words.formsOf(KeyboardWords.key(typed)).none { words.form(it).equals(typed, ignoreCase = true) } &&
            typed.substring(cut + 1).lowercase() in POSSESSIVE) return null
        // Not a word as typed. The same letters with an apostrophe or accent (don't, I'm, café) win outright.
        val key = KeyboardWords.key(typed)
        val same = whole.filter { it.whole == 0f && KeyboardWords.key(words.form(it.id)) == key }
        val plain = same.filter { val f = words.form(it.id); f[0].isLowerCase() || f.startsWith("I'") || f == "I" || f[0] == typed[0] }
        if (plain.isNotEmpty()) {
            val lower = plain.firstOrNull { words.form(it.id).lowercase() == lowerTyped }
            return words.form((lower ?: plain.maxByOrNull { it.wholeScore }!!).id)
        }
        val others = whole.filter { it !in same }
        val bestOther = others.maxByOrNull { it.wholeScore }?.takeIf { it.whole!! <= budget(typed.length) }
        // Only a name or abbreviation has these letters ("Toyota", "Mich", "NAD"): it wins unless an ordinary
        // word close by is far likelier ("much", "and").
        val name = same.maxByOrNull { it.wholeScore }
        if (name != null && (bestOther == null || name.wholeScore + NAME_EDGE >= bestOther.wholeScore)) return words.form(name.id)
        // Two words run together.
        val split = split(typed, prev)
        // When a slip onto the next key explains the typing ("doon" is "soon", "areive" is "arrive"), only a pair
        // that is known to go together is likelier than that.
        val slip = bestOther != null && bestOther.whole!! < 0.6f
        // A pair that goes together ("thank you", "of the") needs less than two words that merely both exist,
        // which are more often a compound or a name ("squarespace").
        val splitNeed = if (split == null || !split.third) SPLIT_UNKNOWN else if (bestOther == null) SPLIT_ALONE else SPLIT_SURE
        if (split != null && split.second >= splitNeed &&
            (bestOther == null || split.second > bestOther.wholeScore) && (!slip || split.third)) return split.first
        if (typed.length < 2) return null
        // Something new (a name, an app, a technical term) is as likely as a fairly rare word: a correction has
        // to be clearly likelier than that, or what was typed stays ("kotlin" isn't "kaolin", "venmo" isn't "venom").
        val best = bestOther ?: return null
        val form = words.form(best.id)
        // A likely slip onto a common word, and never a lower-case word into a name or an abbreviation ("jk" isn't "HK").
        val near = best.whole!! <= closeEnough && best.wholeScore >= CLOSE_SCORE && !(typed[0].isLowerCase() && form[0].isUpperCase())
        return if (near || best.wholeScore >= sureEnough) form else null
    }

    /**
     * [typed] read as two words with the space missed: "ofthe" as "of the", or "thebdog" as "the dog" (B, N, V,
     * C and M sit just above the space bar, so a thumb meant for space lands on them). Both words must be
     * common; the result's score is on the same scale as a single word's.
     */
    fun split(typed: String, prev: String?): Triple<String, Float, Boolean>? {
        if (typed.length < 4 || !typed.all { it.isLetter() }) return null
        var best: Triple<String, Float, Boolean>? = null
        fun consider(a: String, b: String, penalty: Float) {
            val l = common(a) ?: return
            // "a" starts a word pair, never ends one ("canva" isn't "can a"); "I" may ("andi" is "and I").
            if (b.length == 1 && b.lowercase() != "i") return
            val r = common(b) ?: return
            val together = context(l, r)
            var s = min(words.probOf(l), words.probOf(r)) / 16f - penalty + together
            if (prev != null) s += 0.5f * context(prev, l)
            if (best == null || s > best!!.second) best = Triple("$l $r", s, together > 0f)
        }
        for (i in 1 until typed.length) {
            consider(typed.substring(0, i), typed.substring(i), SPLIT)
            if (i < typed.length - 1 && typed[i].lowercaseChar() in SPACE_ROW) consider(typed.substring(0, i), typed.substring(i + 1), SPLIT + 2.5f)
        }
        return best?.let { (w, s, known) -> Triple(matchCase(w, typed), s, known) }
    }

    /** [part] as a word common enough to split off (its usual form: "I" for "i"), or null. */
    private fun common(part: String): String? {
        if (part.length == 1 && part[0].lowercaseChar() !in "ai") return null
        val lower = part.lowercase()
        // As typed, or what it stands for without its apostrophe or capital ("dont" is "don't", "i" is "I").
        val id = words.formsOf(KeyboardWords.key(part)).filter { (words.form(it).lowercase() == lower || words.form(it).contains('\'') && part.length > 2 || words.form(it) == "I") && !words.offensive(it) }
            .maxByOrNull { words.prob(it) } ?: return null
        val need = if (part.length <= 2) 170 else 120
        return if (words.prob(id) >= need) words.form(id) else null
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
            // One vowel for another is how words get misspelled ("seperate", "wheet").
            val miss = if (t in VOWELS && c in VOWELS) VOWEL else 1f
            val target = centers[c] ?: return miss
            val (x, y) = touch(i) ?: return miss
            val dx = x - target[0]
            val dy = (y - target[1]) * ROW_ASPECT
            val d = sqrt(dx * dx + dy * dy)
            // The further the key from where the finger landed, the less likely it was the one meant: a key across
            // the keyboard costs more than a letter simply left out.
            return if (d <= 0.5f) 0.25f else min(if (miss == 1f) FAR else miss, 0.3f + (d - 0.5f) * 0.5f)
        }

        /** The typed letter [i] shouldn't be there: cheaper when it repeats the letter before (a double tap). */
        fun insert(i: Int): Float {
            if (i > 0 && k[i] == k[i - 1]) return 0.5f
            // A stray last letter is rarer than one left out ("windw" is window, not wind).
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
 * Only words the word list doesn't know: names, trade terms, slang. An ordinary word is never
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
