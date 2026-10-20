package com.tokalot.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * Quick Settings tiles that start a dictation without a text box: Dictate (typed into the selected text box,
 * copied if there isn't one) and Voice note (beta, saved to Notes and copied). A second tap while
 * recording finishes. The recording itself runs in the accessibility service, like one from the button.
 */
abstract class StartTile(private val mode: OfflineFlowService.Manual) : TileService() {

    override fun onStartListening() {
        qsTile?.apply { state = Tile.STATE_INACTIVE; updateTile() }
    }

    override fun onClick() {
        if (isLocked) unlockAndRun { start() } else start()
    }

    private fun start() {
        val service = OfflineFlowService.instance
        if (service != null) {
            if (Build.VERSION.SDK_INT < 31) {
                // Newer Android closes the shade through the accessibility service instead.
                @Suppress("DEPRECATION")
                sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS))
            }
            service.startByHand(mode)
            return
        }
        // The accessibility switch is off: open the app, which shows what's missing.
        val open = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(open)
        }
    }

    companion object {
        /** Offers the Voice note tile only while the beta is on (a switched-off tile leaves Quick Settings). */
        fun setNoteTile(ctx: Context, on: Boolean) {
            ctx.packageManager.setComponentEnabledSetting(
                ComponentName(ctx, NoteTile::class.java),
                if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
        }

        /** Android 13+: asks to put a tile in Quick Settings straight away, instead of explaining the edit screen. */
        fun requestAdd(ctx: Context, tile: Class<out StartTile>, label: String, icon: Int) {
            if (Build.VERSION.SDK_INT < 33) return
            runCatching {
                ctx.getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
                    ComponentName(ctx, tile), label, Icon.createWithResource(ctx, icon), ctx.mainExecutor,
                ) { }
            }
        }
    }
}

class DictateTile : StartTile(OfflineFlowService.Manual.DICTATE)

class NoteTile : StartTile(OfflineFlowService.Manual.NOTE)
