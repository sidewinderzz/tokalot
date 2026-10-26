package com.tokalot.app

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * A short log of what the floating mic button's service saw and decided: when Android started and stopped it,
 * whether a keyboard and a text box were found, why the button was shown or hidden, and any error. It's there to
 * find out why the button didn't show on a phone nobody else can see (Settings › Setup › Mic button log).
 *
 * Only app names and yes/no answers go in it, never anything typed. It stays on the phone (the newest
 * [MAX_LINES] lines), is never synced or backed up, and leaves only when Share is tapped.
 */
object MicLog {
    private const val FILE = "mic-button-log.txt"
    const val MAX_LINES = 600
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "mic-log").apply { isDaemon = true } }
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var lines: ArrayDeque<String>? = null // only touched on the io thread

    fun add(ctx: Context, line: String) {
        val app = ctx.applicationContext
        val text = "${stamp.format(Date())} $line"
        io.execute {
            val list = lines ?: load(app).also { lines = it }
            list.addLast(text)
            while (list.size > MAX_LINES) list.removeFirst()
            runCatching { File(app.filesDir, FILE).writeText(list.joinToString("\n")) }
        }
    }

    private fun load(app: Context): ArrayDeque<String> {
        val f = File(app.filesDir, FILE)
        val read = runCatching { if (f.exists()) f.readLines() else emptyList() }.getOrDefault(emptyList())
        return ArrayDeque(read.takeLast(MAX_LINES))
    }

    /** Everything logged so far, oldest first, with a line about the phone and app on top. */
    fun read(ctx: Context): String {
        val app = ctx.applicationContext
        val body = io.submit<String> { (lines ?: load(app).also { lines = it }).joinToString("\n") }.get()
        return "${header(app)}\n\n${body.ifEmpty { "(nothing logged yet)" }}"
    }

    fun clear(ctx: Context) {
        val app = ctx.applicationContext
        io.execute {
            lines = ArrayDeque()
            runCatching { File(app.filesDir, FILE).delete() }
        }
    }

    /** Hands the log to another app (a chat, an email) as text. */
    fun share(ctx: Context) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Tokalot mic button log")
            .putExtra(Intent.EXTRA_TEXT, read(ctx))
        ctx.startActivity(Intent.createChooser(send, "Share the log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun header(ctx: Context) =
        "Tokalot ${Updater.currentVersion(ctx)} on ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}. " +
            "Switch ${if (ServiceGuard.enabled(ctx)) "on" else "off"}, ${status(ctx).lowercase()}. " +
            "Battery ${if (ServiceGuard.batteryUnrestricted(ctx)) "unrestricted" else "optimised"}."

    /** One line for Settings: whether Android is running the service right now, and since when. */
    fun status(ctx: Context): String {
        val s = OfflineFlowService.instance
        return when {
            s != null -> "Running since ${since(OfflineFlowService.connectedAt)}" +
                (OfflineFlowService.lastKeyboardAt.takeIf { it > 0 }?.let { ", keyboard last seen ${since(it)}" } ?: "")
            ServiceGuard.enabled(ctx) -> "Not running, though the switch is on"
            else -> "Not running: the switch is off"
        }
    }

    /** "10:32" today, else "Oct 25, 10:32". The time is kept as uptime so a clock change can't move it. */
    private fun since(elapsedRealtime: Long): String {
        val at = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - elapsedRealtime)
        val today = SimpleDateFormat("yyyyMMdd", Locale.US)
        val f = if (today.format(Date(at)) == today.format(Date())) SimpleDateFormat("HH:mm", Locale.US)
            else SimpleDateFormat("MMM d, HH:mm", Locale.US)
        return f.format(Date(at))
    }
}
