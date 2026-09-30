package com.tokalot.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * One-file backup: a .zip holding settings, dictionary, snippets, styles, usage, history,
 * and optionally recordings and API keys. Keys are left out unless explicitly included,
 * so a backup sitting in Drive or Downloads doesn't leak them.
 */
object Backup {
    private val PREF_FILES = listOf("settings", "usage", "overlay")
    private const val KEY_PREFIX = "key_"

    class Summary(val entries: Int, val recordings: Int, val keys: Boolean)

    fun write(ctx: Context, out: OutputStream, includeAudio: Boolean, includeKeys: Boolean): Summary {
        val app = ctx.applicationContext
        var recordings = 0
        ZipOutputStream(out.buffered()).use { zip ->
            val prefs = JSONObject()
            for (name in PREF_FILES) {
                val o = JSONObject()
                app.getSharedPreferences(name, Context.MODE_PRIVATE).all.forEach { (k, v) ->
                    if (!includeKeys && name == "settings" && k.startsWith(KEY_PREFIX)) return@forEach
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

            val history = File(app.filesDir, "history.json")
            if (history.exists()) {
                zip.putNextEntry(ZipEntry("history.json"))
                history.inputStream().use { it.copyTo(zip) }
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

    /** Replaces current data with the backup's. API keys already on the phone are kept if the backup has none. */
    fun restore(ctx: Context, input: InputStream): Summary {
        val app = ctx.applicationContext
        var manifest: JSONObject? = null
        var historyBytes: ByteArray? = null
        val audio = HashMap<String, ByteArray>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val name = e.name
                when {
                    name == "backup.json" -> manifest = JSONObject(String(zip.readBytes()))
                    name == "history.json" -> historyBytes = zip.readBytes()
                    // Only plain file names under audio/ (no paths) are accepted.
                    name.startsWith("audio/") && name.removePrefix("audio/").matches(Regex("""\d+\.m4a""")) ->
                        audio[name.removePrefix("audio/")] = zip.readBytes()
                }
            }
        }
        val m = manifest ?: throw IllegalArgumentException("That file isn't a Tokalot backup")
        val hasKeys = m.optBoolean("includesKeys")
        val prefs = m.getJSONObject("prefs")
        for (name in PREF_FILES) {
            val o = prefs.optJSONObject(name) ?: continue
            val sp = app.getSharedPreferences(name, Context.MODE_PRIVATE)
            val ed = sp.edit()
            // Clear everything except existing keys when the backup didn't include keys.
            sp.all.keys.forEach { k ->
                if (!(name == "settings" && k.startsWith(KEY_PREFIX) && !hasKeys)) ed.remove(k)
            }
            o.keys().forEach { k ->
                val t = o.getJSONObject(k)
                when (t.getString("t")) {
                    "b" -> ed.putBoolean(k, t.getBoolean("v"))
                    "i" -> ed.putInt(k, t.getInt("v"))
                    "l" -> ed.putLong(k, t.getLong("v"))
                    "f" -> ed.putFloat(k, t.getDouble("v").toFloat())
                    "s" -> ed.putString(k, t.getString("v"))
                }
            }
            ed.commit()
        }
        historyBytes?.let { File(app.filesDir, "history.json").writeBytes(it) }
        History.reload()
        audio.forEach { (name, bytes) -> File(AudioStore.dir(app), name).writeBytes(bytes) }
        return Summary(History.all(app).size, audio.size, hasKeys)
    }
}
