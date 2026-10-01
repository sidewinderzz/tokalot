package com.tokalot.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Optional settings sync without an account or a server: one small JSON file the user keeps
 * in a folder they already sync (Drive, OneDrive, Dropbox, Syncthing…). Every Tokalot pointed
 * at that file reads it, merges in its own changes and writes it back (rules in [SyncMerge]).
 * The file is reached through Android's document picker, so no storage permission is needed.
 * Nothing here runs on the main thread, and a failure only ever becomes a line in Settings.
 */
object Sync {
    const val UNREACHABLE = "Sync file can't be reached. Choose it again."
    private const val BY = "Android"
    private const val DEBOUNCE_MS = 2500L
    private const val MAX_BYTES = 8 * 1024 * 1024 // a real file is a few KB; refuses something that clearly isn't ours
    private const val RW = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    private val main = Handler(Looper.getMainLooper())
    private val busy = AtomicBoolean(false)
    @Volatile private var again = false
    @Volatile private var appContext: Context? = null
    private val debounced = Runnable { appContext?.let { request(it) } }

    /** Told on the main thread when a sync ends; true if it changed a setting on this device. */
    @Volatile var onDone: ((changedLocal: Boolean) -> Unit)? = null

    /** True while a sync is running, for the status line. */
    val running get() = busy.get()

    /** The file's permission is gone (or the file is): stop until the user chooses it again. */
    private class Unreachable : Exception(UNREACHABLE)

    /** A synced setting changed in the app: sync a moment later, once the user has stopped typing. */
    fun changed(ctx: Context) {
        appContext = ctx.applicationContext
        main.removeCallbacks(debounced)
        main.postDelayed(debounced, DEBOUNCE_MS)
    }

    /** Syncs now in the background if a file is set up. Safe to call from anywhere, as often as you like. */
    fun request(ctx: Context) {
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        if (prefs.syncUri == null || prefs.syncBroken) return
        if (!busy.compareAndSet(false, true)) { again = true; return }
        Thread {
            var changed = false
            try {
                do {
                    again = false
                    changed = attempt(app) || changed
                } while (again)
            } finally {
                busy.set(false)
            }
            main.post { onDone?.invoke(changed) }
        }.start()
    }

    /** One sync against the stored file; what went wrong ends up in Prefs instead of being thrown. */
    private fun attempt(app: Context): Boolean {
        val prefs = Prefs(app)
        val uri = prefs.syncUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
        if (prefs.syncBroken) return false
        val changed = BooleanArray(1)
        try {
            sync(app, uri, changed)
            prefs.syncError = null
        } catch (_: Unreachable) {
            prefs.syncBroken = true
            prefs.syncError = UNREACHABLE
        } catch (e: SyncFormatException) {
            prefs.syncError = e.message
        } catch (e: Exception) {
            prefs.syncError = (e as? IOException)?.message ?: "Sync failed: ${e.message ?: e.javaClass.simpleName}"
        }
        return changed[0]
    }

    /**
     * Makes [uri] this device's sync file. The first sync has to work before the file is kept, so a
     * file that isn't a Tokalot sync file is never adopted (or written to). [done] gets null on
     * success or the reason, on the main thread. The caller has already taken the URI permission.
     */
    fun adopt(ctx: Context, uri: Uri, done: (error: String?, changedLocal: Boolean) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val prefs = Prefs(app)
            val changed = BooleanArray(1)
            val error = try {
                val old = prefs.syncUri
                // A different file is a first sync again: what was shared through the old one says nothing about this one.
                sync(app, uri, changed, firstSync = true)
                prefs.syncUri = uri.toString()
                prefs.syncBroken = false
                prefs.syncError = null
                if (old != null && old != uri.toString()) release(app, Uri.parse(old))
                null
            } catch (e: Exception) {
                if (prefs.syncUri != uri.toString()) release(app, uri)
                when (e) {
                    is Unreachable -> "Tokalot can't keep access to that file. Try another folder."
                    is SyncFormatException, is IOException -> e.message
                    else -> "Sync failed: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            main.post { done(error, changed[0]) }
        }.start()
    }

    /** Stops syncing on this device: forgets the file and the base. The file and all settings stay. */
    fun stop(ctx: Context) {
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        main.removeCallbacks(debounced)
        prefs.syncUri?.let { release(app, Uri.parse(it)) }
        prefs.forgetSync()
    }

    /** Takes the lasting read and write permission the picker offered; false if the provider doesn't give one. */
    fun keepPermission(ctx: Context, uri: Uri): Boolean = try {
        ctx.contentResolver.takePersistableUriPermission(uri, RW)
        true
    } catch (_: Exception) {
        false
    }

    private fun release(app: Context, uri: Uri) {
        runCatching { app.contentResolver.releasePersistableUriPermission(uri, RW) }
    }

    private fun hasPermission(app: Context, uri: Uri) =
        app.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }

    /** Read, merge, apply here, write back if needed, remember the base. One at a time. */
    @Synchronized
    private fun sync(app: Context, uri: Uri, changed: BooleanArray, firstSync: Boolean = false) {
        if (!hasPermission(app, uri)) throw Unreachable()
        val bytes = read(app, uri)
        val remote = SyncFormat.parse(String(bytes, Charsets.UTF_8))

        val prefs = Prefs(app)
        val local = prefs.syncState()
        val localKeys = SyncFormat.SERVICES.associateWith { prefs.key(it) }
        val base = SyncFormat.parseBase(if (firstSync) null else prefs.syncBase, DEFAULT_INSTRUCTIONS)
        val result = SyncMerge.merge(local, remote, base, localKeys, prefs.syncKeys)

        if (result.state != local || result.localKeys != localKeys) {
            prefs.applySynced(result.state, result.localKeys)
            changed[0] = true
        }
        if (result.write) {
            write(app, uri, SyncFormat.encode(result.state, result.fileKeys, System.currentTimeMillis(), BY), bytes.size)
        }
        prefs.syncBase = SyncFormat.encodeBase(result.state)
        prefs.syncLast = System.currentTimeMillis()
    }

    private fun read(app: Context, uri: Uri): ByteArray = try {
        val input = app.contentResolver.openInputStream(uri) ?: throw IOException()
        input.use {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_BYTES) throw SyncFormatException(SyncFormat.NOT_OURS)
            }
            out.toByteArray()
        }
    } catch (e: SecurityException) {
        throw Unreachable()
    } catch (e: SyncFormatException) {
        throw e
    } catch (e: Exception) {
        // A cloud folder that's offline, or a file deleted there: not fatal, the next sync tries again.
        throw IOException("Couldn't read the sync file. Will try again.")
    }

    private fun write(app: Context, uri: Uri, text: String, oldSize: Int) {
        val data = text.toByteArray(Charsets.UTF_8)
        try {
            val cr = app.contentResolver
            // "wt" replaces the contents. A provider that doesn't know it gets plain "w", which
            // may leave the old tail in place; padding with spaces (still valid JSON) covers it.
            val truncating = try { cr.openOutputStream(uri, "wt") } catch (e: SecurityException) { throw e } catch (_: Exception) { null }
            val out = truncating ?: cr.openOutputStream(uri, "w") ?: throw IOException()
            out.use {
                it.write(data)
                if (truncating == null) repeat(oldSize - data.size) { _ -> it.write(' '.code) }
                it.flush()
            }
        } catch (e: SecurityException) {
            throw Unreachable()
        } catch (e: Exception) {
            throw IOException("Couldn't write the sync file. Will try again.")
        }
    }

    /** "Synced just now", "Synced 5 min ago"… for the status line. */
    fun ago(then: Long, now: Long = System.currentTimeMillis()): String {
        val min = (now - then) / 60_000
        return when {
            then <= 0 -> "Not synced yet"
            min < 1 -> "Synced just now"
            min < 60 -> "Synced $min min ago"
            min < 48 * 60 -> "Synced ${min / 60} h ago"
            else -> "Synced ${min / (24 * 60)} days ago"
        }
    }
}
