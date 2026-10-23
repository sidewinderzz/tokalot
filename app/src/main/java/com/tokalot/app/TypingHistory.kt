package com.tokalot.app

/**
 * What the user actually types on the keyboard, so suggestions follow them: how often each word was used
 * and which word followed which. Kept on this phone only, as a small text file (see [TokalotKeyboard]),
 * never synced: it is a record of what was typed. New words that are worth keeping go into Tokalot's
 * dictionary instead, which does sync (see [KeyboardLearn]).
 *
 * Bounded: past [MAX] entries the least used half is dropped, so it can't grow without end.
 */
class TypingHistory {
    private val uni = HashMap<String, Int>()
    private val bi = HashMap<String, Int>()
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
                if (t[0].contains(' ')) h.bi[t[0]] = n else h.uni[t[0]] = n
            }
            return h
        }
    }

    /** One word typed and kept, after [prev] (null at the start of a sentence or field). */
    fun used(word: String, prev: String?) {
        if (word.isBlank() || word.length > 40) return
        bump(uni, word)
        if (prev != null) bump(bi, "$prev $word")
        changed = true
    }

    fun count(word: String) = uni[word] ?: 0
    fun pairCount(prev: String, word: String) = bi["$prev $word"] ?: 0

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
        changed = true
    }

    fun clear() { uni.clear(); bi.clear(); changed = true }

    fun serialize(): String {
        changed = false
        return buildString {
            for ((k, v) in uni) append(k).append('\t').append(v).append('\n')
            for ((k, v) in bi) append(k).append('\t').append(v).append('\n')
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
