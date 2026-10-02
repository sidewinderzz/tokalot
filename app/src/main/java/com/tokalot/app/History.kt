package com.tokalot.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Entry(
    val id: Long,
    val time: Long,
    val text: String,       // what was typed
    val raw: String,        // straight from speech recognition
    val durationMs: Long,
    val cleaned: Boolean,   // true if an AI model rewrote it
    val app: String = "",   // package name of the app it was typed into
    val status: String = "", // "" = transcribed; History.FAILED / CANCELLED = audio kept, waiting for a retry
    val own: Boolean = false, // the AI's wording was swapped back for the user's own ("My wording")
) {
    /** No transcript yet: the recording is kept (whatever the retention setting) until it's retried or deleted. */
    val pending: Boolean get() = status.isNotEmpty()
}

/** Every dictation, stored as one JSON file in app-private storage. Newest first. */
object History {
    private const val MAX = 5000
    const val FAILED = "failed"
    const val CANCELLED = "cancelled"

    private var cache: MutableList<Entry>? = null
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Set by the app screen so the list refreshes live when a dictation lands. */
    @Volatile var onChange: (() -> Unit)? = null

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "history.json")

    /** Reads the history file's JSON. Throws on anything malformed (used to vet a backup before restoring it). */
    fun parse(json: String): List<Entry> {
        val arr = JSONArray(json)
        val list = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val text = o.getString("text")
            val raw = o.optString("raw")
            // Failures saved by older versions had no status; they're retryable too.
            val status = o.optString("st").ifEmpty {
                if (raw.isEmpty() && text.startsWith("Transcription failed: ")) FAILED else ""
            }
            list.add(Entry(o.getLong("id"), o.getLong("time"), text, raw, o.optLong("dur"), o.optBoolean("ai"), o.optString("app"), status, o.optBoolean("own")))
        }
        return list
    }

    fun toJson(list: List<Entry>): String {
        val arr = JSONArray()
        list.forEach {
            val o = JSONObject().put("id", it.id).put("time", it.time).put("text", it.text)
                .put("raw", it.raw).put("dur", it.durationMs).put("ai", it.cleaned).put("app", it.app)
            if (it.status.isNotEmpty()) o.put("st", it.status)
            if (it.own) o.put("own", true)
            arr.put(o)
        }
        return arr.toString()
    }

    @Synchronized
    private fun load(ctx: Context): MutableList<Entry> {
        cache?.let { return it }
        val list = ArrayList<Entry>()
        val f = file(ctx)
        if (f.exists()) {
            try {
                list.addAll(parse(f.readText()))
            } catch (_: Exception) {
                // Corrupt file: keep a copy for recovery instead of silently discarding it.
                runCatching { f.copyTo(File(f.parentFile, "history.corrupt.json"), overwrite = true) }
            }
        }
        cache = list
        return list
    }

    @Synchronized
    private fun save(ctx: Context) {
        val f = file(ctx)
        val tmp = File(f.parentFile, "history.tmp")
        tmp.writeText(toJson(cache ?: emptyList()))
        tmp.renameTo(f)
        notifyChanged()
    }

    /** Drops the in-memory copy so the next read comes from disk (after a restore). */
    @Synchronized
    fun reload() { cache = null; notifyChanged() }

    /** Tells the open screen to redraw (e.g. once a recording finishes saving). */
    fun notifyChanged() { onChange?.let { cb -> main.post { cb() } } }

    @Synchronized
    fun all(ctx: Context): List<Entry> = ArrayList(load(ctx))

    /** Recordings that must survive pruning because they still have no transcript. */
    @Synchronized
    fun pendingIds(ctx: Context): Set<Long> = load(ctx).filter { it.pending }.mapTo(HashSet()) { it.id }

    @Synchronized
    fun add(ctx: Context, e: Entry) {
        val list = load(ctx)
        list.add(0, e)
        while (list.size > MAX) list.removeAt(list.size - 1)
        save(ctx)
    }

    /** Replaces the entry with the same id in place (a retry), or adds it as the newest. */
    @Synchronized
    fun put(ctx: Context, e: Entry) {
        val list = load(ctx)
        val i = list.indexOfFirst { it.id == e.id }
        if (i < 0) return add(ctx, e)
        list[i] = e
        save(ctx)
    }

    /**
     * "My wording": the entry now holds the user's own words instead of the AI's, and no longer
     * counts as AI-cleaned. The raw transcript stays, so "Original" still shows it.
     */
    @Synchronized
    fun useOwnWording(ctx: Context, id: Long, plain: String) {
        val list = load(ctx)
        val i = list.indexOfFirst { it.id == id }
        if (i < 0 || list[i].pending) return
        list[i] = ownWording(list[i], plain)
        save(ctx)
    }

    fun ownWording(e: Entry, plain: String) = e.copy(text = plain, cleaned = false, own = true)

    @Synchronized
    fun delete(ctx: Context, id: Long) {
        load(ctx).removeAll { it.id == id }
        save(ctx)
    }
}
