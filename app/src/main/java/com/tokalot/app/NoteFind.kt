package com.tokalot.app

/**
 * Finding notes on the Notes page: the search box, the tag chips, the sort menu and the date range. No Android
 * types, so it's unit-tested on a plain JVM. The desktop apps follow the same rules (NoteFind in Notes.cs).
 */
object NoteFind {
    enum class Sort(val label: String) { NEWEST("Newest first"), OLDEST("Oldest first"), TITLE("Title A–Z") }

    /** What the list shows: [tag] null for all tags, [from]/[to] a day range in ms (to is exclusive), or 0 for no range. */
    data class Filter(val query: String = "", val tag: String? = null, val sort: Sort = Sort.NEWEST, val from: Long = 0, val to: Long = 0)

    /** One note as the list sees it. [title] is the AI's or the user's title, or null. */
    data class Item(val id: Long, val time: Long, val text: String, val title: String?, val tags: List<String>)

    /** The words of a search, lowercased. Every one of them has to be in a note for it to match. */
    fun words(query: String): List<String> = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

    fun matches(item: Item, words: List<String>): Boolean {
        if (words.isEmpty()) return true
        val hay = (item.title.orEmpty() + "\n" + item.text + "\n" + item.tags.joinToString(" ")).lowercase()
        return words.all { hay.contains(it) }
    }

    /** The notes [f] lets through, in its order. Title A–Z puts untitled notes last, by their first words. */
    fun apply(items: List<Item>, f: Filter): List<Item> {
        val w = words(f.query)
        val out = items.filter { i ->
            (f.tag == null || i.tags.any { it.equals(f.tag, ignoreCase = true) }) &&
                (f.from <= 0 || i.time >= f.from) && (f.to <= 0 || i.time < f.to) && matches(i, w)
        }
        return when (f.sort) {
            Sort.NEWEST -> out.sortedWith(compareByDescending<Item> { it.time }.thenByDescending { it.id })
            Sort.OLDEST -> out.sortedWith(compareBy<Item> { it.time }.thenBy { it.id })
            Sort.TITLE -> out.sortedWith(compareBy<Item> { it.title.isNullOrEmpty() }
                .thenBy { (it.title?.takeIf { t -> t.isNotEmpty() } ?: it.text.trim()).lowercase() }.thenByDescending { it.time })
        }
    }

    /** A bit of [text] around the first search word it has, and where each search word is in that bit (to highlight). */
    data class Snippet(val text: String, val hits: List<IntRange>)

    fun snippet(text: String, words: List<String>, around: Int = 40): Snippet? {
        val flat = text.trim().replace(Regex("\\s+"), " ")
        val low = flat.lowercase()
        val first = words.mapNotNull { w -> low.indexOf(w).takeIf { it >= 0 } }.minOrNull() ?: return null
        var start = (first - around).coerceAtLeast(0)
        var end = (first + around * 2).coerceAtMost(flat.length)
        // Whole words at the edges.
        if (start > 0) flat.indexOf(' ', start).takeIf { it in start until first }?.let { start = it + 1 }
        if (end < flat.length) flat.lastIndexOf(' ', end).takeIf { it > first }?.let { end = it }
        val lead = if (start > 0) "…" else ""
        val cut = lead + flat.substring(start, end) + if (end < flat.length) "…" else ""
        return Snippet(cut, hits(cut, words))
    }

    /** Where each of [words] is in [s], for highlighting. Overlaps are left as they are. */
    fun hits(s: String, words: List<String>): List<IntRange> {
        val low = s.lowercase()
        val out = ArrayList<IntRange>()
        for (w in words) {
            var at = low.indexOf(w)
            while (at >= 0) { out.add(at until at + w.length); at = low.indexOf(w, at + w.length) }
        }
        return out.sortedBy { it.first }
    }
}
