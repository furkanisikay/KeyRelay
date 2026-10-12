package dev.keyrelay.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.ViewConfiguration
import dev.keyrelay.core.Endpoint
import dev.keyrelay.core.LatestPusher
import dev.keyrelay.core.StartupFlow
import dev.keyrelay.core.StartupState
import dev.keyrelay.core.defaultPc
import dev.keyrelay.core.promote
import dev.keyrelay.core.touch
import dev.keyrelay.settings.AppSettings
import dev.keyrelay.settings.SettingsStore
import dev.keyrelay.settings.TokenCipher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's only foreground service (connectedDevice): owns the start-up flow, the helper link, the config push and
 * the single status notification.
 */
class RelayService : Service() {
    // Same boundary as AppState.scope: START_STICKY would replay a crash forever.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> Log.e("KeyRelay", "uncaught", e) })
    private var flow: StartupFlow? = null
    private var ports: AppStartupPorts? = null
    // Every trigger goes through one pusher that reads the settings at push time: an older config (say showKeys on)
    // can never reach the helper after a newer one.
    private val config = LatestPusher(scope, { SettingsStore.current(this) }, { Log.w(TAG, "config push failed", it) }, ::pushConfig)
    private val packageChanges = object : BroadcastReceiver() {
        // A reinstalled target app gets a new uid; resolve the targets again.
        override fun onReceive(context: Context, intent: Intent) = config.request()
    }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannel(this)
        if (!enterForeground()) {
            stopSelf()
            return
        }
        val startupPorts = AppStartupPorts(this).also { ports = it }
        val startup = StartupFlow(startupPorts, scope) { SystemClock.elapsedRealtime() }
        flow = startup
        HelperConnection.onAttached = {
            startup.onHelperAttached()
            config.request()
        }
        HelperConnection.onDied = startup::onHelperDied
        scope.launch { startup.state.collect { AppState.startup.value = it } }
        scope.launch { SettingsStore.flow(this@RelayService).collect { config.request() } }
        scope.launch { keepSettingsInSync() }
        scope.launch {
            combine(AppState.relay, AppState.startup, SettingsStore.flow(this@RelayService)) { relay, state, settings ->
                Notifications.model(this@RelayService, relay, state, settings.pcs.defaultPc != null)
            }.distinctUntilChanged().collect {
                AppState.notice.value = it
                Notifications.show(this@RelayService, it)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerReceiver(packageChanges, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(packageChanges, filter)
        startup.onServiceStarted(HelperConnection.helper != null)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val startup = flow ?: return START_NOT_STICKY
        when (intent?.action) {
            ACTION_RETRY -> startup.onRetry()
            ACTION_TOGGLE -> setEnabled(this, !AppState.relay.value.enabled)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // Cancelling the scope runs the flow's cleanup: wireless debugging is switched off even here.
        scope.cancel()
        // Wake-ups stay registered after a failed attempt (awaiting approval); a dead service must not keep them.
        ports?.unregisterWakeups()
        // The tile would otherwise keep showing the last status of a service that is gone.
        AppState.notice.value = null
        runCatching { unregisterReceiver(packageChanges) }
        HelperConnection.onAttached = {}
        HelperConnection.onDied = {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun enterForeground(): Boolean = try {
        val first = Notifications.model(this, AppState.relay.value, StartupState.Idle, hasPc = true)
        startForeground(Notifications.ID, Notifications.build(this, first), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        true
    } catch (e: IllegalStateException) {
        Log.w(TAG, "foreground start refused", e)
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "foreground start refused", e)
        false
    }

    // Keystore decryption and the binder call both stay off the main thread.
    private suspend fun pushConfig(settings: AppSettings) = withContext(Dispatchers.IO) {
        HelperConnection.configure(
            RelayConfigFactory.build(settings, ::uidOf, TokenCipher::open).apply {
                keyRepeatTimeoutMs = ViewConfiguration.getKeyRepeatTimeout()
                keyRepeatDelayMs = ViewConfiguration.getKeyRepeatDelay()
            },
        )
    }

    private fun uidOf(packageName: String): Int? = try {
        packageManager.getPackageUid(packageName, 0)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** Writes back what the helper changed: the shortcut's on/off and the address that worked. */
    private suspend fun keepSettingsInSync() {
        AppState.relay.filter { it.configured }
            .distinctUntilChanged { a, b -> a.enabled == b.enabled && a.connected == b.connected && a.endpoint == b.endpoint }
            .collect { relay ->
                SettingsStore.update(this) { s ->
                    val pc = s.pcs.defaultPc
                    var next = if (relay.enabled != s.enabled) s.copy(enabled = relay.enabled) else s
                    if (pc != null && relay.endpoint.isNotEmpty()) {
                        val pcs = next.pcs.promote(pc.id, Endpoint.parse(relay.endpoint, 0)).touch(pc.id, System.currentTimeMillis())
                        next = next.copy(pcs = pcs)
                    }
                    next
                }
            }
    }

    companion object {
        private const val TAG = "KeyRelayService"
        const val ACTION_RETRY = "dev.keyrelay.action.RETRY"
        const val ACTION_TOGGLE = "dev.keyrelay.action.TOGGLE"

        fun start(context: Context) = send(context, null)

        fun retry(context: Context) = send(context, ACTION_RETRY)

        fun setEnabled(context: Context, on: Boolean) {
            HelperConnection.setEnabled(on)
            AppState.scope.launch { SettingsStore.update(context) { it.copy(enabled = on) } }
        }

        private fun send(context: Context, action: String?) {
            try {
                context.startForegroundService(Intent(context, RelayService::class.java).setAction(action))
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (Android 12+) is an IllegalStateException.
                Log.w(TAG, "relay service start refused", e)
            }
        }
    }
}
