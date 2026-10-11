package dev.keyrelay.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

enum class AdbConnectResult { CONNECTED, NOT_STARTED, PAIRING_REQUIRED }

enum class StartupFailure { NO_PERMISSION, WIRELESS_DEBUGGING_OFF, PAIRING_REQUIRED, HELPER_NOT_STARTED, HELPER_CRASH_LOOP }

sealed interface StartupState {
    data object Idle : StartupState
    data object WaitingForWifi : StartupState
    data object Starting : StartupState
    data object Running : StartupState
    data class Restarting(val attempt: Int, val delayMs: Long) : StartupState
    data object AwaitingApproval : StartupState
    data class Failed(val failure: StartupFailure, val detail: String) : StartupState
}

/**
 * Android side effects of the start sequence. Failures are exceptions other than cancellation.
 * Time limits use withTimeoutOrNull: a leaked TimeoutCancellationException looks like cancellation and stalls the flow.
 */
interface StartupPorts {
    fun hasSecureSettingsPermission(): Boolean
    suspend fun awaitWifi()
    fun putGlobal(key: String, value: Int)
    suspend fun connectAdb(): AdbConnectResult
    suspend fun startHelper()
    suspend fun awaitAttach(timeoutMs: Long): Boolean
    fun helperAlive(): Boolean
    fun registerWakeups(onWake: () -> Unit)
    fun unregisterWakeups()
    suspend fun helperLogTail(): String
}

/**
 * Starts the shell-uid helper over wireless adb (spec "Baslatma", steps 1-4 and triggers a/b/c).
 * One attempt flies at a time. Call every method from the [scope]'s single thread.
 */
class StartupFlow(private val ports: StartupPorts, private val scope: CoroutineScope, private val now: () -> Long) {
    private val _state = MutableStateFlow<StartupState>(StartupState.Idle)
    val state: StateFlow<StartupState> = _state.asStateFlow()

    private var flight: Job? = null
    private var deaths = 0
    private var attachedAtMs = NOT_ATTACHED

    /** Trigger (a): the relay service started. */
    fun onServiceStarted(helperAttached: Boolean) {
        if (helperAttached) {
            onHelperAttached()
            return
        }
        if (!inFlight()) flight = scope.launch { attempt(withPut = true) }
    }

    /** Trigger (b): closes a running attempt (its cleanup runs first) and starts over. */
    fun onRetry() {
        deaths = 0
        val previous = flight
        flight = scope.launch {
            // NonCancellable: a newer retry cancelling us must still wait for the old flight's cleanup.
            withContext(NonCancellable) { previous?.cancelAndJoin() }
            // withContext does not recheck cancellation on the same dispatcher.
            ensureActive()
            attempt(withPut = true)
        }
    }

    /** Trigger (c). [lastError] is what the helper reported before it died. */
    fun onHelperDied(lastError: String) {
        if (attachedAtMs != NOT_ATTACHED && now() - attachedAtMs >= STABLE_MS) deaths = 0
        attachedAtMs = NOT_ATTACHED
        if (!inFlight()) restartLater(lastError)
    }

    fun onHelperAttached() {
        attachedAtMs = now()
        _state.value = StartupState.Running
    }

    private fun inFlight() = flight?.isActive == true

    // Bounded so a helper that always crashes cannot cycle the approval window and adb on and off forever.
    private fun restartLater(lastError: String) {
        if (deaths >= RESTART_DELAYS_MS.size) {
            _state.value = StartupState.Failed(StartupFailure.HELPER_CRASH_LOOP, lastError)
            return
        }
        val delayMs = RESTART_DELAYS_MS[deaths++]
        _state.value = StartupState.Restarting(deaths, delayMs)
        flight = scope.launch {
            delay(delayMs)
            attempt(withPut = true)
        }
    }

    private fun onWake() {
        if (!inFlight()) flight = scope.launch { attempt(withPut = false) }
    }

    // Not only IO: a corrupt adb key or a libadb state error would otherwise crash the service in a loop.
    private suspend fun attempt(withPut: Boolean) {
        try { runAttempt(withPut) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.value = StartupState.Failed(StartupFailure.HELPER_NOT_STARTED, e.message ?: e.toString()) }
    }

    private suspend fun runAttempt(withPut: Boolean) {
        ports.unregisterWakeups()
        if (!ports.hasSecureSettingsPermission()) {
            _state.value = StartupState.Failed(StartupFailure.NO_PERMISSION, "")
            return
        }
        _state.value = StartupState.WaitingForWifi
        ports.awaitWifi()
        _state.value = StartupState.Starting
        var failure: StartupFailure? = null
        var detail = ""
        try {
            if (withPut) {
                ports.putGlobal(DEVELOPMENT_SETTINGS_ENABLED, 1)
                ports.putGlobal(ADB_ENABLED, 1)
                ports.putGlobal(ADB_WIFI_ENABLED, 1)
            }
            val connected = ports.connectAdb()
            // A connect that ignores cancellation must not start the helper for a cancelled flight.
            currentCoroutineContext().ensureActive()
            when (connected) {
                AdbConnectResult.NOT_STARTED -> failure = StartupFailure.WIRELESS_DEBUGGING_OFF
                AdbConnectResult.PAIRING_REQUIRED -> failure = StartupFailure.PAIRING_REQUIRED
                AdbConnectResult.CONNECTED -> {
                    ports.startHelper()
                    if (!ports.awaitAttach(ShellOutput.COMMAND_TIMEOUT_MS)) {
                        failure = StartupFailure.HELPER_NOT_STARTED
                        detail = ports.helperLogTail()
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                ports.unregisterWakeups()
                ports.putGlobal(ADB_WIFI_ENABLED, 0)
            }
        }
        when (failure) {
            // A helper that attached and died inside this flight had its death ignored; catch it here.
            null -> if (ports.helperAlive()) onHelperAttached() else restartLater("helper died right after attach")
            // Registered only now: our own 0 must not be the first change the observer sees.
            StartupFailure.WIRELESS_DEBUGGING_OFF -> {
                _state.value = StartupState.AwaitingApproval
                ports.registerWakeups(::onWake)
            }
            else -> _state.value = StartupState.Failed(failure, detail)
        }
    }

    companion object {
        const val DEVELOPMENT_SETTINGS_ENABLED = "development_settings_enabled"
        const val ADB_ENABLED = "adb_enabled"
        const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
        val RESTART_DELAYS_MS = longArrayOf(5_000, 20_000, 60_000)
        const val STABLE_MS = 10 * 60_000L
        private const val NOT_ATTACHED = -1L
    }
}
