package dev.keyrelay.service

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.keyrelay.MainActivity
import dev.keyrelay.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Quick Settings tile: pause/resume, with the same status words as the notification. */
class RelayTileService : TileService() {
    private var listening: Job? = null

    override fun onStartListening() {
        listening = AppState.scope.launch { AppState.notice.collect { render() } }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
    }

    override fun onClick() {
        if (HelperConnection.helper == null) {
            openApp()
            return
        }
        RelayService.setEnabled(this, !AppState.relay.value.enabled)
    }

    private fun render() {
        val tile = qsTile ?: return
        val relay = AppState.relay.value
        tile.state = if (relay.attached && relay.enabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        val kind = AppState.notice.value?.kind
        tile.subtitle = kind?.let { getString(Notifications.titleRes(it)) }
        tile.icon = Icon.createWithResource(this, kind?.let(Notifications::iconRes) ?: R.drawable.ic_stat_keyrelay)
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
