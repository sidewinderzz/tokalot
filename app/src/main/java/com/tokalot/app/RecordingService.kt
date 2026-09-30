package com.tokalot.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * A foreground service that exists only while recording. Android only lets an app use the
 * mic in the background if it has a visible "microphone" foreground service, so this is what
 * keeps recording alive when the text field or keyboard closes mid-sentence. It also puts a
 * "Listening" notification up with a Stop button.
 */
class RecordingService : Service() {

    companion object {
        const val ACTION_STOP = "com.tokalot.app.STOP"
        private const val CHANNEL = "recording"
        private const val ID = 7

        /** Called once the service is in the foreground (or failed to get there). */
        @Volatile var onReady: ((Boolean) -> Unit)? = null

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, RecordingService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, RecordingService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            OfflineFlowService.instance?.stopFromNotification()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Recording", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Tokalot is listening")
            .setContentText("Tap Stop or the mic button to finish")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .build()
        val ok = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(ID, n)
            }
            true
        } catch (e: Exception) {
            // Android refused (background start rules). Recording is still attempted;
            // it just may not survive the field closing.
            stopSelf()
            false
        }
        onReady?.invoke(ok)
        onReady = null
        return START_NOT_STICKY
    }
}
