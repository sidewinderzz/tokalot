package com.tokalot.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Voice notes (beta): one note per dictation started with "New voice note" (a Quick Settings tile or the
 * accessibility shortcut). Stored as one JSON file in app-private storage, newest first, on this phone only.
 */
object Notes {
    class Note(val id: Long, val time: Long, val text: String)

    const val FILE = "notes.json"
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Set by the app screen so the Notes page refreshes when a note lands. */
    @Volatile var onChange: (() -> Unit)? = null

    private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, FILE)

    /** Reads the notes file's JSON. Throws on anything malformed (used to vet a backup before restoring it). */
    fun parse(json: String): List<Note> {
        val arr = JSONArray(json)
        return List(arr.length()) {
            val o = arr.getJSONObject(it)
            Note(o.getLong("id"), o.getLong("time"), o.getString("text"))
        }
    }

    private fun toJson(list: List<Note>): String {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("time", it.time).put("text", it.text)) }
        return arr.toString()
    }

    @Synchronized
    fun all(ctx: Context): List<Note> = runCatching {
        val f = file(ctx)
        if (f.exists()) parse(f.readText()) else emptyList()
    }.getOrDefault(emptyList())

    @Synchronized
    fun add(ctx: Context, text: String) {
        val now = System.currentTimeMillis()
        save(ctx, listOf(Note(now, now, text)) + all(ctx))
    }

    @Synchronized
    fun delete(ctx: Context, id: Long) = save(ctx, all(ctx).filter { it.id != id })

    /** Written to a side file first and then swapped in, so a crash mid-write can't lose the notes. */
    private fun save(ctx: Context, list: List<Note>) {
        val f = file(ctx)
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeText(toJson(list))
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        main.post { onChange?.invoke() }
    }
}
