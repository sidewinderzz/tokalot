package com.tokalot.app

/**
 * What the user actually types on the keyboard, so suggestions follow them: how often each word was used,
 * which word followed which, and which followed two words in a row. Kept on this phone only, as a small text file (see [TokalotKeyboard]),
 * never synced: it is a record of what was typed. New words that are worth keeping go into Tokalot's
 * dictionary instead, which does sync (see [KeyboardLearn]).
 *
 * Bounded: past [MAX] entries the least used half is dropped, so it can't grow without end.
 */
class TypingHistory {
    private val uni = HashMap<String, Int>()
    private val bi = HashMap<String, Int>()
    private val tri = HashMap<String, Int>()
    var changed = false
        private set

    /** Written to disk: [changed] until the next use. */
    fun saved() { changed = false }

    companion object {
        const val MAX = 6000

        fun parse(text: String): TypingHistory {
            val h = TypingHistory()
            for (line in text.lineSequence()) {
                val t = line.split('\t')
                if (t.size != 2) continue
                val n = t[1].toIntOrNull() ?: continue
                when (t[0].count { it == ' ' }) { 0 -> h.uni[t[0]] = n; 1 -> h.bi[t[0]] = n; 2 -> h.tri[t[0]] = n }
            }
            return h
        }
    }

    /** One word typed and kept, after [prev] (null at the start of a sentence or field) and [prev2] before that. */
    fun used(word: String, prev: String?, prev2: String? = null) {
        if (word.isBlank() || word.length > 40) return
        bump(uni, word)
        if (prev != null) bump(bi, "$prev $word")
        if (prev != null && prev2 != null) bump(tri, "$prev2 $prev $word")
        changed = true
    }

    /**
     * Takes back one [used] (a correction was undone, so the corrected word wasn't what the user meant).
     */
    fun unused(word: String, prev: String?, prev2: String? = null) {
        drop(uni, word)
        if (prev != null) drop(bi, "$prev $word")
        if (prev != null && prev2 != null) drop(tri, "$prev2 $prev $word")
        changed = true
    }

    private fun drop(map: HashMap<String, Int>, k: String) {
        val n = map[k] ?: return
        if (n <= 1) map.remove(k) else map[k] = n - 1
    }

    fun count(word: String) = uni[word] ?: 0
    fun pairCount(prev: String, word: String) = bi["$prev $word"] ?: 0
    fun tripleCount(prev2: String, prev: String, word: String) = tri["$prev2 $prev $word"] ?: 0

    /** The words most often typed after [prev], most often first. */
    fun after(prev: String, n: Int): List<String> {
        val start = "$prev "
        return bi.entries.asSequence().filter { it.key.startsWith(start) && it.value >= 2 }
            .sortedByDescending { it.value }.take(n).map { it.key.substring(start.length) }.toList()
    }

    /** Forgets a word entirely (it was removed from the dictionary, say). */
    fun forget(word: String) {
        uni.remove(word)
        bi.keys.removeAll { k -> k.split(' ').contains(word) }
        tri.keys.removeAll { k -> k.split(' ').contains(word) }
        changed = true
    }

    fun clear() { uni.clear(); bi.clear(); tri.clear(); changed = true }

    fun serialize(): String {
        changed = false
        return buildString {
            for ((k, v) in uni) append(k).append('\t').append(v).append('\n')
            for ((k, v) in bi) append(k).append('\t').append(v).append('\n')
            for ((k, v) in tri) append(k).append('\t').append(v).append('\n')
        }
    }

    private fun bump(map: HashMap<String, Int>, k: String) {
        map[k] = (map[k] ?: 0) + 1
        if (map.size > MAX) {
            val keep = map.entries.sortedByDescending { it.value }.take(MAX / 2).associate { it.key to it.value }
            map.clear(); map.putAll(keep)
        }
    }
}
