package com.tokalot.app

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * The sync file's format and the three-way merge, with no Android types so both are unit-tested
 * on a plain JVM. The Windows and Linux apps follow the same rules against the same file;
 * change nothing here without changing them too.
 */

/**
 * One voice note as the sync file holds it. The id is the time it was made, so two devices never pick the same one.
 * Title: null when the note has none, "" when it was cleared on purpose (so no device makes a new one).
 */
data class SyncNote(val id: Long, val time: Long, val text: String, val title: String? = null)

/** One month of one device's counts, as the sync file holds them. */
data class UsageMonth(
    val words: Long = 0, val dictations: Long = 0,
    /** Filler words and self-corrections cleaned up, and dictations done on-device. */
    val fillers: Long = 0, val corrections: Long = 0, val offline: Long = 0,
)

/**
 * One dictation as the sync file holds it (only when a device turned on "Include dictation history").
 * [app] is what the app was known by where it was dictated (an Android package, a Windows process name)
 * and [label] its name to show. Recordings never go in the file.
 */
data class SyncEntry(
    val id: Long, val time: Long, val text: String, val raw: String, val durationMs: Long,
    val cleaned: Boolean, val app: String, val label: String, val own: Boolean,
) {
    /** Tells two versions of an entry apart (its wording was swapped for the user's own, say). Kept in the base instead of the entry. */
    val hash: Int get() = SyncMerge.fnv(text + "\u0000" + raw + "\u0000" + own + "\u0000" + cleaned)
}

/**
 * One device's usage in the sync file, under that device's own id. Only that device ever changes its
 * entry, and nothing is added up in the file, so a count can't be counted twice or lost to another device.
 */
data class DeviceUsage(val name: String, val updated: Long, val months: Map<String, UsageMonth>) {
    val words get() = months.values.sumOf { it.words }
    val dictations get() = months.values.sumOf { it.dictations }
    val fillers get() = months.values.sumOf { it.fillers }
    val corrections get() = months.values.sumOf { it.corrections }
    val offline get() = months.values.sumOf { it.offline }
}

/**
 * What syncs: dictionary, snippets, explicitly chosen styles, the custom instructions, voice notes and the
 * other settings. Usage and dictation history are kept beside it (see [SyncMerge.usage], [SyncMerge.history]).
 */
data class SyncState(
    val words: List<String> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
    /** Category id -> style id, only for categories the user set themselves. */
    val styles: Map<String, String> = emptyMap(),
    val instructions: String = "",
    /** Newest first. */
    val notes: List<SyncNote> = emptyList(),
    /**
     * Every other synced setting, by its name in the file, each value as canonical JSON text (see
     * [SyncFormat.canon]) so two values compare with ==. Null in a base from before settings synced.
     */
    val settings: Map<String, String>? = emptyMap(),
)

/** What a sync file held. A field the file didn't have is null, and is then left as it is on this device. */
class SyncRemote(
    val words: List<String>?,
    val snippets: List<Snippet>?,
    val styles: Map<String, String>?,
    val instructions: String?,
    /** Service id -> API key; null when no device has opted in. */
    val keys: Map<String, String>?,
    val updated: Long = 0,
    val by: String = "",
    /** Null in a file last written by a version from before notes synced: this device's notes are then kept as they are. */
    val notes: List<SyncNote>? = null,
    /** Device id -> that device's usage; null in a file last written by a version from before usage synced. */
    val usage: Map<String, DeviceUsage>? = null,
    /** Null in a file from before settings synced: this device's settings are then kept as they are. */
    val settings: Map<String, String>? = null,
    /** Dictations, newest first; null when no device has put its history in the file. */
    val history: List<SyncEntry>? = null,
    /** The highest [SyncFormat.LEVEL] of any Tokalot that wrote the file. */
    val level: Int = 1,
)

class SyncResult(
    /** The merged settings: applied on this device, written to the file, and kept as the next base. */
    val state: SyncState,
    /** This device's API keys after the merge (service id -> key, "" if none). */
    val localKeys: Map<String, String>,
    /** The "keys" object the file should have; null leaves it out. */
    val fileKeys: Map<String, String>?,
    /** Whether the file has to be rewritten. */
    val write: Boolean,
)

/** The file can't be used; the message is fit to show as is. */
class SyncFormatException(message: String) : Exception(message)

object SyncFormat {
    const val APP = "tokalot-sync"
    const val FORMAT = 1
    const val FILE_NAME = "tokalot-sync.json"
    const val NOT_OURS = "That isn't a Tokalot sync file"
    const val TOO_NEW = "Update Tokalot to sync with this file"
    /**
     * What this version puts in the file, for telling an older Tokalot it is missing out. 1: dictionary,
     * snippets, styles, instructions, notes, word counts. 2: settings, clean-up counts, dictation history.
     * Raise it whenever something new goes in the file, on every platform at once. [FORMAT] only changes for a
     * change an older version would get wrong, since that one locks older versions out of the file.
     */
    const val LEVEL = 2

    val CATEGORIES = listOf("MESSAGING", "EMAIL", "AI_CODE", "OTHER")
    val STYLES = listOf("FORMAL", "CASUAL", "VERY_CASUAL")
    val SERVICES = listOf("groq", "anthropic", "openai", "gemini")

    /** The base for a first sync: nothing shared yet, and instructions still at their default. */
    fun firstBase(defaultInstructions: String) = SyncState(instructions = defaultInstructions)

    /**
     * Reads a sync file. Null means "no remote state yet" (a missing or empty file).
     * Throws [SyncFormatException] for a file that isn't ours or is from a newer format.
     */
    fun parse(text: String?): SyncRemote? {
        val body = text?.removePrefix("﻿")?.trim() ?: return null
        if (body.isEmpty()) return null
        val o = try { JSONObject(body) } catch (_: Exception) { throw SyncFormatException(NOT_OURS) }
        if (o.opt("app") != APP) throw SyncFormatException(NOT_OURS)
        val format = when (val f = o.opt("format")) {
            null -> FORMAT
            is Number -> f.toInt()
            else -> throw SyncFormatException(NOT_OURS)
        }
        if (format > FORMAT) throw SyncFormatException(TOO_NEW)

        val words = o.optJSONArray("words")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.opt(it) as? String }.filter { it.isNotBlank() }
        }
        val snippets = o.optJSONArray("snippets")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val s = arr.optJSONObject(i) ?: return@mapNotNull null
                val trigger = s.opt("trigger") as? String ?: return@mapNotNull null
                val t = s.opt("text") as? String ?: return@mapNotNull null
                if (trigger.isBlank()) null else Snippet(trigger, t)
            }
        }
        val styles = o.optJSONObject("styles")?.let { s ->
            val out = LinkedHashMap<String, String>()
            for (c in CATEGORIES) (s.opt(c) as? String)?.takeIf { it in STYLES }?.let { out[c] = it }
            out
        }
        val keys = o.optJSONObject("keys")?.let { k ->
            val out = LinkedHashMap<String, String>()
            for (name in k.keys()) (k.opt(name) as? String)?.trim()?.takeIf { it.isNotEmpty() }?.let { out[name] = it }
            out.takeIf { it.isNotEmpty() }
        }
        val notes = o.optJSONArray("notes")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val n = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = (n.opt("id") as? Number)?.toLong() ?: return@mapNotNull null
                val t = n.opt("text") as? String ?: return@mapNotNull null
                SyncNote(id, (n.opt("time") as? Number)?.toLong() ?: id, t, n.opt("title") as? String)
            }
        }
        val settings = o.optJSONObject("settings")?.let { s ->
            val out = sortedMapOf<String, String>()
            for (k in s.keys()) canon(s.opt(k))?.let { out[k] = it }
            out
        }
        val history = o.optJSONArray("history")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val e = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = (e.opt("id") as? Number)?.toLong() ?: return@mapNotNull null
                val text = e.opt("text") as? String ?: return@mapNotNull null
                SyncEntry(
                    id, (e.opt("time") as? Number)?.toLong() ?: id, text, e.opt("raw") as? String ?: "",
                    (e.opt("dur") as? Number)?.toLong()?.coerceAtLeast(0) ?: 0, e.opt("ai") == true,
                    e.opt("app") as? String ?: "", e.opt("label") as? String ?: "", e.opt("own") == true,
                )
            }
        }
        return SyncRemote(
            words, snippets, styles, o.opt("instructions") as? String, keys,
            (o.opt("updated") as? Number)?.toLong() ?: 0, o.opt("by") as? String ?: "", notes,
            o.optJSONObject("usage")?.let { parseUsage(it) }, settings, history,
            (o.opt("level") as? Number)?.toInt()?.coerceAtLeast(1) ?: 1,
        )
    }

    /**
     * A setting's value as JSON text with nothing left to chance: object keys sorted, empty strings in an object
     * dropped (a model name left blank means "the default"), whole numbers without a decimal point. Null for null.
     */
    fun canon(v: Any?): String? = when (v) {
        null, JSONObject.NULL -> null
        is Boolean -> v.toString()
        is Number -> v.toDouble().let { d -> if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 9e15) d.toLong().toString() else d.toString() }
        is String -> JSONObject.quote(v)
        is JSONArray -> (0 until v.length()).mapNotNull { canon(v.opt(it)) }.joinToString(",", "[", "]")
        is JSONObject -> v.keys().asSequence().sorted()
            .mapNotNull { k -> canon(v.opt(k))?.takeIf { it != "\"\"" }?.let { JSONObject.quote(k) + ":" + it } }
            .joinToString(",", "{", "}")
        else -> null
    }

    /** The value [canon] wrote, back as a JSON value (String, Boolean, Number, JSONArray or JSONObject). */
    fun value(canonical: String): Any? = runCatching { JSONTokener(canonical).nextValue() }.getOrNull()

    private val MONTH = Regex("""\d{4}-\d{2}""")

    /** A "usage" object. Anything that doesn't fit is skipped, never an error: it only feeds a number on screen. */
    private fun parseUsage(u: JSONObject): Map<String, DeviceUsage> {
        fun count(v: Any?): Long = (v as? Number)?.toLong()?.coerceAtLeast(0L) ?: 0L
        val out = LinkedHashMap<String, DeviceUsage>()
        for (id in u.keys()) {
            val d = u.optJSONObject(id) ?: continue
            if (id.isBlank()) continue
            val months = LinkedHashMap<String, UsageMonth>()
            d.optJSONObject("months")?.let { m ->
                for (k in m.keys()) {
                    val c = m.optJSONObject(k) ?: continue
                    if (MONTH.matches(k)) months[k] = UsageMonth(
                        count(c.opt("words")), count(c.opt("dictations")),
                        count(c.opt("fillers")), count(c.opt("corrections")), count(c.opt("offline")),
                    )
                }
            }
            out[id] = DeviceUsage(d.opt("name") as? String ?: "", (d.opt("updated") as? Number)?.toLong() ?: 0, months)
        }
        return out
    }

    private fun usageJson(usage: Map<String, DeviceUsage>) = JSONObject().also { u ->
        for (id in usage.keys.sorted()) {
            val d = usage.getValue(id)
            val months = JSONObject()
            for (k in d.months.keys.sorted()) {
                val m = d.months.getValue(k)
                val o = JSONObject().put("words", m.words).put("dictations", m.dictations)
                // Left out when zero, so a month only has what was counted.
                if (m.fillers > 0) o.put("fillers", m.fillers)
                if (m.corrections > 0) o.put("corrections", m.corrections)
                if (m.offline > 0) o.put("offline", m.offline)
                months.put(k, o)
            }
            u.put(id, JSONObject().put("name", d.name).put("updated", d.updated).put("months", months))
        }
    }

    /** The app's private copy of the file's usage, for showing the totals between syncs. */
    fun encodeUsage(usage: Map<String, DeviceUsage>): String = usageJson(usage).toString()

    fun parseUsage(text: String?): Map<String, DeviceUsage> =
        text?.let { runCatching { parseUsage(JSONObject(it)) }.getOrNull() } ?: emptyMap()

    /**
     * The file's text: pretty-printed JSON. [keys] null leaves the "keys" object out, [usage] null or
     * empty leaves "usage" out, and [history] null leaves "history" out.
     */
    fun encode(
        state: SyncState, keys: Map<String, String>?, updated: Long, by: String,
        usage: Map<String, DeviceUsage>? = null, history: List<SyncEntry>? = null, level: Int = LEVEL,
    ): String {
        val o = JSONObject()
        o.put("app", APP)
        o.put("format", FORMAT)
        o.put("level", maxOf(level, LEVEL))
        o.put("updated", updated)
        o.put("by", by)
        o.put("words", JSONArray().also { arr -> state.words.forEach { arr.put(it) } })
        o.put("snippets", JSONArray().also { arr ->
            state.snippets.forEach { arr.put(JSONObject().put("trigger", it.trigger).put("text", it.text)) }
        })
        o.put("styles", JSONObject().also { s -> state.styles.forEach { (c, v) -> s.put(c, v) } })
        o.put("instructions", state.instructions)
        o.put("notes", JSONArray().also { arr ->
            state.notes.forEach { n ->
                arr.put(JSONObject().put("id", n.id).put("time", n.time).put("text", n.text).also { o -> n.title?.let { o.put("title", it) } })
            }
        })
        state.settings?.let { s ->
            o.put("settings", JSONObject().also { j -> s.forEach { (k, v) -> value(v)?.let { j.put(k, it) } } })
        }
        if (!usage.isNullOrEmpty()) o.put("usage", usageJson(usage))
        if (history != null) o.put("history", JSONArray().also { arr ->
            history.forEach {
                val e = JSONObject().put("id", it.id).put("time", it.time).put("text", it.text).put("raw", it.raw)
                    .put("dur", it.durationMs).put("ai", it.cleaned).put("app", it.app).put("label", it.label)
                if (it.own) e.put("own", true)
                arr.put(e)
            }
        })
        if (!keys.isNullOrEmpty()) o.put("keys", JSONObject().also { k -> keys.forEach { (name, v) -> k.put(name, v) } })
        return o.toString(2)
    }

    /** The app's private copy of the base: the same shape as the file, never with keys. */
    fun encodeBase(state: SyncState): String = encode(state, null, 0, "")

    fun parseBase(text: String?, defaultInstructions: String): SyncState {
        val r = (try { parse(text) } catch (_: SyncFormatException) { null }) ?: return firstBase(defaultInstructions)
        return SyncState(
            r.words ?: emptyList(), r.snippets ?: emptyList(), r.styles ?: emptyMap(), r.instructions ?: defaultInstructions,
            r.notes ?: emptyList(), r.settings,
        )
    }

    /** The history part of the base: each entry's id and [SyncEntry.hash], which is all the merge needs from it. */
    fun encodeHistoryBase(entries: List<SyncEntry>): String =
        JSONObject().also { o -> entries.forEach { o.put(it.id.toString(), it.hash) } }.toString()

    fun parseHistoryBase(text: String?): Map<Long, Int> = runCatching {
        val o = JSONObject(text ?: return emptyMap())
        o.keys().asSequence().mapNotNull { k -> k.toLongOrNull()?.let { id -> (o.opt(k) as? Number)?.let { id to it.toInt() } } }.toMap()
    }.getOrDefault(emptyMap())
}

object SyncMerge {
    const val EMPTY_FILE = "The sync file is empty right now. Will try again. To start it over, choose the file again."

    /**
     * True when the file has no state but this device already shared something through it. That is a
     * cloud placeholder or another device mid-write far more often than a real fresh start, and treating
     * it as one would write only this device's settings and make every other device delete the rest.
     */
    fun remoteLost(remote: SyncRemote?, base: SyncState): Boolean =
        remote == null && (base.words.isNotEmpty() || base.snippets.isNotEmpty() || base.styles.isNotEmpty() || base.notes.isNotEmpty() ||
            !base.settings.isNullOrEmpty())

    /** How words and snippet triggers are compared: ignoring case and surrounding spaces. */
    fun id(s: String) = s.trim().lowercase()

    /**
     * Three-way merge. [local] is this device now, [remote] the file (null = no remote state yet),
     * [base] what this device had at the end of its last successful sync.
     */
    fun merge(
        local: SyncState, remote: SyncRemote?, base: SyncState,
        localKeys: Map<String, String>, includeKeys: Boolean, knownKeys: Set<String> = emptySet(),
        usable: (name: String, value: String) -> Boolean = { _, _ -> true },
    ): SyncResult {
        val state = SyncState(
            words(local.words, remote?.words, base.words),
            snippets(local.snippets, remote?.snippets, base.snippets),
            styles(local.styles, remote?.styles, base.styles),
            if (local.instructions == base.instructions && remote?.instructions != null) remote.instructions else local.instructions,
            notes(local.notes, remote?.notes, base.notes),
            settings(local.settings.orEmpty(), remote?.settings, base.settings, usable),
        )

        // Keys have no base: an empty slot here is filled from the file, and the file only
        // gets this device's keys when it opted in. Deleting a key never spreads. A slot this device
        // has filled before ([knownKeys]) and is empty now was cleared on purpose, so it stays empty.
        val theirs = remote?.keys ?: emptyMap()
        val mine = LinkedHashMap<String, String>()
        for (s in SyncFormat.SERVICES) {
            val have = localKeys[s]?.trim().orEmpty()
            mine[s] = have.ifEmpty { if (s in knownKeys) "" else theirs[s].orEmpty() }
        }
        val fileKeys: Map<String, String>? = if (!includeKeys) remote?.keys else {
            val out = LinkedHashMap<String, String>()
            for (s in SyncFormat.SERVICES) (mine[s].orEmpty().ifEmpty { null })?.let { out[s] = it }
            theirs.forEach { (s, v) -> if (s !in out) out[s] = v }
            out.takeIf { it.isNotEmpty() }
        }

        val write = remote == null || !sameContent(state, remote) || fileKeys.orEmpty() != remote.keys.orEmpty()
        return SyncResult(state, mine, fileKeys, write)
    }

    /** The file's order first, then what only this device has: every device settles on the same list. */
    private fun words(l: List<String>, r: List<String>?, b: List<String>): List<String> {
        val mine = LinkedHashMap<String, String>()
        l.forEach { w -> id(w).takeIf { it.isNotEmpty() }?.let { mine.putIfAbsent(it, w) } }
        if (r == null) return mine.values.toList()
        val baseSpelling = HashMap<String, String>()
        b.forEach { baseSpelling.putIfAbsent(id(it), it) }
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (w in r) {
            val k = id(w)
            if (k.isEmpty() || !seen.add(k)) continue
            val here = mine[k]
            val was = baseSpelling[k]
            when {
                here == null -> if (was == null) out.add(w) // else: deleted here
                // The file's spelling, unless it was respelled on this device since the last sync.
                else -> out.add(if (was != null && here != was) here else w)
            }
        }
        for ((k, w) in mine) {
            if (k in seen) continue
            if (k !in baseSpelling) out.add(w) // else: deleted elsewhere
        }
        return out
    }

    private fun snippets(l: List<Snippet>, r: List<Snippet>?, b: List<Snippet>): List<Snippet> {
        val mine = LinkedHashMap<String, Snippet>()
        l.forEach { s -> id(s.trigger).takeIf { it.isNotEmpty() }?.let { mine.putIfAbsent(it, s) } }
        if (r == null) return mine.values.toList()
        val baseText = HashMap<String, String>()
        b.forEach { baseText.putIfAbsent(id(it.trigger), it.text) }
        val seen = HashSet<String>()
        val out = ArrayList<Snippet>()
        for (s in r) {
            val k = id(s.trigger)
            if (k.isEmpty() || !seen.add(k)) continue
            val here = mine[k]
            when {
                here == null -> if (k !in baseText) out.add(s) // else: deleted here
                // Unchanged here since the last sync, so a different text was changed elsewhere.
                else -> out.add(Snippet(s.trigger, if (baseText[k] == here.text) s.text else here.text))
            }
        }
        for ((k, s) in mine) {
            if (k in seen) continue
            if (k !in baseText) out.add(s) // else: deleted elsewhere
        }
        return out
    }

    /**
     * Notes match by id. One that a side had at the last sync and no longer has was deleted there; a text
     * changed since the last sync wins over the unchanged one, and so does a title, on its own. A side that doesn't
     * know titles (an older version) never clears one. Newest first.
     */
    private fun notes(l: List<SyncNote>, r: List<SyncNote>?, b: List<SyncNote>): List<SyncNote> {
        if (r == null) return l
        val mine = LinkedHashMap<Long, SyncNote>().also { m -> l.forEach { m.putIfAbsent(it.id, it) } }
        val theirs = LinkedHashMap<Long, SyncNote>().also { m -> r.forEach { m.putIfAbsent(it.id, it) } }
        val base = b.associateBy { it.id }
        val out = ArrayList<SyncNote>()
        for (id in LinkedHashSet(theirs.keys + mine.keys)) {
            val here = mine[id]
            val there = theirs[id]
            val was = base[id]
            when {
                here != null && there != null -> {
                    val title = if (here.title != was?.title) here.title ?: there.title else there.title ?: here.title
                    out.add((if (was != null && here.text == was.text) there else here).copy(title = title))
                }
                here != null -> if (was == null) out.add(here) // else: deleted elsewhere
                there != null -> if (was == null) out.add(there) // else: deleted here
            }
        }
        return out.sortedWith(compareByDescending<SyncNote> { it.time }.thenByDescending { it.id })
    }

    private fun styles(l: Map<String, String>, r: Map<String, String>?, b: Map<String, String>): Map<String, String> {
        if (r == null) return l
        val out = LinkedHashMap<String, String>()
        val categories = SyncFormat.CATEGORIES + (l.keys + r.keys + b.keys).filter { it !in SyncFormat.CATEGORIES }
        for (c in categories) {
            // Untouched here ("not set" counts): follow the file, which may also be "not set".
            val v = if (l[c] == b[c]) r[c] else l[c]
            if (v != null) out[c] = v
        }
        return out
    }

    /**
     * Settings are compared one by one. [local] holds every setting this device syncs. One this device
     * doesn't have (the indicator on a PC, haptics on a phone) passes through the file untouched, and so does
     * a value this device can't use ([usable] false, say a choice only a newer version knows). Otherwise a
     * setting changed here since the last sync wins, and one left alone follows the file. A setting that
     * isn't in the base yet (the first sync, or one added in an update) counts as left alone, so a device
     * that joins takes the settings already in the file.
     */
    fun settings(
        local: Map<String, String>, remote: Map<String, String>?, base: Map<String, String>?,
        usable: (name: String, value: String) -> Boolean,
    ): Map<String, String> {
        if (remote == null) return local.toSortedMap()
        val out = sortedMapOf<String, String>()
        for (k in remote.keys + local.keys) {
            val l = local[k]
            val r = remote[k]
            out[k] = when {
                l == null -> r ?: continue
                r == null -> l
                !usable(k, r) -> r
                (base?.get(k) ?: l) == l -> r
                else -> l
            }
        }
        return out
    }

    /** At most this many dictations are in the file: the newest ones. */
    const val HISTORY_MAX = 1000

    class HistoryResult(
        /** What the file should hold, newest first. */
        val file: List<SyncEntry>,
        /** Entries to add here, or to put in place of the one with the same id. */
        val put: List<SyncEntry>,
        /** Entries deleted on another device. */
        val delete: Set<Long>,
    )

    /**
     * Dictation history, matched by id like notes: one a side had at the last sync ([base] holds their ids
     * and hashes) and no longer has was deleted there, and a version changed since the last sync wins. The
     * file keeps only the newest [HISTORY_MAX]; one that drops off the end was not deleted, so it stays
     * wherever it is, and an older one here is simply not shared.
     */
    fun history(local: List<SyncEntry>, remote: List<SyncEntry>?, base: Map<Long, Int>): HistoryResult {
        val mine = LinkedHashMap<Long, SyncEntry>().also { m -> local.forEach { m.putIfAbsent(it.id, it) } }
        val theirs = LinkedHashMap<Long, SyncEntry>().also { m -> remote.orEmpty().forEach { m.putIfAbsent(it.id, it) } }
        val order = compareByDescending<SyncEntry> { it.time }.thenByDescending { it.id }
        // A full file's oldest entry: anything older fell off the end rather than being deleted.
        val edge = theirs.values.takeIf { it.size >= HISTORY_MAX }?.minWithOrNull(order.reversed())
        fun fellOff(e: SyncEntry) = edge != null && order.compare(e, edge) > 0
        val kept = ArrayList<SyncEntry>()
        val put = ArrayList<SyncEntry>()
        val delete = HashSet<Long>()
        for (id in LinkedHashSet(theirs.keys + mine.keys)) {
            val here = mine[id]
            val there = theirs[id]
            val was = base[id]
            when {
                here != null && there != null -> {
                    val pick = if (here.hash == there.hash || was == here.hash) there else here
                    kept.add(pick)
                    if (pick.hash != here.hash) put.add(pick)
                }
                here != null -> when {
                    was == null -> kept.add(here)
                    !fellOff(here) -> delete.add(id) // deleted elsewhere
                }
                there != null -> if (was == null) { kept.add(there); put.add(there) } // else: deleted here
            }
        }
        val file = kept.sortedWith(order).take(HISTORY_MAX)
        return HistoryResult(file, put, delete)
    }

    /** FNV-1a, so a hash is the same in every run (String.hashCode is too, but C#'s isn't). */
    fun fnv(s: String): Int {
        var h = -0x7ee3623b // 2166136261
        for (c in s) {
            h = h xor c.code
            h *= 16777619
        }
        return h
    }

    /**
     * The usage the file should have: every other device's entry exactly as the file has it, and this
     * device's ([mine], under [id]) in place of its own. There is nothing to merge, because each entry has
     * one writer. An entry whose counts haven't changed is kept as it was, so it doesn't make a write.
     */
    fun usage(remote: Map<String, DeviceUsage>?, id: String, mine: DeviceUsage): Map<String, DeviceUsage> {
        val out = LinkedHashMap(remote.orEmpty())
        val had = out[id]
        when {
            had != null && had.name == mine.name && had.months == mine.months -> {}
            had == null && mine.months.isEmpty() -> {} // nothing counted here yet: no entry to add
            else -> out[id] = mine
        }
        return out
    }

    /** Whether the file already says exactly what [state] says, so there is nothing to write. */
    fun sameContent(state: SyncState, r: SyncRemote): Boolean =
        state.words == r.words && state.snippets == r.snippets && state.styles == r.styles &&
            state.instructions == r.instructions && state.notes == r.notes.orEmpty() &&
            (state.settings.isNullOrEmpty() && r.settings == null || state.settings == r.settings)
}
