package dev.keyrelay.service

import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import dev.keyrelay.core.AdbConnectResult
import dev.keyrelay.core.HelperCommand
import dev.keyrelay.core.StartupFlow
import dev.keyrelay.core.StartupPorts
import dev.keyrelay.launcher.AdbManager
import dev.keyrelay.launcher.AdbSetup
import io.github.muntashirakon.adb.AdbPairingRequiredException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.resume

/** Android side of [StartupFlow]. Called on the main thread. */
class AppStartupPorts(private val context: Context) : StartupPorts {
    private val adb get() = AdbManager.get(context)
    private val main = Handler(Looper.getMainLooper())
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
    private var generationAtStart = 0
    private var settingsObserver: ContentObserver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun hasSecureSettingsPermission() = AdbSetup.hasSecureSettingsPermission(context)

    override suspend fun awaitWifi() {
        if (connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) return
        suspendCancellableCoroutine { cont ->
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runCatching { connectivity.unregisterNetworkCallback(this) }
                    if (cont.isActive) cont.resume(Unit)
                }
            }
            connectivity.registerNetworkCallback(wifi, callback)
            cont.invokeOnCancellation { runCatching { connectivity.unregisterNetworkCallback(callback) } }
        }
    }

    override fun putGlobal(key: String, value: Int) {
        Settings.Global.putInt(context.contentResolver, key, value)
    }

    override suspend fun connectAdb(): AdbConnectResult = adb.reconnect(context)

    override suspend fun startHelper() {
        generationAtStart = HelperConnection.generation.value
        val info = context.applicationInfo
        val start = HelperCommand.build(info.sourceDir, info.nativeLibraryDir, listOf("--app-uid=${Process.myUid()}"))
        // EVIOCGRAB is exclusive: a leftover helper would keep the keyboard and the new one could not grab it.
        val result = adb.exec("kill \$(pidof ${HelperCommand.NICE_NAME}) 2>/dev/null; $start")
        if (result.exitCode != 0) throw IOException("helper start command failed (${result.exitCode}): ${result.output}")
    }

    override suspend fun awaitAttach(timeoutMs: Long) =
        withTimeoutOrNull(timeoutMs) { HelperConnection.generation.first { it > generationAtStart } } != null

    override fun helperAlive() = HelperConnection.isAlive()

    override fun registerWakeups(onWake: () -> Unit) {
        settingsObserver = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = onWake()
        }.also {
            context.contentResolver.registerContentObserver(Settings.Global.getUriFor(StartupFlow.ADB_WIFI_ENABLED), false, it)
        }
        val registeredOn = connectivity.activeNetwork?.networkHandle
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            // Registration replays the network we are already on; only a reconnect counts as a wake-up.
            override fun onAvailable(network: Network) {
                if (isNewNetwork(registeredOn, network.networkHandle)) main.post(onWake)
            }
        }.also { connectivity.registerNetworkCallback(wifi, it) }
    }

    override fun unregisterWakeups() {
        settingsObserver?.let { context.contentResolver.unregisterContentObserver(it) }
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
        settingsObserver = null
        networkCallback = null
    }

    // IOException only: runCatching would also swallow the flight's cancellation.
    override suspend fun helperLogTail(): String = try {
        adb.exec("tail -n $LOG_LINES ${HelperCommand.LOG_PATH}").output
    } catch (e: IOException) {
        ""
    }

    private companion object {
        const val LOG_LINES = 20
    }
}

/**
 * libadb's ways of failing (spec "Baslatma" step 2): false = connect phase timed out, InterruptedException = mDNS
 * window ran out (not a real interrupt, so the flag is not restored), IOException = no address or early end.
 */
internal fun classifyConnect(connect: () -> Boolean): AdbConnectResult = try {
    if (connect()) AdbConnectResult.CONNECTED else AdbConnectResult.NOT_STARTED
} catch (e: AdbPairingRequiredException) {
    AdbConnectResult.PAIRING_REQUIRED
} catch (e: InterruptedException) {
    AdbConnectResult.NOT_STARTED
} catch (e: IOException) {
    AdbConnectResult.NOT_STARTED
}

internal fun isNewNetwork(registeredOn: Long?, available: Long) = registeredOn != available
