package com.tokalot.app

import android.content.Context

/**
 * The list of changes per version, kept in assets/CHANGELOG.md. The same file is the text of each
 * GitHub release, so the update banner can show what a new version brings before it's installed.
 */
object Changelog {
    const val FILE = "CHANGELOG.md"

    class Entry(val version: String, val date: String?, val items: List<String>)

    /** This version's own changelog, as built into the app. */
    fun bundled(ctx: Context): List<Entry> =
        runCatching { parse(ctx.assets.open(FILE).bufferedReader().use { it.readText() }) }.getOrDefault(emptyList())

    /**
     * "## 1.27 · 2026-10-08" starts a version; "- " lines under it are its changes (an indented line
     * carries on the one above). Anything else (the title, comments, blank lines) is skipped.
     */
    fun parse(md: String): List<Entry> {
        val out = mutableListOf<Entry>()
        var version: String? = null
        var date: String? = null
        val items = mutableListOf<String>()
        var inComment = false
        fun flush() {
            version?.let { if (items.isNotEmpty()) out.add(Entry(it, date, items.toList())) }
            items.clear()
        }
        for (raw in md.lines()) {
            val line = raw.trim()
            if (inComment) { if ("-->" in line) inComment = false; continue }
            if (line.startsWith("<!--")) { inComment = "-->" !in line; continue }
            when {
                line.startsWith("## ") -> {
                    flush()
                    val head = line.removePrefix("## ").split('·', limit = 2)
                    version = head[0].trim().removePrefix("v").ifEmpty { null }
                    date = head.getOrNull(1)?.trim()?.ifEmpty { null }
                }
                version == null -> {}
                line.startsWith("- ") || line.startsWith("* ") -> items.add(plain(line.drop(2)))
                line.isNotEmpty() && raw.startsWith(" ") && items.isNotEmpty() ->
                    items[items.size - 1] = items.last() + " " + plain(line)
            }
        }
        flush()
        return out
    }

    /** The entries newer than [installed], up to and including [upTo] (when given), newest first. */
    fun since(entries: List<Entry>, installed: String, upTo: String? = null): List<Entry> =
        entries.filter {
            Updater.isNewer(it.version, installed) && (upTo == null || !Updater.isNewer(it.version, upTo))
        }

    /**
     * A GitHub release body as plain text, for a release made before the changelog existed:
     * headings and bullets kept as lines, markdown marks and the "Full Changelog" link dropped.
     */
    fun releaseText(body: String): String = body.lines()
        .map { it.trim() }
        .filter { !it.startsWith("**Full Changelog**") && !it.startsWith("<!--") }
        .map { l ->
            when {
                l.startsWith("#") -> plain(l.trimStart('#').trim())
                l.startsWith("- ") || l.startsWith("* ") -> "• " + plain(l.drop(2))
                else -> plain(l)
            }
        }
        .joinToString("\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    /** Drops **bold**, `code` and [link](url) marks, keeping the words. */
    private fun plain(s: String) = s
        .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        .replace("**", "")
        .replace("`", "")
        .trim()
}
