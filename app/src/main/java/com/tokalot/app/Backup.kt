package com.tokalot.app

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * One-file backup: a .zip holding settings, dictionary, snippets, styles, usage, history, notes,
 * and optionally recordings and API keys. Keys are left out unless explicitly included,
 * so a backup sitting in Drive or Downloads doesn't leak them.
 * Both directions block; call them off the main thread.
 */
object Backup {
    // The "sync" preferences (sync file location, base and status) are deliberately not here:
    // they belong to this phone, so they are neither exported nor replaced by a restore.
    private val PREF_FILES = listOf("settings", "usage", "overlay")
    private const val KEY_PREFIX = "key_"
    private const val MAX_JSON_BYTES = 64 * 1024 * 1024 // far above any real history; stops a hostile zip
    private val AUDIO_NAME = Regex("""\d+\.m4a""")

    class Summary(val entries: Int, val recordings: Int, val keys: Boolean)

    /** A backup.json that passed every check: the values are already the types they'll be stored as. */
    class Manifest(val hasKeys: Boolean, val prefs: Map<String, Map<String, Any>>)

    fun write(ctx: Context, out: OutputStream, includeAudio: Boolean, includeKeys: Boolean): Summary {
        val app = ctx.applicationContext
        var recordings = 0
        ZipOutputStream(out.buffered()).use { zip ->
            val prefs = JSONObject()
            for (name in PREF_FILES) {
                val o = JSONObject()
                app.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                    if (name == "settings" && k.startsWith(KEY_PREFIX)) {
                        // Keys are encrypted with a key that never leaves this phone, so the
                        // backup gets them decrypted or not at all.
                        val plain = if (includeKeys) Prefs(app).key(k.removePrefix(KEY_PREFIX)) else ""
                        if (plain.isNotEmpty()) o.put(k, JSONObject().put("t", "s").put("v", plain))
                        return@forEach
                    }
                    val typed = when (v) {
                        is Boolean -> JSONObject().put("t", "b").put("v", v)
                        is Int -> JSONObject().put("t", "i").put("v", v)
                        is Long -> JSONObject().put("t", "l").put("v", v)
                        is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                        is String -> JSONObject().put("t", "s").put("v", v)
                        else -> null
                    }
                    if (typed != null) o.put(k, typed)
                }
                prefs.put(name, o)
            }
            val manifest = JSONObject()
                .put("format", 1)
                .put("created", System.currentTimeMillis())
                .put("appVersion", Updater.currentVersion(app))
                .put("includesKeys", includeKeys)
                .put("prefs", prefs)
            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write(manifest.toString().toByteArray())
            zip.closeEntry()

            History.flush() // the latest changes are written in the background
            val history = File(app.filesDir, "history.json")
            if (history.exists()) {
                zip.putNextEntry(ZipEntry("history.json"))
                history.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            val notes = File(app.filesDir, Notes.FILE)
            if (notes.exists()) {
                zip.putNextEntry(ZipEntry(Notes.FILE))
                notes.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            if (includeAudio) {
                AudioStore.dir(app).listFiles { f -> f.name.endsWith(".m4a") }?.forEach { f ->
                    zip.putNextEntry(ZipEntry("audio/${f.name}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    recordings++
                }
            }
        }
        return Summary(History.all(app).size, recordings, includeKeys)
    }

    /**
     * Checks backup.json from top to bottom without touching anything on the phone.
     * Throws IllegalArgumentException (with a message fit for a toast) on anything unexpected.
     */
    fun parseManifest(json: String): Manifest {
        val bad = IllegalArgumentException("That file isn't a Tokalot backup")
        val m = try { JSONObject(json) } catch (_: Exception) { throw bad }
        val format = m.opt("format") as? Int ?: throw bad
        if (format > 1) throw IllegalArgumentException("That backup was made by a newer version of Tokalot. Update the app first.")
        val prefs = m.optJSONObject("prefs") ?: throw bad
        val damaged = IllegalArgumentException("That backup is damaged, so nothing was changed")
        val out = LinkedHashMap<String, Map<String, Any>>()
        for (name in PREF_FILES) {
            val o = prefs.optJSONObject(name) ?: continue
            val values = LinkedHashMap<String, Any>()
            for (k in o.keys()) {
                val t = o.optJSONObject(k) ?: throw damaged
                val v = t.opt("v")
                values[k] = when (t.optString("t")) {
                    "b" -> v as? Boolean
                    "i" -> (v as? Number)?.toInt()
                    "l" -> (v as? Number)?.toLong()
                    "f" -> (v as? Number)?.toFloat()
                    "s" -> v as? String
                    else -> null
                } ?: throw damaged
            }
            out[name] = values
        }
        return Manifest(m.optBoolean("includesKeys"), out)
    }

    /**
     * Replaces current data with the backup's. API keys already on the phone are kept if the backup has none.
     * The whole file is read and checked first; if anything is wrong it throws and nothing on the phone has changed.
     */
    fun restore(ctx: Context, input: InputStream): Summary {
        val app = ctx.applicationContext
        // Recordings are streamed to a holding folder (never into memory) and moved in at the end.
        val holding = File(app.filesDir, "restore.tmp").apply { deleteRecursively(); mkdirs() }
        try {
            var manifestJson: String? = null
            var historyJson: String? = null
            var notesJson: String? = null
            val audio = ArrayList<String>()
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name
                    when {
                        name == "backup.json" -> manifestJson = readText(zip)
                        name == "history.json" -> historyJson = readText(zip)
                        name == Notes.FILE -> notesJson = readText(zip)
                        // Only plain file names under audio/ (no paths) are accepted.
                        name.startsWith("audio/") && name.removePrefix("audio/").matches(AUDIO_NAME) -> {
                            val file = name.removePrefix("audio/")
                            File(holding, file).outputStream().use { zip.copyTo(it) }
                            if (file !in audio) audio.add(file)
                        }
                    }
                }
            }
            val m = parseManifest(manifestJson ?: throw IllegalArgumentException("That file isn't a Tokalot backup"))
            val history = historyJson
            if (history != null) {
                try { History.parse(history) } catch (_: Exception) {
                    throw IllegalArgumentException("That backup's history is damaged, so nothing was changed")
                }
            }
            val notes = notesJson
            if (notes != null) {
                try { Notes.parse(notes) } catch (_: Exception) {
                    throw IllegalArgumentException("That backup's notes are damaged, so nothing was changed")
                }
            }

            // Everything checked out. Only now does anything on the phone change.
            for ((name, values) in m.prefs) {
                val sp = app.getSharedPreferences(name, Context.MODE_PRIVATE)
                val ed = sp.edit()
                // Clear everything except existing keys when the backup didn't include keys.
                sp.all.keys.forEach { k ->
                    if (!(name == "settings" && k.startsWith(KEY_PREFIX) && !m.hasKeys)) ed.remove(k)
                }
                values.forEach { (k, v) ->
                    when (v) {
                        is Boolean -> ed.putBoolean(k, v)
                        is Int -> ed.putInt(k, v)
                        is Long -> ed.putLong(k, v)
                        is Float -> ed.putFloat(k, v)
                        is String -> ed.putString(k, v)
                    }
                }
                ed.commit()
            }
            // Keys arrive in plain text; reading each one encrypts it for this phone.
            if (m.hasKeys) Prefs(app).let { p -> Services.all.forEach { (id, _) -> p.key(id) } }
            // If this phone syncs, the next sync starts over as a first one: the restored settings
            // are combined with the sync file's, instead of counting as deletions for every device.
            Prefs(app).let { p -> if (p.syncUri != null) p.syncBase = null }
            if (history != null) History.restore(app, history) else History.reload()
            if (notes != null) {
                val target = File(app.filesDir, Notes.FILE)
                val tmp = File(app.filesDir, "notes.restore")
                tmp.writeText(notes)
                if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
            }
            runCatching { StartTile.setNoteTile(app, Prefs(app).notesBeta) } // the restored setting decides the tile
            var recordings = 0
            for (file in audio) {
                val from = File(holding, file)
                val to = File(AudioStore.dir(app), file)
                to.delete()
                if (from.renameTo(to) || runCatching { from.copyTo(to, overwrite = true) }.isSuccess) recordings++
            }
            return Summary(History.all(app).size, recordings, m.hasKeys)
        } finally {
            holding.deleteRecursively()
        }
    }

    /** Reads the current zip entry as text, refusing anything absurdly large. */
    private fun readText(zip: ZipInputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_JSON_BYTES) throw IllegalArgumentException("That file isn't a Tokalot backup")
        }
        return String(out.toByteArray())
    }
}
