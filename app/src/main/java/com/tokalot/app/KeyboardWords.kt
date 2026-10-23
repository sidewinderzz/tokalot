package com.tokalot.app

import java.io.BufferedReader
import java.text.Normalizer

/**
 * The keyboard's word list: every word with how common it is, the likeliest next words after each, and
 * a few fixed replacements ("dont" -> "don't"). Loaded from the AOSP English dictionary (see
 * tools/keyboard/make_wordlist.py), plus the user's own words.
 *
 * Words are found by a "key": lower case, with accents and apostrophes left out, so "dont", "Don't" and
 * "don't" share one key and the forms under a key decide which one is right. The keys sit in a trie kept
 * in flat arrays (about 400,000 nodes for the 160,000 words), which the fuzzy search in [Autocorrect]
 * walks one letter at a time.
 *
 * Pure Kotlin, no Android, so the unit tests load the real list.
 */
class KeyboardWords private constructor() {

    // ---- the words themselves
    private val forms = ArrayList<String>()
    private var probs = IntArray(0)
    private var offensive = BooleanArray(0)
    /** The next form with the same key, or -1. */
    private var sameKey = IntArray(0)

    // ---- the trie: node 0 is the root; children are a linked list (first child, next sibling)
    private var nodeChar = CharArray(1 shl 16)
    private var firstChild = IntArray(1 shl 16) { -1 }
    private var nextSibling = IntArray(1 shl 16) { -1 }
    /** The first form whose key ends at this node, or -1. */
    private var nodeWord = IntArray(1 shl 16) { -1 }
    /** The highest probability of any form at or below this node, for best-first completion. */
    private var subBest = IntArray(1 shl 16)
    private var nodes = 1

    private val nextWords = HashMap<String, Array<String>>()
    private val shortcuts = HashMap<String, Pair<String, Int>>()

    val size get() = forms.size

    companion object {
        /** A word added by the user (Tokalot's dictionary, or learned while typing) counts as this common. */
        const val USER_PROB = 170

        fun load(reader: BufferedReader): KeyboardWords {
            val w = KeyboardWords()
            var section = 0
            val pending = ArrayList<Triple<String, Int, Boolean>>(170_000)
            reader.forEachLine { line ->
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                when {
                    line == "=bigrams" -> section = 1
                    line == "=shortcuts" -> section = 2
                    section == 0 -> {
                        val t = line.split('\t')
                        if (t.size >= 2) pending += Triple(t[0], t[1].toIntOrNull() ?: 0, t.size > 2 && t[2] == "x")
                    }
                    section == 1 -> {
                        val t = line.split('\t')
                        if (t.size >= 2) w.nextWords[t[0]] = t.subList(1, t.size).toTypedArray()
                    }
                    else -> {
                        val t = line.split('\t')
                        if (t.size >= 3) w.shortcuts[t[0]] = t[1] to (t[2].toIntOrNull() ?: 0)
                    }
                }
            }
            w.probs = IntArray(pending.size + 64)
            w.offensive = BooleanArray(pending.size + 64)
            w.sameKey = IntArray(pending.size + 64) { -1 }
            for ((form, p, x) in pending) w.add(form, p, x)
            // The dictionary lists next words in no useful order: commonest first.
            for ((k, list) in w.nextWords) w.nextWords[k] = list.sortedByDescending { w.probOf(it) }.toTypedArray()
            return w
        }

        private val MARKS = Regex("\\p{Mn}+")

        /** The key a word is stored and looked up under. */
        fun key(word: String): String {
            val lower = word.lowercase()
            val plain = if (lower.all { it.code < 128 }) lower
                else MARKS.replace(Normalizer.normalize(lower, Normalizer.Form.NFD), "")
            return if (plain.indexOf('\'') < 0 && plain.indexOf('’') < 0) plain
                else plain.replace("'", "").replace("’", "")
        }
    }

    private fun add(form: String, prob: Int, x: Boolean): Int {
        val k = key(form)
        if (k.isEmpty()) return -1
        val node = insert(k, prob)
        // The same form again (a user word already in the list): keep it once, at the higher probability.
        var i = nodeWord[node]
        while (i >= 0) {
            if (forms[i] == form) {
                if (prob > probs[i]) probs[i] = prob
                return i
            }
            i = sameKey[i]
        }
        val id = forms.size
        if (id >= probs.size) {
            probs = probs.copyOf(id * 2); offensive = offensive.copyOf(id * 2)
            sameKey = sameKey.copyOf(id * 2).also { it.fill(-1, id, it.size) }
        }
        forms += form
        probs[id] = prob
        offensive[id] = x
        sameKey[id] = nodeWord[node]
        nodeWord[node] = id
        return id
    }

    private fun insert(k: String, prob: Int): Int {
        var node = 0
        if (prob > subBest[0]) subBest[0] = prob
        for (c in k) {
            var child = firstChild[node]
            var prev = -1
            while (child >= 0 && nodeChar[child] != c) { prev = child; child = nextSibling[child] }
            if (child < 0) {
                child = newNode(c)
                if (prev < 0) firstChild[node] = child else nextSibling[prev] = child
            }
            if (prob > subBest[child]) subBest[child] = prob
            node = child
        }
        return node
    }

    private fun newNode(c: Char): Int {
        if (nodes == nodeChar.size) {
            val n = nodes * 2
            nodeChar = nodeChar.copyOf(n)
            firstChild = firstChild.copyOf(n).also { it.fill(-1, nodes, n) }
            nextSibling = nextSibling.copyOf(n).also { it.fill(-1, nodes, n) }
            nodeWord = nodeWord.copyOf(n).also { it.fill(-1, nodes, n) }
            subBest = subBest.copyOf(n)
        }
        nodeChar[nodes] = c
        return nodes++
    }

    /** Adds the user's own words (Tokalot's dictionary, words learned while typing), as common words. */
    fun addUserWords(words: Collection<String>) {
        for (w in words) {
            val t = w.trim()
            if (t.isEmpty() || t.contains(' ') || t.length > 40) continue
            add(t, USER_PROB, false)
        }
    }

    // ---------- lookups

    fun form(id: Int) = forms[id]
    fun prob(id: Int) = probs[id]
    fun offensive(id: Int) = offensive[id]

    /** Every form stored under [k] (a [key]). */
    fun formsOf(k: String): List<Int> {
        val node = find(k)
        if (node < 0) return emptyList()
        val out = ArrayList<Int>(2)
        var i = nodeWord[node]
        while (i >= 0) { out += i; i = sameKey[i] }
        return out
    }

    /** The id of exactly this form, or -1. */
    fun idOf(form: String): Int = formsOf(key(form)).firstOrNull { forms[it] == form } ?: -1

    /** How common [word] is (0-255), any capitalisation; -1 when it isn't a word. */
    fun probOf(word: String): Int {
        val lower = word.lowercase()
        return formsOf(key(word)).filter { forms[it].lowercase() == lower }.maxOfOrNull { probs[it] } ?: -1
    }

    /** The dictionary's likeliest next words after [word], best first. */
    fun next(word: String): Array<String>? = nextWords[word] ?: nextWords[word.lowercase()]

    /** A fixed replacement for [typed], with its strength (0-15), or null. */
    fun shortcut(typed: String): Pair<String, Int>? = shortcuts[typed.lowercase()]

    private fun find(k: String): Int {
        var node = 0
        for (c in k) {
            var child = firstChild[node]
            while (child >= 0 && nodeChar[child] != c) child = nextSibling[child]
            if (child < 0) return -1
            node = child
        }
        return node
    }

    /**
     * Up to [n] of the commonest words whose key starts with [prefix] (a [key]), best first, the word
     * itself included. Best-first: a branch is only opened while it can still hold something better.
     */
    fun complete(prefix: String, n: Int): List<Int> {
        val start = find(prefix)
        if (start < 0) return emptyList()
        val best = ArrayList<Int>(n + 1)
        // A small priority queue of nodes by the best probability below them.
        val queue = java.util.PriorityQueue<Int>(64, compareByDescending { subBest[it] })
        queue += start
        while (queue.isNotEmpty()) {
            val node = queue.poll()
            if (best.size >= n && subBest[node] <= probs[best.last()]) break
            var i = nodeWord[node]
            while (i >= 0) {
                if (!offensive[i] && probs[i] > 0) {
                    var at = best.size
                    while (at > 0 && probs[best[at - 1]] < probs[i]) at--
                    if (at < n) { best.add(at, i); if (best.size > n) best.removeAt(n) }
                }
                i = sameKey[i]
            }
            var child = firstChild[node]
            while (child >= 0) {
                if (best.size < n || subBest[child] > probs[best.last()]) queue += child
                child = nextSibling[child]
            }
        }
        return best
    }

    /** The trie walk behind fuzzy matching: [visit] is called on each child of [node] with its character. */
    fun walk(node: Int, visit: (child: Int, c: Char) -> Unit) {
        var child = firstChild[node]
        while (child >= 0) {
            visit(child, nodeChar[child])
            child = nextSibling[child]
        }
    }

    /** The forms at [node], for [walk]. */
    inline fun eachWordAt(node: Int, f: (Int) -> Unit) {
        var i = wordAt(node)
        while (i >= 0) { f(i); i = nextForm(i) }
    }

    fun wordAt(node: Int) = nodeWord[node]
    fun nextForm(id: Int) = sameKey[id]
    fun bestBelow(node: Int) = subBest[node]

    /** All forms with letters only, for swipe typing: ids, the commonest first. */
    fun swipeable(minProb: Int): List<Int> {
        val out = ArrayList<Int>(60_000)
        for (i in forms.indices) {
            val f = forms[i]
            if (probs[i] < minProb || offensive[i] || f.length < 2 && f != "I" && f != "a") continue
            if (f.all { it.isLetter() || it == '\'' || it == '-' }) out += i
        }
        out.sortByDescending { probs[it] }
        return out
    }
}
