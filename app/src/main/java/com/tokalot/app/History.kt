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
)

/** Every dictation, stored as one JSON file in app-private storage. Newest first. */
object History {
    private const val MAX = 5000
    private var cache: MutableList<Entry>? = null
    private val main = Handler(Looper.getMainLooper())

    /** Set by the app screen so the list refreshes live when a dictation lands. */
    @Volatile var onChange: (() -> Unit)? = null

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "history.json")

    @Synchronized
    private fun load(ctx: Context): MutableList<Entry> {
        cache?.let { return it }
        val list = ArrayList<Entry>()
        val f = file(ctx)
        if (f.exists()) {
            try {
                val arr = JSONArray(f.readText())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        Entry(
                            o.getLong("id"), o.getLong("time"), o.getString("text"),
                            o.optString("raw"), o.optLong("dur"), o.optBoolean("ai"), o.optString("app")
                        )
                    )
                }
            } catch (_: Exception) {
                // Corrupt file: keep a copy for recovery instead of silently discarding it.
                f.copyTo(File(f.parentFile, "history.corrupt.json"), overwrite = true)
            }
        }
        cache = list
        return list
    }

    @Synchronized
    private fun save(ctx: Context) {
        val arr = JSONArray()
        cache?.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("time", it.time).put("text", it.text)
                    .put("raw", it.raw).put("dur", it.durationMs).put("ai", it.cleaned).put("app", it.app)
            )
        }
        val f = file(ctx)
        val tmp = File(f.parentFile, "history.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(f)
        onChange?.let { cb -> main.post { cb() } }
    }

    /** Drops the in-memory copy so the next read comes from disk (after a restore). */
    @Synchronized
    fun reload() { cache = null; notifyChanged() }

    /** Tells the open screen to redraw (e.g. once a recording finishes saving). */
    fun notifyChanged() { onChange?.let { cb -> main.post { cb() } } }

    @Synchronized
    fun all(ctx: Context): List<Entry> = ArrayList(load(ctx))

    @Synchronized
    fun add(ctx: Context, e: Entry) {
        val list = load(ctx)
        list.add(0, e)
        while (list.size > MAX) list.removeAt(list.size - 1)
        save(ctx)
    }

    @Synchronized
    fun delete(ctx: Context, id: Long) {
        load(ctx).removeAll { it.id == id }
        save(ctx)
    }
}
