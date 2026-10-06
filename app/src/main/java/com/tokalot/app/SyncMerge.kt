package com.tokalot.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * The sync file's format and the three-way merge, with no Android types so both are unit-tested
 * on a plain JVM. The Windows and Linux apps follow the same rules against the same file;
 * change nothing here without changing them too.
 */

/** The settings that sync: dictionary, snippets, explicitly chosen styles and the custom instructions. */
data class SyncState(
    val words: List<String> = emptyList(),
    val snippets: List<Snippet> = emptyList(),
    /** Category id -> style id, only for categories the user set themselves. */
    val styles: Map<String, String> = emptyMap(),
    val instructions: String = "",
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
        return SyncRemote(
            words, snippets, styles, o.opt("instructions") as? String, keys,
            (o.opt("updated") as? Number)?.toLong() ?: 0, o.opt("by") as? String ?: "",
        )
    }

    /** The file's text: pretty-printed JSON. [keys] null leaves the "keys" object out. */
    fun encode(state: SyncState, keys: Map<String, String>?, updated: Long, by: String): String {
        val o = JSONObject()
        o.put("app", APP)
        o.put("format", FORMAT)
        o.put("updated", updated)
        o.put("by", by)
        o.put("words", JSONArray().also { arr -> state.words.forEach { arr.put(it) } })
        o.put("snippets", JSONArray().also { arr ->
            state.snippets.forEach { arr.put(JSONObject().put("trigger", it.trigger).put("text", it.text)) }
        })
        o.put("styles", JSONObject().also { s -> state.styles.forEach { (c, v) -> s.put(c, v) } })
        o.put("instructions", state.instructions)
        if (!keys.isNullOrEmpty()) o.put("keys", JSONObject().also { k -> keys.forEach { (name, v) -> k.put(name, v) } })
        return o.toString(2)
    }

    /** The app's private copy of the base: the same shape as the file, never with keys. */
    fun encodeBase(state: SyncState): String = encode(state, null, 0, "")

    fun parseBase(text: String?, defaultInstructions: String): SyncState {
        val r = (try { parse(text) } catch (_: SyncFormatException) { null }) ?: return firstBase(defaultInstructions)
        return SyncState(r.words ?: emptyList(), r.snippets ?: emptyList(), r.styles ?: emptyMap(), r.instructions ?: defaultInstructions)
    }
}

object SyncMerge {
    /** How words and snippet triggers are compared: ignoring case and surrounding spaces. */
    fun id(s: String) = s.trim().lowercase()

    /**
     * Three-way merge. [local] is this device now, [remote] the file (null = no remote state yet),
     * [base] what this device had at the end of its last successful sync.
     */
    fun merge(
        local: SyncState, remote: SyncRemote?, base: SyncState,
        localKeys: Map<String, String>, includeKeys: Boolean, knownKeys: Set<String> = emptySet(),
    ): SyncResult {
        val state = SyncState(
            words(local.words, remote?.words, base.words),
            snippets(local.snippets, remote?.snippets, base.snippets),
            styles(local.styles, remote?.styles, base.styles),
            if (local.instructions == base.instructions && remote?.instructions != null) remote.instructions else local.instructions,
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

    /** Whether the file already says exactly what [state] says, so there is nothing to write. */
    fun sameContent(state: SyncState, r: SyncRemote): Boolean =
        state.words == r.words && state.snippets == r.snippets && state.styles == r.styles &&
            state.instructions == r.instructions
}
