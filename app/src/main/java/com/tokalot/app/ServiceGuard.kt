package com.tokalot.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Gets the mic button's accessibility service back after Tokalot updates itself. Some phones (Samsung) leave it
 * switched off, or switched on but not started, after an update, and only turning it off and on again in Android's
 * settings brings the button back. An app can't flip that switch itself, unless it has been given
 * WRITE_SECURE_SETTINGS once over USB (`adb shell pm grant com.tokalot.app android.permission.WRITE_SECURE_SETTINGS`),
 * which survives updates. With it, Tokalot does the off-and-on itself; without it, a notification takes you there.
 */
object ServiceGuard {
    private const val CHANNEL = "service"
    private const val NOTE_ID = 8
    private const val WAIT_MS = 6000L // how long Android gets to start the service after the update
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
            true
        }.getOrDefault(false)
    }

    private var lastRepair = 0L

    /** Fixes it if it can, else puts up a notification that opens Tokalot's accessibility page. */
    fun check(ctx: Context) {
        if (!broken(ctx)) { cancelNote(ctx); return }
        if (repair(ctx)) return
        notifyBroken(ctx)
    }

    private fun notifyBroken(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Mic button", NotificationManager.IMPORTANCE_DEFAULT))
        val open = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, component(ctx).flattenToString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val target = if (open.resolveActivity(ctx.packageManager) != null) open
            else Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, 4, target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Turn Tokalot back on")
            .setContentText("The update switched off the mic button. Tap, then turn Tokalot off and on.")
            .setStyle(Notification.BigTextStyle().bigText(
                "The update switched off the mic button. Tap here, then turn Tokalot off and on again. " +
                    "Settings › Setup in Tokalot shows how to make this automatic."
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
            val done = goAsync()
            Handler(Looper.getMainLooper()).postDelayed({
                try { check(app) } finally { done.finish() }
            }, WAIT_MS)
        }
    }
}
