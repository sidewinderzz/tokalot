package com.tokalot.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * The keyboard's clipboard list: text copied on this phone, newest first. Pinned items stay until unpinned;
 * the rest go after [KEEP_MS], and only the newest [MAX] are kept. It stays on this phone (never synced or
 * backed up), and nothing copied from a password field or marked sensitive is ever added.
 */
class ClipHistory {
    class Item(val text: String, val time: Long, val pinned: Boolean)

    companion object {
        const val KEEP_MS = 60 * 60 * 1000L
        const val MAX = 30
        /** How long something just copied is offered on the suggestion bar. */
        const val FRESH_MS = 90 * 1000L
        /** Longer than this isn't kept (a whole document copied). */
        const val LONGEST = 20_000

        fun parse(json: String): ClipHistory {
            val h = ClipHistory()
            runCatching {
                val a = JSONArray(json)
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    h.items += Item(o.getString("text"), o.getLong("time"), o.optBoolean("pinned"))
                }
            }
            return h
        }

        private val url = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"']+""")
        private val email = Regex("""(?i)\b[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}\b""")
        private val phone = Regex("""(?<![\w.])\+?\(?\d[\d ().-]{6,}\d(?![\w.])""")

        /**
         * What a copy offers on the suggestion bar: a web address, an email address or a phone number found
         * in it (each on its own chip), then the whole thing. At most three.
         */
        fun smart(text: String): List<String> {
            val t = text.trim()
            if (t.isEmpty()) return emptyList()
            val found = LinkedHashSet<String>()
            url.findAll(t).forEach { found += it.value.trimEnd('.', ',', ')', '!', '?', ';', ':') }
            email.findAll(t).forEach { m -> if (found.none { m.value in it }) found += m.value }
            phone.findAll(t).forEach { m -> if (m.value.count { it.isDigit() } in 7..15 && found.none { m.value in it }) found += m.value.trim() }
            found.remove(t)
            return found.take(2) + t
        }
    }

    val items = ArrayList<Item>()
    var changed = false
        private set

    /** Something was copied at [now]. Returns false if it isn't kept (empty, too long). */
    fun add(text: String, now: Long): Boolean {
        if (text.isBlank() || text.length > LONGEST) return false
        val old = items.firstOrNull { it.text == text }
        items.removeAll { it.text == text }
        items.add(0, Item(text, now, old?.pinned ?: false))
        prune(now)
        changed = true
        return true
    }

    /** Drops what's past its time, and the oldest beyond [MAX] (pinned ones are never dropped). */
    fun prune(now: Long) {
        val before = items.size
        items.removeAll { !it.pinned && now - it.time > KEEP_MS }
        var unpinned = 0
        items.removeAll { !it.pinned && ++unpinned > MAX }
        if (items.size != before) changed = true
    }

    fun pin(text: String, on: Boolean) {
        val i = items.indexOfFirst { it.text == text }
        if (i < 0) return
        items[i] = Item(text, items[i].time, on)
        changed = true
    }

    fun remove(text: String) {
        if (items.removeAll { it.text == text }) changed = true
    }

    /** Clears everything but the pinned items. */
    fun clear() {
        if (items.removeAll { !it.pinned }) changed = true
    }

    /** The newest item if it was copied within [FRESH_MS] of [now]. */
    fun fresh(now: Long): Item? = items.maxByOrNull { it.time }?.takeIf { now - it.time in 0..FRESH_MS }

    fun serialize(): String {
        changed = false
        return JSONArray(items.map { JSONObject().put("text", it.text).put("time", it.time).put("pinned", it.pinned) }).toString()
    }
}
