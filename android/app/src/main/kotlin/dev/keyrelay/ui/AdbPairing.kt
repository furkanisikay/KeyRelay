package dev.keyrelay.ui

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Log
import dev.keyrelay.MainActivity
import dev.keyrelay.R
import dev.keyrelay.launcher.AdbSetup
import dev.keyrelay.service.AppState
import dev.keyrelay.service.Notifications
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Wizard step 2: finds the pairing port over mDNS and pairs with the code typed on screen or in the notification. */
object AdbPairing {
    const val CODE_LENGTH = 6
    private const val STOP_DELAY_MS = 2_000L

    sealed interface State {
        data object Searching : State
        data class Found(val host: String, val port: Int) : State
        data object Working : State
        data object Done : State
        data class Failed(val detail: String) : State
    }

    val state = MutableStateFlow<State>(State.Searching)
    @Volatile private var found: State.Found? = null
    private var mdns: AdbMdns? = null
    private var pendingStop: Job? = null

    /** The wizard's pairing step is searching; without it a code typed in the notification has no port to go to. */
    var searching = false
        private set

    /** Main thread. Right after [stopDiscovery] (a rotation) the running search and its port are kept. */
    fun startDiscovery(context: Context) {
        pendingStop?.cancel()
        pendingStop = null
        searching = true
        if (mdns != null) return
        found = null
        state.value = State.Searching
        mdns = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, port ->
            if (host != null && port > 0) {
                val f = State.Found(host.hostAddress.orEmpty(), port)
                found = f
                // A pairing in progress keeps its state; only the port is refreshed.
                if (state.value !is State.Working && state.value != State.Done) state.value = f
            } else {
                // The pairing dialog closed: that port is gone, a new one has to be found.
                found = null
                if (state.value is State.Found) state.value = State.Searching
            }
        }.also { it.start() }
    }

    /** Main thread. Delayed: AdbMdns.stop() before NSD has reported the search as started stops nothing (javap). */
    fun stopDiscovery() {
        searching = false
        val m = mdns ?: return
        pendingStop?.cancel()
        pendingStop = AppState.scope.launch {
            delay(STOP_DELAY_MS)
            m.stop()
            if (mdns === m) {
                mdns = null
                found = null
            }
        }
    }

    fun hasPort() = found != null

    // ASCII only: Char.isDigit also accepts other scripts' digits, which adbd would compare byte for byte and reject.
    fun isValidCode(code: String) = code.length == CODE_LENGTH && code.all { it in '0'..'9' }

    /** Pairs, then grants WRITE_SECURE_SETTINGS over the same wireless debugging the user has open. Main thread. */
    suspend fun submit(context: Context, code: String) {
        // One pairing at a time: the on-screen button and the notification can both send.
        if (state.value == State.Working) return
        val target = found
        if (target == null || !isValidCode(code)) {
            state.value = State.Failed(context.getString(if (target == null) R.string.wizard_pair_no_port else R.string.wizard_pair_bad_code))
            PairingNotification.update(context, state.value)
            return
        }
        state.value = State.Working
        var paired = false
        state.value = try {
            paired = AdbSetup.pair(context, target.host, target.port, code)
            when {
                !paired -> State.Failed(context.getString(R.string.wizard_pair_refused))
                !AdbSetup.grantSecureSettings(context) -> State.Failed(context.getString(R.string.wizard_pair_no_permission))
                else -> {
                    stopDiscovery()
                    State.Done
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("KeyRelay", "adb pairing failed", e)
            State.Failed(context.getString(if (paired) R.string.wizard_pair_unreachable else R.string.wizard_pair_no_answer))
        }
        PairingNotification.update(context, state.value)
    }
}

/** Code entry in the notification shade: works while the Settings pairing dialog covers KeyRelay. */
object PairingNotification {
    const val KEY_CODE = "code"
    private const val ID = 2
    private const val RESULT_VISIBLE_MS = 10_000L

    fun show(context: Context) =
        post(context, withCodeInput(context, base(context).setContentTitle(context.getString(R.string.wizard_code_notification_title))).build())

    /**
     * Success is a short plain notice. A failure keeps the code entry while the pairing step is searching; otherwise
     * (step left, or a new process after KeyRelay was closed) a code would only fail again, so it offers to open KeyRelay.
     */
    fun update(context: Context, state: AdbPairing.State) {
        val notification = when (state) {
            AdbPairing.State.Done -> base(context).setContentTitle(context.getString(R.string.wizard_pair_done)).setTimeoutAfter(RESULT_VISIBLE_MS)
            is AdbPairing.State.Failed -> {
                val failed = base(context).setContentTitle(context.getString(R.string.wizard_pair_failed, state.detail))
                if (AdbPairing.searching) withCodeInput(context, failed) else withOpenApp(context, failed).setTimeoutAfter(RESULT_VISIBLE_MS)
            }
            else -> return
        }
        post(context, notification.build())
    }

    private fun withOpenApp(context: Context, builder: Notification.Builder): Notification.Builder {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
        )
        val action = Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_keyrelay), context.getString(R.string.wizard_code_open_action), open).build()
        return builder.setContentText(context.getString(R.string.wizard_code_open_app)).setContentIntent(open).addAction(action).setAutoCancel(true)
    }

    /** Leaving the step: a code typed into a left-over notification would only fail. */
    fun cancel(context: Context) = context.getSystemService(NotificationManager::class.java).cancel(ID)

    private fun withCodeInput(context: Context, builder: Notification.Builder): Notification.Builder {
        val input = RemoteInput.Builder(KEY_CODE).setLabel(context.getString(R.string.wizard_code_label)).build()
        // Mutable so the system can add the typed text; the explicit target keeps that safe.
        val reply = PendingIntent.getBroadcast(
            context, 0, Intent(context, PairingCodeReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        val action = Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stat_keyrelay), context.getString(R.string.wizard_code_action), reply)
            .addRemoteInput(input).build()
        return builder.setContentText(context.getString(R.string.wizard_code_notification_text)).addAction(action)
    }

    private fun base(context: Context) = Notification.Builder(context, Notifications.CHANNEL).setSmallIcon(R.drawable.ic_stat_keyrelay).setOnlyAlertOnce(true)

    private fun post(context: Context, notification: Notification) {
        Notifications.createChannel(context)
        context.getSystemService(NotificationManager::class.java).notify(ID, notification)
    }
}

/**
 * goAsync keeps the process active until the pairing ends; without it a cached KeyRelay (Settings full screen) may be
 * frozen mid-pairing. The work is meant to be bounded by its own timeouts (pair 10 s + connect 20 s + grant 10 s).
 * Not verified: that those timeouts really cut libadb's blocking socket, and which broadcast time limit applies to a
 * notification reply (60 s background or 10 s foreground). Measure on a device before relying on it.
 */
class PairingCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PairingNotification.KEY_CODE)?.toString()?.trim().orEmpty()
        val app = context.applicationContext
        val pending = goAsync()
        AppState.scope.launch {
            try {
                AdbPairing.submit(app, code)
            } finally {
                pending.finish()
            }
        }
    }
}
