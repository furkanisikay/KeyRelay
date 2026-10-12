package dev.keyrelay.service

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import dev.keyrelay.core.Frame
import dev.keyrelay.core.PairedPc
import dev.keyrelay.core.PairingInfo
import dev.keyrelay.core.RelayClient
import dev.keyrelay.core.TlsDialer
import dev.keyrelay.core.makeDefault
import dev.keyrelay.core.upsert
import dev.keyrelay.settings.SettingsStore
import dev.keyrelay.settings.TokenCipher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Base64

sealed interface PairResult {
    data class Paired(val pcName: String, val id: String) : PairResult
    data class Rejected(val reason: Int) : PairResult
    data class Unreachable(val detail: String) : PairResult
    /** The PC answered with a key other than the one in the QR code. */
    data object KeyMismatch : PairResult
    /** The PC accepted (and spent the code) but the token could not be stored here. */
    data object NotSaved : PairResult
}

// 64 UTF-16 chars stay far below the PAIR frame's byte limit even for 4-byte characters.
private const val MAX_NAME_CHARS = 64
private const val TAG = "KeyRelayPairing"

internal fun pairingDeviceName(deviceName: String?, model: String) = (deviceName?.takeIf { it.isNotBlank() } ?: model).take(MAX_NAME_CHARS)

/**
 * Pairing calls outlive the confirmation dialog (rotation, activity recreation): one job per QR code, and a dialog
 * shown again for the same code re-attaches to it instead of spending the one-time code twice. A failed job removes
 * itself so the user can try again; a successful one is kept only until its dialog closes, or [KEEP_DONE_MS] without
 * one, so an old QR scanned later goes to the network again. Main thread only.
 */
object PairingJobs {
    private const val KEEP_DONE_MS = 60_000L
    private val jobs = HashMap<String, Deferred<PairResult>>()

    private fun key(info: PairingInfo) = Base64.getUrlEncoder().encodeToString(info.code)

    /** The running or recently successful job for this code, if any. */
    fun find(info: PairingInfo): Deferred<PairResult>? = jobs[key(info)]

    fun start(context: Context, info: PairingInfo): Deferred<PairResult> {
        find(info)?.let { return it }
        val k = key(info)
        val app = context.applicationContext
        lateinit var job: Deferred<PairResult>
        job = AppState.scope.async {
            // Any failure ends as a result: the job never dies with an exception the dialog cannot show.
            val result = try {
                PcPairing.pair(app, info)
            } catch (e: Exception) {
                Log.w(TAG, "pairing failed", e)
                PairResult.Unreachable(e.toString())
            }
            if (result !is PairResult.Paired) {
                jobs.remove(k)
            } else {
                // Not a child of this job: the result must be delivered now, not after the wait.
                AppState.scope.launch {
                    delay(KEEP_DONE_MS)
                    if (jobs[k] === job) jobs.remove(k)
                }
            }
            result
        }
        jobs[k] = job
        return job
    }

    /** The dialog for this code is closed for good. */
    fun forget(info: PairingInfo) {
        jobs.remove(key(info))
    }
}

object PcPairing {
    // NonCancellable: the PC spends the one-time code on PAIRED, so leaving the screen must not drop the token unsaved.
    /** Pairs with the PC of a scanned or opened link; the new PC becomes the default. Only after the user confirmed. */
    suspend fun pair(context: Context, info: PairingInfo): PairResult = withContext(Dispatchers.IO + NonCancellable) {
        val name = pairingDeviceName(Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME), Build.MODEL)
        val reply = try {
            RelayClient.pair(info.endpoints, info.spkiSha256, info.code, name)
        } catch (e: TlsDialer.PinMismatchException) {
            return@withContext PairResult.KeyMismatch
        } catch (e: IOException) {
            // Logged here: the screen shows a plain message without this detail.
            Log.w(TAG, "pc unreachable", e)
            return@withContext PairResult.Unreachable(e.message.orEmpty())
        }
        when (reply) {
            is Frame.Paired -> {
                // Keystore failures (ProviderException, GeneralSecurityException) and disk errors must end as a result, not a crash.
                runCatching {
                    val pc = PairedPc(reply.deviceId, info.pcName, info.endpoints, info.spkiSha256, TokenCipher.seal(reply.token), System.currentTimeMillis(), true)
                    SettingsStore.update(context) { it.copy(pcs = it.pcs.upsert(pc).makeDefault(pc.id)) }
                    pc.id
                }.fold({ PairResult.Paired(info.pcName, it) }, { e ->
                    Log.w(TAG, "pairing token not saved", e)
                    PairResult.NotSaved
                })
            }
            is Frame.Reject -> PairResult.Rejected(reply.reason)
            else -> PairResult.Unreachable("unexpected reply")
        }
    }
}
