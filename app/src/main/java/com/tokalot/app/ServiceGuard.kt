package com.tokalot.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings

/**
 * Gets the mic button's accessibility service back after Tokalot updates itself. Some phones (Samsung) leave it
 * switched off, or switched on but not started, after an update, and only turning it off and on again in Android's
 * settings brings the button back. An app can't flip that switch itself, unless it has been given
 * WRITE_SECURE_SETTINGS once over USB (`adb shell pm grant com.tokalot.app android.permission.WRITE_SECURE_SETTINGS`),
 * which survives updates. With it, Tokalot does the off-and-on itself; without it, a notification takes you there.
 *
 * The same check also runs every quarter of an hour or so ([GuardJob]), because a phone's battery saver can kill the
 * service's process at any time and some phones don't start it again (stock Android does). Until now that only came
 * to light when the app was opened, which is also where the "isn't running" card on Home comes from.
 */
object ServiceGuard {
    private const val CHANNEL = "service"
    private const val NOTE_ID = 8
    private const val JOB_ID = 81
    private const val WAIT_MS = 6000L // how long Android gets to start the service after the update
    private const val NOTE_EVERY_MS = 12 * 60 * 60 * 1000L // the periodic check nags at most twice a day
    const val GRANT_COMMAND = "adb shell pm grant com.tokalot.app android.permission.WRITE_SECURE_SETTINGS"

    private fun component(ctx: Context) = ComponentName(ctx, OfflineFlowService::class.java)

    private fun enabledList(ctx: Context): List<String> =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').filter { it.isNotBlank() }

    private fun isOurs(ctx: Context, entry: String) =
        ComponentName.unflattenFromString(entry) == component(ctx)

    /** Tokalot's accessibility switch is on. Android usually stores the full class name, but "package/.Class" is valid too. */
    fun enabled(ctx: Context) = enabledList(ctx).any { isOurs(ctx, it) }

    /** Running: Android has started the service in this process. */
    fun running() = OfflineFlowService.instance != null

    /** Whether the switch was on the last time the service ran (cleared when it's switched off on purpose). */
    fun wanted(ctx: Context) = prefs(ctx).getBoolean("wanted", false)
    fun setWanted(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("wanted", on).apply()
    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("guard", Context.MODE_PRIVATE)

    /** Given the one-time permission that lets Tokalot switch its own service back on. */
    fun canRepair(ctx: Context) =
        ctx.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    /** It should be running and isn't: switched off by an update, or on but never started. */
    fun broken(ctx: Context) = !running() && (enabled(ctx) || wanted(ctx))

    // ---------- battery ----------

    /** Tokalot is on the phone's "unrestricted" (not optimised) battery list, so its process is less likely to be killed. */
    fun batteryUnrestricted(ctx: Context) =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true

    /** Phone makers whose battery savers are known to stop background services that stock Android would leave alone. */
    val strictMaker: Boolean = Build.MANUFACTURER.lowercase() in setOf(
        "samsung", "oneplus", "oppo", "realme", "xiaomi", "redmi", "poco", "huawei", "honor", "vivo", "iqoo", "meizu", "asus", "tecno", "infinix",
    )

    /** Android's own "allow Tokalot to run unrestricted?" prompt, or the list it lives in where the prompt isn't offered. */
    fun batteryIntent(ctx: Context): Intent {
        val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
        return if (ask.resolveActivity(ctx.packageManager) != null) ask else Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    /**
     * Switches the service off and on again, which makes Android start it afresh. Needs [canRepair].
     * True if the setting was written.
     */
    fun repair(ctx: Context): Boolean {
        if (!canRepair(ctx)) return false
        // Once a minute at most, so a service that can't start isn't switched off and on at every turn.
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastRepair != 0L && now - lastRepair < 60_000L) return false
        lastRepair = now
        return runCatching {
            val cr = ctx.contentResolver
            val others = enabledList(ctx).filterNot { isOurs(ctx, it) }
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
            Settings.Secure.putString(
                cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others + component(ctx).flattenToString()).joinToString(":")
            )
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            MicLog.add(ctx, "guard: switched the service off and on")
            true
        }.getOrDefault(false)
    }

    private var lastRepair = 0L

    /** Fixes it if it can, else puts up a notification that opens Tokalot's accessibility page. */
    fun check(ctx: Context) {
        if (!broken(ctx)) { cancelNote(ctx); return }
        MicLog.add(ctx, "guard: service not running after an update (switch ${if (enabled(ctx)) "on" else "off"})")
        if (repair(ctx)) return
        notifyBroken(ctx)
    }

    private fun notifyBroken(ctx: Context, afterUpdate: Boolean = true) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        MicLog.add(ctx, "guard: notification shown")
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Mic button", NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, component(ctx).flattenToString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val target = if (open.resolveActivity(ctx.packageManager) != null) open
            else Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, 4, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cause = if (afterUpdate) "The update switched off the mic button." else "The phone stopped the mic button."
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Turn Tokalot back on")
            .setContentText("$cause Tap, then turn Tokalot off and on.")
            .setStyle(Notification.BigTextStyle().bigText(
                "$cause Tap here, then turn Tokalot off and on again. " +
                    "Settings › Setup in Tokalot shows how to make this automatic" +
                    (if (afterUpdate) "." else ", and how to keep the phone's battery saver off Tokalot.")
            ))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTE_ID, n) }
    }

    fun cancelNote(ctx: Context) {
        runCatching { ctx.getSystemService(NotificationManager::class.java)?.cancel(NOTE_ID) }
    }

    /** Tokalot was just updated: give Android a few seconds to start the service, then check. */
    class UpdateReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
            val app = ctx.applicationContext
            MicLog.add(app, "updated to ${Updater.currentVersion(app)}")
            schedule(app)
            val done = goAsync()
            Handler(Looper.getMainLooper()).postDelayed({
                try { check(app) } finally { done.finish() }
            }, WAIT_MS)
        }
    }

    // ---------- the periodic check ----------

    /** Starts the periodic check if it isn't already scheduled. Called when the app opens and when the service starts. */
    fun schedule(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java) ?: return
        if (js.getPendingJob(JOB_ID) != null) return
        runCatching {
            js.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(ctx, GuardJob::class.java))
                    .setPeriodic(JobInfo.getMinPeriodMillis()) // 15 minutes; Android spaces it out further in Doze
                    .setPersisted(true) // survives a restart of the phone
                    .build()
            )
        }
    }

    /**
     * Every quarter of an hour or so: if the switch is on but the service isn't running, Android has dropped it (the
     * battery saver killed the process and nothing started it again). Fixed the same way as after an update, with the
     * notification at most twice a day. Only the switch counts here, not [wanted]: if someone switched Tokalot off while
     * the service wasn't around to notice, that's not something to nag about.
     */
    class GuardJob : JobService() {
        override fun onStartJob(params: JobParameters): Boolean {
            val app = applicationContext
            if (!enabled(app) || running()) { cancelNote(app); return false }
            // This job may have started the process itself, and Android may be about to start the service in it too.
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    if (enabled(app) && !running()) {
                        MicLog.add(app, "guard: the service isn't running though the switch is on (checked by the periodic job)")
                        if (!repair(app)) {
                            val p = prefs(app)
                            val now = System.currentTimeMillis()
                            if (now - p.getLong("notedAt", 0L) > NOTE_EVERY_MS) {
                                p.edit().putLong("notedAt", now).apply()
                                notifyBroken(app, afterUpdate = false)
                            }
                        }
                    }
                } finally { jobFinished(params, false) }
            }, WAIT_MS)
            return true
        }

        override fun onStopJob(params: JobParameters) = false
    }
}
