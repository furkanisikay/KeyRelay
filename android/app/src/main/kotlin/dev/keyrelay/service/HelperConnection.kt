package dev.keyrelay.service

import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import dev.keyrelay.core.KeyPulse
import dev.keyrelay.helper.HelperStatus
import dev.keyrelay.helper.IKeyRelayHelper
import dev.keyrelay.helper.IStatusListener
import dev.keyrelay.helper.RelayConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The app's end of the helper binder: attach, death, status updates and calls into the helper. */
object HelperConnection {
    private const val TAG = "KeyRelayHelperLink"

    /** Given to the helper on attach; when this process dies the helper sees it and exits (grab released). */
    val appToken: IBinder = Binder()

    @Volatile var helper: IKeyRelayHelper? = null
        private set

    private val _generation = MutableStateFlow(0)
    /** Grows by one on every attach; the start-up flow waits for it to pass the value it saw before starting. */
    val generation: StateFlow<Int> = _generation.asStateFlow()

    var onAttached: () -> Unit = {}
    var onDied: (lastError: String) -> Unit = {}
    private val main = Handler(Looper.getMainLooper())

    // Status writes, attach and death all run on the main thread: a late status from a dead helper must not mark it attached again.
    private fun statusListener(h: IKeyRelayHelper) = object : IStatusListener.Stub() {
        override fun onStatus(status: HelperStatus) {
            val next = status.toRelayStatus()
            val rtt = status.rttMs
            main.post {
                if (helper !== h) return@post
                AppState.relay.value = next
                AppState.rtt.value = rtt
            }
        }

        override fun onKeyPulse() {
            if (helper === h) AppState.keys.tryEmit(KeyPulse.NO_KEY)
        }

        override fun onKey(code: Int) {
            if (helper === h) AppState.keys.tryEmit(code)
        }
    }

    @Volatile private var keyWatch = false

    /** The status screen is (not) on screen; the helper sends key events only while it is. Oneway, safe on main. */
    fun setKeyWatch(on: Boolean) {
        keyWatch = on
        runCatching { helper?.setKeyWatch(on) }.onFailure { Log.w(TAG, "setKeyWatch failed", it) }
    }

    private var rttWatchers = 0

    /** Main thread. A screen showing the latency starts (true) or stops (false); counted, as screens overlap in transitions. */
    fun watchRtt(on: Boolean) {
        val before = rttWatchers > 0
        rttWatchers = (rttWatchers + if (on) 1 else -1).coerceAtLeast(0)
        val now = rttWatchers > 0
        if (now != before) runCatching { helper?.setRttWatch(now) }.onFailure { Log.w(TAG, "setRttWatch failed", it) }
    }

    /** Binder thread: called by [HelperAttachProvider]. */
    fun attach(binder: IBinder) {
        val h = IKeyRelayHelper.Stub.asInterface(binder)
        main.post {
            try {
                binder.linkToDeath({ main.post { died(h) } }, 0)
            } catch (e: RemoteException) {
                Log.i(TAG, "helper died before attach")
                onDied("helper died before attach")
                return@post
            }
            helper = h
            // A new helper starts not watching; tell it if the status screen is already open.
            if (keyWatch) runCatching { h.setKeyWatch(true) }
            if (rttWatchers > 0) runCatching { h.setRttWatch(true) }
            _generation.value += 1
            Log.i(TAG, "helper attached generation=${_generation.value}")
            onAttached()
        }
        // Off the main thread; its statuses are posted after the attach above, so they find helper set.
        runCatching { h.setStatusListener(statusListener(h)) }
    }

    private fun died(h: IKeyRelayHelper) {
        if (helper?.asBinder() !== h.asBinder()) return
        helper = null
        val lastError = AppState.relay.value.lastError
        AppState.relay.value = RelayStatus()
        AppState.rtt.value = -1
        Log.i(TAG, "helper died: $lastError")
        onDied(lastError)
    }

    fun isAlive() = helper?.asBinder()?.pingBinder() == true

    fun configure(config: RelayConfig) {
        runCatching { helper?.configure(config) }.onFailure { Log.w(TAG, "configure failed", it) }
    }

    fun setEnabled(on: Boolean) {
        runCatching { helper?.setEnabled(on) }.onFailure { Log.w(TAG, "setEnabled failed", it) }
    }
}
