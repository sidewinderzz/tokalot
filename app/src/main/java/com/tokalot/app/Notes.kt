package com.tokalot.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Voice notes (beta): one note per dictation started with "New voice note" (a Quick Settings tile or the
 * accessibility shortcut). Stored as one JSON file in app-private storage, newest first, and shared with
 * the other devices through the sync file when one is set up.
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

    /**
     * The notes, for a change that is about to be saved. A file that is there but isn't valid notes is first moved
     * aside (notes.corrupt-<time>.json, kept for recovery) so saving can never write over the notes in it.
     */
    private fun forChange(ctx: Context): List<Note> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val json = f.readText() // a read error throws: nothing is changed or saved
        return try {
            parse(json)
        } catch (e: Exception) {
            val aside = File(f.parentFile, "notes.corrupt-${System.currentTimeMillis()}.json")
            // Not even moved aside: refuse the change rather than overwrite the notes.
            if (!f.renameTo(aside)) throw java.io.IOException("Couldn't read the notes file", e)
            emptyList()
        }
    }

    /** Saves a new note and returns its id. */
    @Synchronized
    fun add(ctx: Context, text: String): Long {
        val now = System.currentTimeMillis()
        save(ctx, listOf(Note(now, now, text)) + forChange(ctx))
        Sync.changed(ctx)
        return now
    }

    @Synchronized
    fun delete(ctx: Context, id: Long) {
        save(ctx, forChange(ctx).filter { it.id != id })
        Sync.changed(ctx)
    }

    /** The notes as the sync file holds them. Throws if the file is there but can't be read, so a sync never takes that for "no notes". */
    @Synchronized
    fun forSync(ctx: Context): List<SyncNote> {
        val f = file(ctx)
        return if (f.exists()) parse(f.readText()).map { SyncNote(it.id, it.time, it.text) } else emptyList()
    }

    /** Stores a sync's result, unless a note was added or deleted here since [before] was read (the caller then syncs again). */
    @Synchronized
    fun applySynced(ctx: Context, before: List<SyncNote>, after: List<SyncNote>): Boolean {
        if (forSync(ctx) != before) return false
        save(ctx, after.map { Note(it.id, it.time, it.text) })
        return true
    }

    /**
     * The beta's feedback form on GitHub (.github/ISSUE_TEMPLATE/voice-notes-feedback.yml), with [vote] in
     * the title and the app and Android version filled in. Nothing else about the phone or the notes is sent.
     */
    fun feedbackUrl(ctx: Context, vote: String): String {
        fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
        val app = "Tokalot ${Updater.currentVersion(ctx)} on Android ${android.os.Build.VERSION.RELEASE}"
        return "https://github.com/${Updater.REPO}/issues/new?template=voice-notes-feedback.yml" +
            "&title=${enc("[Voice notes] $vote")}&app=${enc(app)}"
    }

    /** Written to a side file first and then swapped in, so a crash mid-write can't lose the notes. */
    private fun save(ctx: Context, list: List<Note>) {
        val f = file(ctx)
        val tmp = File(f.parentFile, "$FILE.tmp")
        tmp.writeText(toJson(list))
        if (!tmp.renameTo(f)) { f.writeText(tmp.readText()); tmp.delete() }
        main.post { onChange?.invoke() }
    }
}
