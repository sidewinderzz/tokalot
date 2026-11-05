package com.tokalot.app

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Optional (Settings › Keyboard › Codes from texts and emails): watches new notifications only to spot a sign-in code in
 * a text or an email's preview, which Tokalot Keyboard then offers on its bar for a few minutes, as Gboard does. Nothing else
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
        // A group's summary repeats older messages (and older codes); the message's own notification has it.
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        // An old message shown again (the conversation was updated, the phone restarted) isn't a new code.
        val now = System.currentTimeMillis()
        if (n.`when` > 0 && now - n.`when` > 10 * 60 * 1000L) return
        // Texts, emails (Gmail, Outlook and the like show the start of the email), and anything else that looks
        // like one (some banks and apps post codes as plain notices).
        val e = n.extras ?: return
        val found = CodeFinder.find(texts(e).joinToString(" ")) ?: return
        code = found
        at = now
        TokalotKeyboard.instance?.codeArrived()
    }

    /**
     * The words of a notification: its title, and the newest message of a conversation (not the older ones
     * above it, which may hold an old code), or else its text, long text and lines (an inbox of emails).
     */
    private fun texts(e: android.os.Bundle): List<CharSequence> {
        val out = ArrayList<CharSequence>()
        e.getCharSequence(Notification.EXTRA_TITLE)?.let { out += it }
        @Suppress("DEPRECATION")
        val newest = (e.getParcelableArray(Notification.EXTRA_MESSAGES)?.lastOrNull() as? android.os.Bundle)?.getCharSequence("text")
        if (newest != null) { out += newest; return out }
        e.getCharSequence(Notification.EXTRA_TEXT)?.let { out += it }
        e.getCharSequence(Notification.EXTRA_BIG_TEXT)?.let { out += it }
        e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.firstOrNull()?.let { out += it }
        return out
    }
}
