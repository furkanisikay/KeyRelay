package dev.keyrelay.helper

import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import dev.keyrelay.core.ConnectionConfig
import dev.keyrelay.core.Endpoint
import dev.keyrelay.core.ForegroundTracker
import dev.keyrelay.core.KeyPulse
import dev.keyrelay.core.KeyRepeat
import dev.keyrelay.core.RelayClient
import dev.keyrelay.core.RelayStateMachine
import dev.keyrelay.core.RttWatch
import dev.keyrelay.core.ShellOutput
import dev.keyrelay.service.HelperAttachProvider
import java.util.concurrent.locks.LockSupport
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** The helper process: owns the keyboard, the PC connection and the routing state machine. */
class Helper(private val appUid: Int) : IKeyRelayHelper.Stub(), RelayStateMachine.Output {
    // Lock order: lock, then InputReader's monitor or status; InputReader never calls back while holding its own.
    private val lock = Any()
    private val machine = RelayStateMachine(this)
    private val input = InputReader(
        sink = ::onInputKey,
        onKeyboards = ::onKeyboards,
        onNodeLost = {
            synchronized(lock) {
                machine.releaseHeld()
                repeat.stop()
            }
        },
    )
    private val repeat = KeyRepeat()
    // Parked while no key repeats: no wake-ups when idle.
    private val repeater = thread(start = false, isDaemon = true, name = "key-repeat") { repeatLoop() }
    private var tracker = ForegroundTracker { false }
    private var targetUids = IntArray(0)
    private var connection: ConnectionConfig? = null
    private var client: RelayClient? = null
    @Volatile private var clientGeneration = 0
    @Volatile private var statusListener: IStatusListener? = null
    private val status = HelperStatus()
    private val keyPulse = KeyPulse()
    @Volatile private var showKeys = false
    @Volatile private var keyWatch = false
    private val rttWatch = RttWatch()

    private val observer = ProcessObserverBinder(object : ProcessObserverBinder.Listener {
        override fun onForegroundActivitiesChanged(pid: Int, uid: Int, foreground: Boolean) = synchronized(lock) {
            if (tracker.onForegroundActivitiesChanged(pid, uid, foreground)) logForeground()
            machine.onForeground(tracker.targetForeground, tracker.isTarget(uid))
        }

        override fun onProcessDied(pid: Int, uid: Int) = synchronized(lock) {
            if (tracker.onProcessDied(pid)) logForeground()
            machine.onForeground(tracker.targetForeground, tracker.isTarget(uid))
        }
    })

    fun start() {
        val token = attachToApp() ?: run {
            println("${now()} attach to app failed")
            exitProcess(EXIT_ATTACH)
        }
        token.linkToDeath({
            println("${now()} app died")
            exitProcess(0)
        }, 0)
        HiddenApi.registerProcessObserver(observer)
        repeater.start()
        input.start()
    }

    private fun onInputKey(code: Int, value: Int) {
        // Repeats are made here (KeyRepeat); a kernel repeat, if a device still sent one, would double them.
        if (value == 2) return
        val armed = synchronized(lock) {
            machine.onKey(code, value)
            repeat.onKey(code, value, machine.grabbed, SystemClock.uptimeMillis())
            value == 1 && repeat.code == code
        }
        if (armed) LockSupport.unpark(repeater)
    }

    private fun repeatLoop() {
        while (true) {
            val waitMs = synchronized(lock) {
                val now = SystemClock.uptimeMillis()
                val code = repeat.due(now)
                // Through the state machine and the client's one ordered queue: a release always follows the last repeat,
                // and repeats of a consumed shortcut key or of a keyboard not grabbed are dropped there.
                if (code != KeyRepeat.NONE) machine.onKey(code, 2)
                if (repeat.nextMs == Long.MAX_VALUE) -1L else repeat.nextMs - now
            }
            if (waitMs < 0) LockSupport.park(this) else if (waitMs > 0) LockSupport.parkNanos(this, waitMs * NS_PER_MS)
        }
    }

    fun reportFatal(e: Throwable) = publish { lastError = e.toString() }

    // A helper that cannot hand over its binder in time counts as not started (spec "Baslatma" step 1).
    private fun attachToApp(): IBinder? {
        var token: IBinder? = null
        val t = thread(name = "attach") {
            token = runCatching { HiddenApi.attach(HelperAttachProvider.AUTHORITY, this, appUid / PER_USER_RANGE) }
                .onFailure { println("${now()} attach error: $it") }
                .getOrNull()
        }
        t.join(ShellOutput.COMMAND_TIMEOUT_MS)
        return token
    }

    override fun configure(config: RelayConfig) {
        checkCaller()
        showKeys = config.showKeys
        val next = try {
            config.endpoints?.takeIf { it.isNotEmpty() }?.let { endpoints ->
                ConnectionConfig(
                    endpoints.map { Endpoint.parse(it, 0) }, config.spkiSha256, config.deviceId, config.token,
                    config.pingIntervalMs, config.pongTimeoutMs, config.sendTimeoutMs,
                )
            }
        } catch (e: RuntimeException) {
            // Bad endpoint, key size, timing or a missing field: shown to the user, the rest of the settings still apply.
            println("${now()} bad connection config: $e")
            publish { lastError = e.toString() }
            null
        }
        synchronized(lock) {
            if (config.keyRepeatTimeoutMs > 0 && config.keyRepeatDelayMs > 0) {
                repeat.delayMs = config.keyRepeatTimeoutMs.toLong()
                repeat.intervalMs = config.keyRepeatDelayMs.toLong()
            }
            val uids = config.targetUids ?: IntArray(0)
            // Re-seeding on every settings write would throw away the event history the rule depends on.
            if (!uids.contentEquals(targetUids)) {
                targetUids = uids
                val set = uids.toSet()
                tracker = ForegroundTracker { it in set }.apply { seed(HiddenApi.topAppPids()) }
                logForeground()
            }
            if (config.toggleKey in RelayStateMachine.TOGGLE_KEYS) machine.toggleKey = config.toggleKey
            machine.setEnabled(config.enabled)
            machine.onForeground(tracker.targetForeground, targetEvent = false)
            val current = connection
            if (next == null || current == null || !next.sameConnection(current)) restartClient(next)
            publish { configured = true; enabled = config.enabled }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        checkCaller()
        synchronized(lock) { machine.setEnabled(enabled) }
    }

    override fun setStatusListener(listener: IStatusListener?) {
        checkCaller()
        statusListener = listener
        publish { }
    }

    override fun setKeyWatch(on: Boolean) {
        checkCaller()
        keyWatch = on
    }

    override fun setRttWatch(on: Boolean) {
        checkCaller()
        // The status already holds the latest round trip; a screen that just opened gets it without waiting for a ping.
        if (rttWatch.setWatching(on)) publish { }
    }

    override fun ping(): Int {
        checkCaller()
        return Process.myPid()
    }

    private fun checkCaller() {
        val caller = Binder.getCallingUid()
        if (caller != appUid) throw SecurityException("caller uid $caller is not the KeyRelay app")
    }

    private fun restartClient(next: ConnectionConfig?) {
        client?.stop()
        client = null
        machine.setConnected(false)
        machine.setPcReady(false)
        connection = next
        val generation = ++clientGeneration
        if (next != null) client = RelayClient(next, ClientEvents(generation)).also { it.start() }
        publish { connected = false; pcReady = false; rejectReason = 0; rttMs = -1 }
    }

    override fun sendKey(code: Int, value: Int) {
        client?.sendKey(code, value)
        // Nothing leaves while no status screen watches; the key itself only while the user chose to show keys.
        // It is never logged or kept.
        val signal = keyPulse.next(code, value, SystemClock.uptimeMillis(), keyWatch, showKeys)
        if (signal != KeyPulse.NOTHING) runCatching { statusListener?.run { if (signal == KeyPulse.NO_KEY) onKeyPulse() else onKey(signal) } }
    }

    override fun sendSas() {
        client?.sendSas()
    }

    override fun sendLock() {
        client?.sendLock()
    }

    override fun setGrab(on: Boolean) {
        if (!on) repeat.stop()
        input.setGrab(on)
        println("${now()} grab=$on")
        publish { grabbed = on }
    }

    override fun onToggled(enabled: Boolean, override: Boolean) = publish {
        this.enabled = enabled
        overriding = override
    }

    // A connecting keyboard reports the same list 10-12 times; each publish is a binder call.
    private fun onKeyboards(names: List<String>) {
        val array = names.toTypedArray()
        if (synchronized(status) { array.contentEquals(status.keyboards) }) return
        publish { keyboards = array }
    }

    private fun logForeground() {
        println("${now()} foreground=${tracker.targetForeground}")
        publish { targetForeground = tracker.targetForeground }
    }

    private fun publish(update: HelperStatus.() -> Unit) {
        synchronized(status) {
            status.update()
            // A dead app is handled by linkToDeath; a failed oneway call needs nothing more.
            runCatching { statusListener?.onStatus(status) }
        }
    }

    /** Ignores callbacks of a client that a newer configure already replaced. */
    private inner class ClientEvents(private val generation: Int) : RelayClient.Listener {
        private fun current() = generation == clientGeneration

        override fun onConnected(pcName: String, endpoint: Endpoint) {
            synchronized(lock) {
                if (!current()) return
                machine.setConnected(true)
                println("${now()} connected $endpoint")
                publish { connected = true; this.pcName = pcName; this.endpoint = endpoint.toString(); rejectReason = 0; lastError = "" }
            }
        }

        override fun onPcReady(ready: Boolean) {
            synchronized(lock) {
                if (!current()) return
                machine.setPcReady(ready)
                publish { pcReady = ready }
            }
        }

        override fun onDisconnected(reason: String) {
            synchronized(lock) {
                if (!current()) return
                repeat.stop()
                machine.setConnected(false)
                machine.setPcReady(false)
                println("${now()} disconnected: $reason")
                publish { connected = false; pcReady = false; lastError = reason; rttMs = -1 }
            }
        }

        override fun onRejected(reason: Int) {
            synchronized(lock) {
                if (!current()) return
                machine.setConnected(false)
                println("${now()} rejected: $reason")
                publish { connected = false; rejectReason = reason }
            }
        }

        override fun onRtt(ms: Int) {
            if (!current()) return
            // Kept either way; only a watching screen makes it a binder call (idle CPU with the screen off).
            if (rttWatch.onRtt()) publish { rttMs = ms } else synchronized(status) { status.rttMs = ms }
        }
    }

    private companion object {
        const val EXIT_ATTACH = 2
        const val PER_USER_RANGE = 100_000
        const val NS_PER_MS = 1_000_000L

        fun now() = System.currentTimeMillis()
    }
}
