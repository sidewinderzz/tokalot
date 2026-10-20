package com.tokalot.app

import java.io.BufferedReader

/**
 * The emoji page's contents (from assets/keyboard/emoji.txt; see tools/keyboard/make_emoji.py): emoji in
 * groups, each with the words that find it in search, and which ones take a skin tone.
 */
class EmojiData private constructor(val groups: List<Group>) {
    class Emoji(val text: String, val tones: Boolean, val words: List<String>)
    class Group(val name: String, val emoji: List<Emoji>)

    companion object {
        val TONES = listOf("🏻", "🏼", "🏽", "🏾", "🏿")

        fun load(reader: BufferedReader): EmojiData {
            val groups = ArrayList<Group>()
            var name: String? = null
            var list = ArrayList<Emoji>()
            reader.forEachLine { line ->
                if (line.startsWith("=")) {
                    name?.let { groups += Group(it, list) }
                    name = line.substring(1); list = ArrayList()
                } else {
                    val t = line.split('\t')
                    if (t.size >= 2) list += Emoji(t[0], t[1] == "1", t.getOrNull(2)?.split(' ')?.filter { it.isNotEmpty() } ?: emptyList())
                }
            }
            name?.let { groups += Group(it, list) }
            return EmojiData(groups)
        }

        /**
         * [emoji] in skin tone [tone] (0 = none, 1-5 light to dark): the modifier goes after the first
         * character, before any joiner, so 👍 becomes 👍🏽 and 🧑‍🌾 becomes 🧑🏽‍🌾.
         */
        fun withTone(emoji: String, tone: Int): String {
            if (tone !in 1..5) return emoji
            val first = Character.charCount(emoji.codePointAt(0))
            var rest = emoji.substring(first)
            if (rest.startsWith("️")) rest = rest.substring(1)
            return emoji.substring(0, first) + TONES[tone - 1] + rest
        }
    }

    /** Emoji whose words start with every word of [query], best matches (a word equal to the query) first. */
    fun search(query: String, limit: Int = 60): List<Emoji> {
        val parts = query.lowercase().split(' ').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        val hits = ArrayList<Pair<Emoji, Int>>()
        for (g in groups) for (e in g.emoji) {
            if (parts.all { p -> e.words.any { it.startsWith(p) } }) {
                val exact = e.words.indexOfFirst { it == parts[0] }
                hits += e to (if (exact >= 0) exact else 100 + e.words.indexOfFirst { it.startsWith(parts[0]) })
            }
        }
        return hits.sortedBy { it.second }.take(limit).map { it.first }
    }
}
