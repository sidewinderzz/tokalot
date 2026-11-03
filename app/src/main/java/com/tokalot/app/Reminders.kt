package com.tokalot.app

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.time.ZonedDateTime

/**
 * Reminders on voice notes (beta): the note's own reminder and timed checklist lines ring at their time.
 *
 * Built to be as dependable as the phone's own reminder app:
 * - One alarm at a time, for the next thing due, set with Android's alarm-clock alarm. That's the kind the clock app
 *   uses: it goes off on time even in deep sleep, and battery savers leave it alone.
 * - Everything is worked out again from the notes file whenever the notes change, the phone restarts, the clock or
 *   time zone changes, or Tokalot updates. Nothing that rings lives only in memory.
 * - Something that came due while the phone was off rings as soon as it's back (if it's less than a day late).
 * - What has rung is remembered on this phone by note, line and time, so a snoozed or moved one rings again but
 *   nothing rings twice.
 */
object Reminders {
    const val CHANNEL = "reminders"
    const val CHANNEL_ALARM = "reminder_alarms"
    private const val ACTION_FIRE = "com.tokalot.app.REMINDER_FIRE"
    private const val ACTION_DONE = "com.tokalot.app.REMINDER_DONE"
    private const val ACTION_SNOOZE = "com.tokalot.app.REMINDER_SNOOZE"
    private const val EXTRA_NOTE = "note"
    private const val EXTRA_ITEM = "item"
    private const val EXTRA_MINUTES = "minutes"
    private const val PREFS = "reminders"
    private const val RUNG = "rung"
    /** How late something can be (the phone was off) and still ring. */
    private const val LATE_MS = 24 * 3600_000L

    /** Sets the alarm for the next thing due in [notes], and rings anything already due. Safe to call from any thread. */
    @Synchronized
    fun schedule(ctx: Context, notes: List<Notes.Note> = Notes.all(ctx)) {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        val dues = notes.flatMap { Plans.dues(it.id, it.text, it.plan) }
        val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val rung = sp.getStringSet(RUNG, emptySet()).orEmpty()
        val keys = dues.map { it.key }.toSet()
        // Forget what no longer exists, so the list never grows.
        val kept = rung.filter { it in keys }.toMutableSet()

        val late = dues.filter { it.at <= now && it.key !in kept }
        for (d in late) {
            if (now - d.at <= LATE_MS) notify(app, d)
            kept += d.key
        }
        if (kept != rung) sp.edit().putStringSet(RUNG, kept).apply()

        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val fire = PendingIntent.getBroadcast(app, 0, Intent(app, Receiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val next = dues.filter { it.at > now && it.key !in kept }.minByOrNull { it.at }
        if (next == null) { am.cancel(fire); return }
        val show = PendingIntent.getActivity(app, 1, openIntent(app, next.noteId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        if (exactAllowed(app)) {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(next.at, show), fire)
        } else {
            // Exact alarms switched off for Tokalot: still rings, but Android may hold it back a few minutes.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at, fire)
        }
    }

    fun exactAllowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true

    private fun openIntent(ctx: Context, noteId: Long) = Intent(ctx, MainActivity::class.java)
        .putExtra(MainActivity.EXTRA_NOTE, noteId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun notifyId(noteId: Long, itemId: Long?) = (noteId * 31 + (itemId ?: -1)).hashCode()

    private fun channels(nm: NotificationManager) {
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Reminders and timed checklist lines on your notes"
            enableVibration(true)
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALARM, "Alarm-style reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Reminders set to ring like an alarm clock, until you tap Done or Snooze"
            enableVibration(true)
            val sound: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            setSound(sound, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            setBypassDnd(true)
        })
    }

    private fun notify(ctx: Context, d: Due) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        channels(nm)
        val id = notifyId(d.noteId, d.itemId)
        fun action(act: String, code: Int, minutes: Int = 0): PendingIntent {
            val i = Intent(ctx, Receiver::class.java).setAction(act)
                .putExtra(EXTRA_NOTE, d.noteId).putExtra(EXTRA_ITEM, d.itemId ?: Long.MIN_VALUE).putExtra(EXTRA_MINUTES, minutes)
            return PendingIntent.getBroadcast(ctx, id * 4 + code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val open = PendingIntent.getActivity(ctx, id, openIntent(ctx, d.noteId), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val title = d.text.ifBlank { "Reminder" }
        val showText = Prefs(ctx).reminderLockText
        val b = Notification.Builder(ctx, if (d.alarm) CHANNEL_ALARM else CHANNEL)
            .setSmallIcon(R.drawable.ic_bell)
            .setContentTitle(title)
            .setContentText("Tokalot reminder · ${Plans.label(d.at, ZonedDateTime.now())}")
            .setCategory(if (d.alarm) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setWhen(d.at)
            .setShowWhen(true)
            .addAction(Notification.Action.Builder(null, "Done", action(ACTION_DONE, 0)).build())
            .addAction(Notification.Action.Builder(null, "Snooze 10 min", action(ACTION_SNOOZE, 1, 10)).build())
            .addAction(Notification.Action.Builder(null, "1 hour", action(ACTION_SNOOZE, 2, 60)).build())
            // On the lock screen: the text only when the privacy setting allows it (and Android's own "hide sensitive
            // content" setting, which hides it on top of that).
            .setVisibility(if (showText) Notification.VISIBILITY_PRIVATE else Notification.VISIBILITY_SECRET)
            .setPublicVersion(Notification.Builder(ctx, if (d.alarm) CHANNEL_ALARM else CHANNEL)
                .setSmallIcon(R.drawable.ic_bell).setContentTitle("Tokalot reminder").build())
        val n = b.build()
        if (d.alarm) n.flags = n.flags or Notification.FLAG_INSISTENT // keeps ringing until Done or Snooze
        runCatching { nm.notify(id, n) }
    }

    /** Ticks off what rang (Done), or moves it on by some minutes (Snooze). */
    private fun act(ctx: Context, intent: Intent) {
        val noteId = intent.getLongExtra(EXTRA_NOTE, 0)
        val itemId = intent.getLongExtra(EXTRA_ITEM, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
        ctx.getSystemService(NotificationManager::class.java)?.cancel(notifyId(noteId, itemId))
        when (intent.action) {
            ACTION_DONE -> Notes.updatePlan(ctx, noteId) { p -> p?.let { Plans.setDone(it, itemId, true) } }
            ACTION_SNOOZE -> {
                val at = System.currentTimeMillis() + intent.getIntExtra(EXTRA_MINUTES, 10) * 60_000L
                Notes.updatePlan(ctx, noteId) { p -> p?.let { Plans.moveTo(it, itemId, at) } }
            }
        }
    }

    /** What the mic button's bubble says after saving: "Reminder set · Tomorrow 6:00 AM", "Checklist saved · 5 items". */
    fun savedLabel(made: Plans.Made): String {
        val plan = made.plan ?: return "Saved to Notes"
        return when (made.kind) {
            Plans.Kind.REMINDER -> if (plan.remind != 0L) "Reminder set · ${Plans.label(plan.remind, ZonedDateTime.now())}"
                else "Saved to Notes · add the time from the note's ⋮ menu"
            Plans.Kind.LIST -> "Checklist saved · ${plan.items.size} ${if (plan.items.size == 1) "item" else "items"}"
            Plans.Kind.NOTE -> "Saved to Notes"
        }
    }

    /**
     * Asks the AI (only when AI cleanup is on, as chosen for this beta) to turn [said] into a reminder or a checklist, and
     * puts its version on note [id] in place of the phone's own [made], unless the note was changed meanwhile.
     * [asked]: what the start phrase asked for; null lets the AI decide (the Voice note tile). [said] goes to the
     * cleanup service, the same one the dictation itself just went to. [onMade] gets a line to show when it changed
     * something the user didn't ask for. Failing is quiet: the phone's version stays.
     */
    fun refine(ctx: Context, id: Long, said: String, asked: Plans.Kind?, made: Plans.Made?, onMade: (String) -> Unit) {
        val app = ctx.applicationContext
        val prefs = Prefs(app)
        if (prefs.cleanup == CleanupChoice.OFF || !NoteImport.canAsk(prefs) || said.trim().length < 12) return
        Thread {
            runCatching {
                val now = ZonedDateTime.now()
                val reply = NoteImport.ask(prefs, Plans.aiSystem(asked, now), said, 800)
                val better = Plans.fromAi(reply, now.zone, id + 1) ?: return@runCatching
                if (better.kind == Plans.Kind.NOTE) return@runCatching // nothing to add; asked-for ones keep the phone's version
                if (asked != null && better.kind != asked) return@runCatching
                val now2 = Notes.all(app).firstOrNull { it.id == id } ?: return@runCatching
                if (now2.plan != made?.plan?.takeIf { !it.isEmpty }) return@runCatching // changed by hand meanwhile
                Notes.updatePlan(app, id, text = better.text.ifEmpty { null }) { better.plan }
                if (asked == null) android.os.Handler(android.os.Looper.getMainLooper()).post { onMade(savedLabel(better)) }
            }
        }.start()
    }

    // ---------- Reminder check: what the phone needs for reminders to ring on time ----------

    /** One thing that can stop reminders, whether it's fine now, and how to fix it. */
    class Check(val title: String, val ok: Boolean, val fixLabel: String, val fix: Intent?)

    fun checks(ctx: Context): List<Check> {
        val pkg = Uri.parse("package:${ctx.packageName}")
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val notifOk = nm?.areNotificationsEnabled() == true &&
            nm.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        val notifFix = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        val exactFix = if (Build.VERSION.SDK_INT >= 31) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkg) else null
        val pm = ctx.getSystemService(PowerManager::class.java)
        val batteryOk = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
        @Suppress("BatteryLife")
        val batteryFix = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg)
        val restricted = Build.VERSION.SDK_INT >= 28 &&
            ctx.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true
        return listOf(
            Check("Notifications on", notifOk, "Turn on", notifFix),
            Check("Alarms & reminders allowed", exactAllowed(ctx), "Allow", exactFix),
            Check("Battery saver off for Tokalot", batteryOk, "Allow", batteryFix),
            Check("Not restricted in the background", !restricted, "Open", Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)),
        )
    }

    /** Samsung puts apps it thinks are unused into "Sleeping apps", which no app can see. Worth a tip on Samsung phones. */
    val samsung: Boolean get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    /** Rings what's due, ticks or snoozes from a notification, and sets the alarms again after a restart or a clock change. */
    class Receiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val app = ctx.applicationContext
            val done = goAsync()
            Thread {
                try {
                    when (intent.action) {
                        ACTION_DONE, ACTION_SNOOZE -> act(app, intent) // saving the notes sets the alarms again
                        else -> schedule(app) // fire, boot, update, clock or time zone change
                    }
                } catch (_: Exception) {
                } finally { done.finish() }
            }.start()
        }
    }
}
