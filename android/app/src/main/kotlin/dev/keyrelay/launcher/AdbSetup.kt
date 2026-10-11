package dev.keyrelay.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import dev.keyrelay.core.AdbConnectResult
import dev.keyrelay.core.ShellOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** One-time setup steps over wireless debugging: pair this app as an adb client and grant it the settings permission. */
object AdbSetup {
    /**
     * Meant to throw IOException after [ShellOutput.COMMAND_TIMEOUT_MS], but the limit does not cut a stalled pairing:
     * libadb 3.1.1 opens the pairing socket without a timeout (javap: `new Socket(host, port)`, no setSoTimeout), and
     * an interrupt does not unblock java.net.Socket I/O, so runInterruptible waits until the call returns (P4).
     */
    suspend fun pair(context: Context, host: String, port: Int, code: String): Boolean =
        withTimeoutOrNull(ShellOutput.COMMAND_TIMEOUT_MS) {
            runInterruptible(Dispatchers.IO) { AdbManager.get(context).pair(host, port, code) }
        } ?: throw IOException("adb pairing timed out")

    /** Wireless debugging must be on (the user is in its settings page during the wizard). */
    suspend fun grantSecureSettings(context: Context): Boolean {
        val adb = AdbManager.get(context)
        val connected = adb.reconnect(context)
        if (connected != AdbConnectResult.CONNECTED) throw IOException("wireless debugging is not reachable: $connected")
        adb.exec("pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}")
        return hasSecureSettingsPermission(context)
    }

    /** The start-up flow and the boot receiver need it to switch wireless debugging on. */
    fun hasSecureSettingsPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
}
