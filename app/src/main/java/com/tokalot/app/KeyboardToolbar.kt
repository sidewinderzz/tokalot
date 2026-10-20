package com.tokalot.app

/**
 * The buttons on the keyboard's top bar, in the order the user put them (Settings is the ⋮ menu on the
 * keyboard). Shown across the bar when there's nothing to suggest; while there is, the mic stays on the
 * side it's nearer to. The mic can be moved but never removed: dictating is what the keyboard is for.
 */
object KeyboardToolbar {
    const val MIC = "mic"
    const val FIX = "fix"
    const val CLIPBOARD = "clipboard"
    const val EMOJI = "emoji"
    const val THEME = "theme"
    const val NUMBERS = "numbers"
    const val SETTINGS = "settings"

    /** Every button there is, in the order the menu offers them. */
    val ALL = listOf(FIX, CLIPBOARD, MIC, EMOJI, THEME, NUMBERS, SETTINGS)
    /** Clipboard in the middle, AI fix on the left, the mic on the right. */
    val DEFAULT = listOf(FIX, CLIPBOARD, MIC)

    fun label(item: String) = when (item) {
        MIC -> "Dictate"
        FIX -> "AI fix"
        CLIPBOARD -> "Clipboard"
        EMOJI -> "Emoji"
        THEME -> "Light / dark"
        NUMBERS -> "Number row"
        SETTINGS -> "Settings"
        else -> item
    }

    /** The stored order ("fix,clipboard,mic"): unknown names dropped, each once, the mic always there. */
    fun parse(s: String?): List<String> {
        if (s == null) return DEFAULT
        val list = s.split(',').map { it.trim() }.filter { it in ALL }.distinct()
        return if (MIC in list) list else list + MIC
    }

    fun format(list: List<String>) = list.joinToString(",")

    /** Whether [s] is a stored order this version understands (for sync). */
    fun valid(s: String) = s.split(',').let { p -> p.all { it in ALL } && p.distinct().size == p.size && MIC in p }

    /** [item] moved [by] places (negative: left), stopping at either end. */
    fun move(list: List<String>, item: String, by: Int): List<String> {
        val i = list.indexOf(item)
        if (i < 0) return list
        val to = (i + by).coerceIn(0, list.size - 1)
        return list.toMutableList().apply { removeAt(i); add(to, item) }
    }

    fun remove(list: List<String>, item: String) = if (item == MIC) list else list - item

    /** Added at the end, unless already there. */
    fun add(list: List<String>, item: String) = if (item in list || item !in ALL) list else list + item

    /** While suggestions show, the mic sits on the left when it's in the bar's left half. */
    fun micOnLeft(list: List<String>) = list.indexOf(MIC) * 2 + 1 < list.size
}
