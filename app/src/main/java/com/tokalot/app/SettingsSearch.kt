package com.tokalot.app

/**
 * The search box on the Settings list. It matches what each setting is called, the line under it, and the everyday
 * words people type for it ("dark" finds Theme, "wifi" finds the weak-signal option). Pure, so it's unit-tested.
 */
object SettingsSearch {
    /** One setting: the title on its row, the line under it, the page it's on, and where to say it is. */
    data class Row(val title: String, val sub: String, val page: String, val where: String)

    /** A result: open [page] and flash [title]. [note] says if it's somewhere else as well. */
    data class Hit(val title: String, val where: String, val page: String, val note: String?)

    /** Everyday words, and the words in Tokalot's settings they mean. */
    private val SAME = mapOf(
        "dark" to listOf("theme", "appearance"), "light" to listOf("theme", "appearance"), "night" to listOf("theme"),
        "color" to listOf("theme", "accent"), "colour" to listOf("theme", "accent"), "font" to listOf("text size"),
        "wifi" to listOf("weak signal", "on-device"), "internet" to listOf("weak signal", "on-device"),
        "offline" to listOf("on-device", "weak signal"), "signal" to listOf("weak signal"), "data" to listOf("weak signal", "sync"),
        "local" to listOf("on-device"), "download" to listOf("on-device", "model"),
        "listen" to listOf("read aloud", "voice"), "tts" to listOf("read aloud", "voice"), "pdf" to listOf("read aloud"),
        "article" to listOf("read aloud"), "speak" to listOf("read aloud", "voice"),
        "language" to listOf("detect language"), "spanish" to listOf("detect language"),
        "grammar" to listOf("cleanup", "polish"), "ai" to listOf("cleanup", "polish", "api keys"), "fix" to listOf("cleanup", "autocorrect"),
        "key" to listOf("api keys"), "openai" to listOf("api keys"), "groq" to listOf("api keys"), "gemini" to listOf("api keys"),
        "push" to listOf("hold to talk"), "press" to listOf("hold to talk"), "vibrate" to listOf("haptics"), "buzz" to listOf("haptics"),
        "beep" to listOf("sounds"), "chime" to listOf("sounds"), "headset" to listOf("headset", "microphone"), "bluetooth" to listOf("headset", "microphone"),
        "mic" to listOf("microphone", "headset"), "button" to listOf("floating", "mic button", "tile"), "bubble" to listOf("floating", "mic button"),
        "volume" to listOf("volume keys"), "tile" to listOf("tile"), "terminal" to listOf("shell"), "termux" to listOf("shell"),
        "typing" to listOf("keyboard"), "spelling" to listOf("autocorrect"), "swipe" to listOf("swipe"),
        "drive" to listOf("sync"), "dropbox" to listOf("sync"), "restore" to listOf("backup"), "storage" to listOf("recordings", "delete all"),
        "space" to listOf("recordings", "delete all"), "cost" to listOf("usage"), "money" to listOf("usage"), "stats" to listOf("usage"),
        "update" to listOf("beta updates", "check for updates", "changelog"), "version" to listOf("check for updates", "about"),
        "pause" to listOf("skip silence"), "pauses" to listOf("skip silence"), "gaps" to listOf("skip silence"),
        "um" to listOf("skip ums"), "uh" to listOf("skip ums"), "filler" to listOf("skip ums"),
        "fast" to listOf("speed", "playback speed", "auto speed"), "slow" to listOf("speed", "playback speed", "auto speed"),
        "wpm" to listOf("auto speed aims"), "words" to listOf("auto speed aims"),
        "summary" to listOf("summary length"), "whatsapp" to listOf("voice notes"), "note" to listOf("voice notes", "note phrases"),
        "private" to listOf("privacy"), "privacy" to listOf("privacy"),
    )

    /** Words that say nothing about which setting: "turn on dark mode" searches for dark. */
    private val STOP = setOf("the", "a", "an", "to", "my", "turn", "on", "off", "mode", "setting", "settings", "change", "how", "set")

    /** Settings that also live somewhere outside Settings. */
    private val ALSO = mapOf(
        "Skip silence" to "also in each note's ⋮ menu",
        "Skip ums" to "also in each note's ⋮ menu",
        "Playback speed" to "also the speed button on a note",
        "Auto speed" to "also the speed button on a note",
        "Summary length" to "also on a note's Summary card",
    )

    fun find(query: String, rows: List<Row>, max: Int = 25): List<Hit> {
        val words = query.lowercase().split(Regex("[^\\p{L}\\p{N}-]+")).filter { it.isNotEmpty() && it !in STOP }
        if (words.isEmpty()) return emptyList()
        val scored = rows.mapNotNull { r ->
            val title = r.title.lowercase()
            val sub = r.sub.lowercase()
            var total = 0
            for (w in words) {
                val s = when {
                    title.startsWith(w) || title.split(' ').any { it.startsWith(w) } -> 10
                    title.contains(w) -> 7
                    SAME[w]?.any { title.contains(it) } == true -> 6
                    sub.contains(w) -> 3
                    SAME[w]?.any { sub.contains(it) } == true -> 2
                    r.where.lowercase().contains(w) -> 1
                    else -> return@mapNotNull null // every word has to match somewhere
                }
                total += s
            }
            r to total
        }
        return scored.sortedWith(compareByDescending<Pair<Row, Int>> { it.second }.thenBy { rows.indexOf(it.first) })
            .take(max).map { (r, _) -> Hit(r.title, r.where, r.page, ALSO[r.title]) }
    }
}
