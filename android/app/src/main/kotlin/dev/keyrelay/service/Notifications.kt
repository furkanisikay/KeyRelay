package dev.keyrelay.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import dev.keyrelay.MainActivity
import dev.keyrelay.R
import dev.keyrelay.core.StartupFailure
import dev.keyrelay.core.StartupState

/** The relay service's single notification (one channel, default importance, silent). */
object Notifications {
    const val ID = 1
    const val CHANNEL = "relay"
    private const val OLD_CHANNEL = "startup"

    /** Everything the notification shows; the latency is left out so a ping does not rebuild it. */
    data class Model(val kind: StatusKind, val detail: String)

    fun model(context: Context, relay: RelayStatus, startup: StartupState, hasPc: Boolean): Model {
        val kind = statusKind(relay, startup, hasPc)
        val detail = when (kind) {
            // The technical detail stays in the logs; the user gets what happened and what to do.
            StatusKind.ERROR -> context.getString(failureRes((startup as StartupState.Failed).failure))
            StatusKind.APPROVAL -> context.getString(R.string.detail_approval)
            StatusKind.STARTING -> (startup as? StartupState.Restarting)?.let { context.getString(R.string.detail_restarting, it.delayMs / 1000) }.orEmpty()
            StatusKind.RELAYING -> context.getString(R.string.detail_relaying, relay.pcName)
            StatusKind.READY, StatusKind.PC_NOT_READY -> context.getString(R.string.detail_ready, relay.pcName)
            StatusKind.CONNECTING -> context.getString(R.string.detail_connecting, relay.endpoint.ifEmpty { relay.pcName })
            else -> ""
        }
        return Model(kind, detail)
    }

    fun titleRes(kind: StatusKind) = when (kind) {
        StatusKind.STARTING -> R.string.status_starting
        StatusKind.NO_WIFI -> R.string.status_no_wifi
        StatusKind.APPROVAL -> R.string.status_approval
        StatusKind.ERROR -> R.string.status_error
        StatusKind.NOT_PAIRED -> R.string.status_not_paired
        StatusKind.REPAIR -> R.string.status_repair
        StatusKind.PAUSED -> R.string.status_paused
        StatusKind.CONNECTING -> R.string.status_connecting
        StatusKind.PC_NOT_READY -> R.string.status_pc_not_ready
        StatusKind.READY -> R.string.status_ready
        StatusKind.RELAYING -> R.string.status_relaying
    }

    /**
     * The status bar icon: monochrome, so the state is told by shape (keycap + arrow, alone, dots, bars or "!").
     * Derived from the kind only, so a ping never re-posts the notification.
     */
    fun iconRes(kind: StatusKind) = when (kind) {
        StatusKind.RELAYING -> R.drawable.ic_stat_keyrelay
        StatusKind.READY -> R.drawable.ic_stat_ready
        StatusKind.PAUSED -> R.drawable.ic_stat_paused
        StatusKind.STARTING, StatusKind.NO_WIFI, StatusKind.CONNECTING, StatusKind.PC_NOT_READY -> R.drawable.ic_stat_waiting
        StatusKind.ERROR, StatusKind.APPROVAL, StatusKind.NOT_PAIRED, StatusKind.REPAIR -> R.drawable.ic_stat_alert
    }

    fun failureRes(failure: StartupFailure) = when (failure) {
        StartupFailure.NO_PERMISSION -> R.string.failure_no_permission
        StartupFailure.WIRELESS_DEBUGGING_OFF -> R.string.failure_wireless_debugging
        StartupFailure.PAIRING_REQUIRED -> R.string.failure_pairing
        StartupFailure.HELPER_NOT_STARTED -> R.string.failure_helper
        StartupFailure.HELPER_CRASH_LOOP -> R.string.failure_crash_loop
    }

    fun createChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.deleteNotificationChannel(OLD_CHANNEL)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
    }

    fun build(context: Context, m: Model): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val retry = m.kind == StatusKind.ERROR || m.kind == StatusKind.APPROVAL
        val action = if (retry) RelayService.ACTION_RETRY else RelayService.ACTION_TOGGLE
        val label = when {
            retry -> R.string.action_retry
            m.kind == StatusKind.PAUSED -> R.string.action_resume
            else -> R.string.action_pause
        }
        val pending = PendingIntent.getService(
            context, action.hashCode(), Intent(context, RelayService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(iconRes(m.kind))
            .setContentTitle(context.getString(titleRes(m.kind)))
            .setContentText(m.detail)
            .setStyle(Notification.BigTextStyle().bigText(m.detail))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_keyrelay), context.getString(label), pending).build())
            .build()
    }

    fun show(context: Context, m: Model) {
        context.getSystemService(NotificationManager::class.java).notify(ID, build(context, m))
    }
}
