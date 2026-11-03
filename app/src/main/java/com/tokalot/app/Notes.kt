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
    /**
     * [tags]: the user's own labels, as [cleanTags] leaves them (sorted, no repeats). [plan]: its reminder and checklist
     * (beta), null for none. [home]: it sits on Home with the dictations instead of on the Notes page (something shared
     * into Tokalot just to read or play back), until Move to Notes files it.
     */
    class Note(val id: Long, val time: Long, val text: String, val tags: List<String> = emptyList(), val plan: NotePlan? = null,
               val home: Boolean = false) {
        fun copy(text: String = this.text, tags: List<String> = this.tags, plan: NotePlan? = this.plan, home: Boolean = this.home) =
            Note(id, time, text, tags, plan, home)
    }

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
            val t = o.optJSONArray("tags")
            Note(o.getLong("id"), o.getLong("time"), o.getString("text"),
                if (t == null) emptyList() else cleanTags(List(t.length()) { i -> t.optString(i) }),
                NotePlan.parse(o.optJSONObject("plan")), o.optBoolean("home", false))
        }
    }

    private fun toJson(list: List<Note>): String {
        val arr = JSONArray()
        list.forEach {
            val o = JSONObject().put("id", it.id).put("time", it.time).put("text", it.text)
            if (it.tags.isNotEmpty()) o.put("tags", JSONArray(it.tags))
            if (it.plan != null && !it.plan.isEmpty) o.put("plan", it.plan.toJson())
            if (it.home) o.put("home", true)
            arr.put(o)
        }
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

    /**
     * Saves a new note and returns its id. A shared voice note passes the id its audio is saved under, and [home] so it
     * shows on Home rather than on the Notes page. [time] defaults to the id (a dictation saved to Notes keeps its own).
     */
    @Synchronized
    fun add(ctx: Context, text: String, id: Long = System.currentTimeMillis(), plan: NotePlan? = null, home: Boolean = false, time: Long = id): Long {
        save(ctx, listOf(Note(id, time, text, plan = plan?.takeIf { !it.isEmpty }, home = home)) + forChange(ctx))
        Sync.changed(ctx)
        return id
    }

    @Synchronized
    fun delete(ctx: Context, id: Long) {
        save(ctx, forChange(ctx).filter { it.id != id })
        NoteImport.deleteMedia(ctx, id)
        Sync.changed(ctx)
    }

    /** Gives note [id] these tags (cleaned up first). Syncs. */
    @Synchronized
    fun setTags(ctx: Context, id: Long, tags: List<String>) {
        val clean = cleanTags(tags)
        save(ctx, forChange(ctx).map { if (it.id == id) it.copy(tags = clean) else it })
        Sync.changed(ctx)
    }

    /** Moves note [id] to Home ([home] true) or files it on the Notes page. Syncs. */
    @Synchronized
    fun setHome(ctx: Context, id: Long, home: Boolean) {
        val all = forChange(ctx)
        if (all.none { it.id == id && it.home != home }) return
        save(ctx, all.map { if (it.id == id) it.copy(home = home) else it })
        Sync.changed(ctx)
    }

    /** Whether there's a note with this id (a dictation saved to Notes keeps the dictation's id). */
    fun has(ctx: Context, id: Long) = all(ctx).any { it.id == id }

    /**
     * Changes note [id]'s reminder and checklist with [change] (handed null when it has none; returning null or an empty
     * plan removes it). [text], when given, replaces the note's text too (a list made by the AI gets its heading).
     * Returns the plan saved, or null when the note is gone. Syncs, and sets the phone's alarms again.
     */
    @Synchronized
    fun updatePlan(ctx: Context, id: Long, text: String? = null, change: (NotePlan?) -> NotePlan?): NotePlan? {
        val all = forChange(ctx)
        val note = all.firstOrNull { it.id == id } ?: return null
        val plan = change(note.plan)?.takeIf { !it.isEmpty }
        if (plan == note.plan && (text == null || text == note.text)) return plan
        save(ctx, all.map { if (it.id == id) it.copy(text = text ?: it.text, plan = plan) else it })
        Sync.changed(ctx)
        return plan
    }

    /** Every tag used on any note, A to Z. */
    fun allTags(notes: List<Note>): List<String> = cleanTags(notes.flatMap { it.tags })

    /**
     * Tags as they're kept: spaces tidied, commas dropped (the sync file and the desktop keep them in one line), at most
     * 30 characters, no repeats whatever the capitals, sorted A to Z. Same rules as Notes.CleanTags on the desktop.
     */
    fun cleanTags(tags: List<String>): List<String> = SyncMerge.cleanTags(tags)

    @Synchronized
    private fun texts(ctx: Context): List<SyncNote> {
        val f = file(ctx)
        return if (f.exists()) parse(f.readText()).map { SyncNote(it.id, it.time, it.text, tags = it.tags, plan = it.plan?.line() ?: "", home = it.home) } else emptyList()
    }

    /**
     * The notes as the sync file holds them. Throws if the file is there but can't be read, so a sync never takes that for
     * "no notes". Titles live with the media and are read outside this lock: NoteImport takes its own, and asks for the notes while holding it.
     */
    fun forSync(ctx: Context): List<SyncNote> = texts(ctx).map { it.copy(title = NoteImport.syncTitle(ctx, it.id)) }

    /** Stores a sync's result, unless a note was added or deleted here since [before] was read (the caller then syncs again). */
    fun applySynced(ctx: Context, before: List<SyncNote>, after: List<SyncNote>): Boolean {
        if (!store(ctx, before, after)) return false
        // A title that came from another device, unless it changed here meanwhile.
        val had = before.associate { it.id to it.title }
        for (n in after) {
            val was = had[n.id]
            if (n.title == null || n.title == was || NoteImport.syncTitle(ctx, n.id) != was) continue
            runCatching { NoteImport.setTitle(ctx, n.id, n.title, sync = false) }
        }
        return true
    }

    @Synchronized
    private fun store(ctx: Context, before: List<SyncNote>, after: List<SyncNote>): Boolean {
        if (texts(ctx) != before.map { it.copy(title = null) }) return false
        save(ctx, after.map { Note(it.id, it.time, it.text, it.tags.orEmpty(), NotePlan.parse(it.plan), it.home ?: false) })
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
        // Every change to the notes can change what rings next.
        runCatching { Reminders.schedule(ctx, list) }
        main.post { onChange?.invoke() }
    }
}
