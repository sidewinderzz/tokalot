package com.tokalot.app

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Optional (Settings › Keyboard › Codes from texts): watches new notifications only to spot a sign-in code in
 * a message, which Tokalot Keyboard then offers on its bar for a few minutes, as Gboard does. Nothing else
 * from a notification is looked at, kept or sent anywhere; the code itself is held in memory only.
 */
class CodeListener : NotificationListenerService() {
    companion object {
        /** How long a code is offered. */
        const val FRESH_MS = 3 * 60 * 1000L

        @Volatile var code: String? = null
            private set
        @Volatile var at = 0L
            private set

        /** The code, if one came in the last few minutes. */
        fun fresh(now: Long = System.currentTimeMillis()): String? = code?.takeIf { now - at in 0..FRESH_MS }

        /** Whether the user has allowed Tokalot to see notifications (Android's "Notification access"). */
        fun allowed(ctx: Context): Boolean {
            val list = android.provider.Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            return list.split(':').any { it.startsWith(ctx.packageName + "/") }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn?.notification ?: return
        if (sbn.packageName == packageName || !Prefs(this).kbCodes) return
        // Messages, and anything else that looks like one (some banks and apps post codes as plain notices).
        val e = n.extras ?: return
        val text = listOfNotNull(
            e.getCharSequence(Notification.EXTRA_TITLE), e.getCharSequence(Notification.EXTRA_TEXT),
            e.getCharSequence(Notification.EXTRA_BIG_TEXT),
        ).joinToString(" ")
        val found = CodeFinder.find(text) ?: return
        code = found
        at = System.currentTimeMillis()
        TokalotKeyboard.instance?.codeArrived()
    }
}
