package dev.keyrelay.service

import android.util.Log
import dev.keyrelay.core.StartupState
import dev.keyrelay.helper.HelperStatus
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/** What the helper last reported; [attached] is false while no helper binder is held. */
data class RelayStatus(
    val attached: Boolean = false,
    val configured: Boolean = false,
    val connected: Boolean = false,
    val pcReady: Boolean = false,
    val pcName: String = "",
    val endpoint: String = "",
    val targetForeground: Boolean = false,
    val enabled: Boolean = true,
    val override: Boolean = false,
    val grabbed: Boolean = false,
    val keyboards: List<String> = emptyList(),
    val lastError: String = "",
    val rejectReason: Int = 0,
)

fun HelperStatus.toRelayStatus() = RelayStatus(
    attached = true,
    configured = configured,
    connected = connected,
    pcReady = pcReady,
    pcName = pcName.orEmpty(),
    endpoint = endpoint.orEmpty(),
    targetForeground = targetForeground,
    enabled = enabled,
    override = overriding,
    grabbed = grabbed,
    keyboards = keyboards?.toList().orEmpty(),
    lastError = lastError.orEmpty(),
    rejectReason = rejectReason,
)

/** Process-wide state shared by the service, the screens, the tile and the notification. */
object AppState {
    val relay = MutableStateFlow(RelayStatus())

    /** Last round trip in ms, -1 unknown. Apart from [relay] so a ping redraws only what shows it, not every screen and the notification model. */
    val rtt = MutableStateFlow(-1)
    val startup = MutableStateFlow<StartupState>(StartupState.Idle)
    val notice = MutableStateFlow<Notifications.Model?>(null)

    /**
     * One event per key press sent to the PC, at most 4 per second: the evdev code while keys are shown, otherwise
     * KeyPulse.NO_KEY. Not replayed or stored; only the status screen listens.
     */
    val keys = MutableSharedFlow<Int>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** For writes that must outlive a screen, a tile click or a receiver call. */
    // An uncaught failure (DataStore disk error) must be logged, not crash the process and loop the sticky service.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> Log.e("KeyRelay", "uncaught", e) })
}
