package com.tokalot.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub Releases for a newer signed APK, downloads it, and hands it to Android's
 * installer. Android only accepts it if it's signed with the same key as the installed app.
 * Note: this only works while the GitHub repo (or at least its releases) is public.
 */
object Updater {
    const val REPO = "sidewinderzz/tokalot"
    private const val CHECK_EVERY_MS = 6 * 3600 * 1000L

    class Release(val version: String, val apkUrl: String, val size: Long, val notes: String)

    @Volatile var progress: Int? = null   // download %, null when not downloading
    @Volatile var error: String? = null
    /** True when the last check() couldn't reach GitHub, so "no update" isn't the same as "up to date". */
    @Volatile var lastCheckFailed = false

    fun currentVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)

    /** The newer release found last time we checked, if any (no network). */
    fun available(ctx: Context): Release? {
        val s = sp(ctx)
        val v = s.getString("version", null) ?: return null
        if (!isNewer(v, currentVersion(ctx))) return null
        if (s.getString("dismissed", null) == v) return null
        return Release(v, s.getString("url", "")!!, s.getLong("size", 0), s.getString("notes", "")!!)
    }

    fun dismiss(ctx: Context, version: String) = sp(ctx).edit().putString("dismissed", version).apply()

    /** Blocking. Asks GitHub for the latest release unless we checked recently. Returns true if something changed. */
    fun check(ctx: Context, force: Boolean = false): Boolean {
        val s = sp(ctx)
        val now = System.currentTimeMillis()
        if (!force && now - s.getLong("checked", 0) < CHECK_EVERY_MS) return false
        lastCheckFailed = false
        return try {
            val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val code = conn.responseCode
            if (code != 200) {
                s.edit().putLong("checked", now).apply()
                lastCheckFailed = code != 404 // 404 = no releases yet, or the repo is private
                return false
            }
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val version = o.getString("tag_name").removePrefix("v")
            val assets = o.getJSONArray("assets")
            var url = ""
            var size = 0L
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    url = a.getString("browser_download_url"); size = a.optLong("size"); break
                }
            }
            if (url.isEmpty()) return false
            val changed = s.getString("version", null) != version
            s.edit().putLong("checked", now).putString("version", version).putString("url", url)
                .putLong("size", size).putString("notes", o.optString("body").take(500)).apply()
            changed
        } catch (_: Exception) {
            lastCheckFailed = true
            false
        }
    }

    /** "1.10" > "1.9"; compares dot-separated numbers. */
    fun isNewer(a: String, b: String): Boolean {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }) - (y.getOrElse(i) { 0 })
            if (d != 0) return d > 0
        }
        return false
    }

    /** Blocking download to the cache folder. Updates [progress]. */
    fun download(ctx: Context, r: Release): File {
        val out = File(ctx.cacheDir, "update.apk")
        progress = 0
        error = null
        try {
            var conn = URL(r.apkUrl).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 10000
            conn.readTimeout = 30000
            // GitHub redirects release downloads to another host; follow it manually if needed.
            if (conn.responseCode in 300..399) {
                val loc = conn.getHeaderField("Location")
                conn = URL(loc).openConnection() as HttpURLConnection
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: r.size
            conn.inputStream.use { input ->
                out.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n)
                        done += n
                        if (total > 0) progress = (done * 100 / total).toInt()
                    }
                }
            }
            return out
        } finally {
            progress = null
        }
    }

    /** Hands the APK to Android's installer; Android then shows its own "Update?" prompt. */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("tokalot.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(ctx, MainActivity::class.java).setAction(MainActivity.ACTION_INSTALL_STATUS)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getActivity(ctx, 3, intent, flags)
            session.commit(pi.intentSender)
        }
    }
}
