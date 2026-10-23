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
    /** The rolling pre-release each beta build replaces; its title is "Tokalot <version>", e.g. 1.44-beta.190. */
    const val BETA_TAG = "android-beta"
    private const val CHECK_EVERY_MS = 3 * 3600 * 1000L

    class Release(val version: String, val apkUrl: String, val size: Long, val notes: String)

    @Volatile var progress: Int? = null   // download %, null when not downloading
    @Volatile var error: String? = null
    /** True when the last check() couldn't reach GitHub, so "no update" isn't the same as "up to date". */
    @Volatile var lastCheckFailed = false

    fun currentVersion(ctx: Context): String =
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "0"

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("updates", Context.MODE_PRIVATE)

    /** The newer release found last time we checked, if any (no network). */
    /** The newer release found by the last check, or null. [evenDismissed]: also when its banner was closed (Settings offers it anyway). */
    fun available(ctx: Context, evenDismissed: Boolean = false): Release? {
        val s = sp(ctx)
        val v = s.getString("version", null) ?: return null
        if (!isNewer(v, currentVersion(ctx))) return null
        if (!evenDismissed && s.getString("dismissed", null) == v) return null
        return Release(v, s.getString("url", "")!!, s.getLong("size", 0), s.getString("notes", "")!!)
    }

    fun dismiss(ctx: Context, version: String) = sp(ctx).edit().putString("dismissed", version).apply()

    /** Opted in to beta builds as well as releases. On this phone only, as each phone chooses for itself. */
    fun beta(ctx: Context) = sp(ctx).getBoolean("beta", false)

    fun setBeta(ctx: Context, on: Boolean) {
        val e = sp(ctx).edit().putBoolean("beta", on).putLong("checked", 0)
        if (!on) e.remove("version") // a beta already offered isn't offered any more
        e.apply()
    }

    /** A release as GitHub describes it, and the git ref its changelog is read at. */
    private class Found(val version: String, val url: String, val size: Long, val notes: String, val ref: String)

    /** Null with [lastCheckFailed] untouched when there's no such release (404); throws when GitHub can't be reached. */
    private fun fetch(path: String, failIfMissing: Boolean): Found? {
        val conn = URL("https://api.github.com/repos/$REPO/releases/$path").openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 8000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val code = conn.responseCode
        if (code != 200) {
            if (code != 404 || failIfMissing) lastCheckFailed = true // 404 = no releases yet, or the repo is private
            return null
        }
        val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        val tag = o.getString("tag_name")
        val version = if (tag == BETA_TAG) o.optString("name").removePrefix("Tokalot ").trim() else tag.removePrefix("v")
        if (version.isEmpty()) return null
        val assets = o.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) {
                return Found(version, a.getString("browser_download_url"), a.optLong("size"), o.optString("body").take(4000), tag)
            }
        }
        return null
    }

    /** Blocking. Asks GitHub for the latest release unless we checked recently. Returns true if something changed. */
    fun check(ctx: Context, force: Boolean = false): Boolean {
        val s = sp(ctx)
        val now = System.currentTimeMillis()
        if (!force && now - s.getLong("checked", 0) < CHECK_EVERY_MS) return false
        lastCheckFailed = false
        return try {
            // With betas on, whichever is newer: the latest release or the beta (a release of the same
            // version beats its betas, so beta testers land back on it).
            val release = fetch("latest", failIfMissing = false)
            val beta = if (beta(ctx)) fetch("tags/$BETA_TAG", failIfMissing = false) else null
            val f = listOfNotNull(release, beta).reduceOrNull { a, b -> if (isNewer(b.version, a.version)) b else a }
            if (f == null) {
                s.edit().putLong("checked", now).apply()
                return false
            }
            val version = f.version
            val changed = s.getString("version", null) != version
            s.edit().putLong("checked", now).putString("version", version).putString("url", f.url)
                .putLong("size", f.size).putString("notes", f.notes).apply()
            // The new version's changelog, for the banner's "What's new". Fetched once per version;
            // without it the banner falls back to the release text above.
            if (s.getString("changelog_for", null) != version && isNewer(version, currentVersion(ctx))) {
                fetchChangelog(f.ref)?.let { s.edit().putString("changelog", it).putString("changelog_for", version).apply() }
            }
            changed
        } catch (_: Exception) {
            lastCheckFailed = true
            false
        }
    }

    /** The app's CHANGELOG.md as it is at the release's tag [ref], or null if it can't be had. */
    private fun fetchChangelog(ref: String): String? = try {
        val conn = URL("https://raw.githubusercontent.com/$REPO/$ref/app/src/main/assets/${Changelog.FILE}")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 8000
        if (conn.responseCode == 200) conn.inputStream.bufferedReader().use { it.readText() }.take(200_000) else null
    } catch (_: Exception) {
        null
    }

    /**
     * What [r] brings over the installed version: every changelog entry in between, newest first.
     * Empty when the changelog couldn't be fetched (or the release predates it); see [releaseText].
     */
    fun whatsNew(ctx: Context, r: Release): List<Changelog.Entry> {
        val s = sp(ctx)
        if (s.getString("changelog_for", null) != r.version) return emptyList()
        // A beta of 1.45 brings what's under "## 1.45" so far.
        return Changelog.since(Changelog.parse(s.getString("changelog", "")!!), currentVersion(ctx), r.version.substringBefore('-'))
    }

    /** The release's own text on GitHub, tidied, for when [whatsNew] has nothing. May be empty. */
    fun releaseText(r: Release) = Changelog.releaseText(r.notes)

    fun releasePage(version: String) =
        "https://github.com/$REPO/releases/tag/" + if (isBeta(version)) BETA_TAG else "v$version"

    fun isBeta(version: String) = '-' in version

    /** Every version's changes, on GitHub. */
    const val CHANGELOG_PAGE = "https://github.com/$REPO/blob/main/app/src/main/assets/${Changelog.FILE}"

    /**
     * "1.10" > "1.9"; compares dot-separated numbers. A beta ("1.45-beta.190") comes after the release
     * before it and before its own release: 1.44 < 1.45-beta.190 < 1.45-beta.191 < 1.45.
     */
    fun isNewer(a: String, b: String): Boolean {
        val x = a.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrElse(i) { 0 }) - (y.getOrElse(i) { 0 })
            if (d != 0) return d > 0
        }
        val betaA = betaNumber(a)
        val betaB = betaNumber(b)
        return when {
            betaA == null -> betaB != null
            betaB == null -> false
            else -> betaA > betaB
        }
    }

    /** 190 for "1.45-beta.190", null for a release. */
    private fun betaNumber(v: String): Int? =
        if ('-' !in v) null else v.substringAfter('-').filter { it.isDigit() }.toIntOrNull() ?: 0

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
