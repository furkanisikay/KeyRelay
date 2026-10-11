package dev.keyrelay.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class StartupFlowTest {
    private class FakePorts : StartupPorts {
        val calls = mutableListOf<String>()
        var permission = true
        val connectResults = ArrayDeque<AdbConnectResult>()
        var attach = true
        var alive = true
        var startError: Exception? = null
        var connectGate: CompletableDeferred<Unit>? = null
        var connectIgnoresCancel = false
        var registerError: Exception? = null
        var wifiGate: CompletableDeferred<Unit>? = null
        var wake: (() -> Unit)? = null

        override fun hasSecureSettingsPermission() = permission
        override suspend fun awaitWifi() { calls += "wifi"; wifiGate?.await() }
        override fun putGlobal(key: String, value: Int) { calls += "put $key=$value" }
        override suspend fun connectAdb(): AdbConnectResult {
            calls += "connect"
            connectGate?.let { if (connectIgnoresCancel) withContext(NonCancellable) { it.await() } else it.await() }
            return connectResults.removeFirstOrNull() ?: AdbConnectResult.CONNECTED
        }
        override suspend fun startHelper() { calls += "start"; startError?.let { throw it } }
        override suspend fun awaitAttach(timeoutMs: Long): Boolean { calls += "attach"; return attach }
        override fun helperAlive() = alive
        override fun registerWakeups(onWake: () -> Unit) { registerError?.let { throw it }; calls += "register"; wake = onWake }
        override fun unregisterWakeups() { calls += "unregister"; wake = null }
        override suspend fun helperLogTail() = "log tail"
    }

    private val ports = FakePorts()
    private val putOn = listOf("put development_settings_enabled=1", "put adb_enabled=1", "put adb_wifi_enabled=1")
    private val success = listOf("unregister", "wifi") + putOn + listOf("connect", "start", "attach", "unregister", "put adb_wifi_enabled=0")

    // Not backgroundScope: advanceUntilIdle stops once only background tasks remain.
    private fun TestScope.flow() = StartupFlow(ports, this) { testScheduler.currentTime }
    private fun puts() = ports.calls.count { it == "put adb_wifi_enabled=1" }

    @Test
    fun onServiceStarted_helperNotAttached_runsStepOneThenZeroAndRuns() = runTest {
        val f = flow()
        f.onServiceStarted(helperAttached = false)
        advanceUntilIdle()
        assertEquals(success, ports.calls)
        assertEquals(StartupState.Running, f.state.value)
    }

    @Test
    fun onServiceStarted_helperAttached_doesNothing() = runTest {
        val f = flow()
        f.onServiceStarted(helperAttached = true)
        advanceUntilIdle()
        assertTrue(ports.calls.isEmpty())
        assertEquals(StartupState.Running, f.state.value)
    }

    @Test
    fun attempt_wirelessDebuggingNotStarted_awaitsApprovalWithObserverAfterZero() = runTest {
        ports.connectResults += AdbConnectResult.NOT_STARTED
        val f = flow()
        f.onServiceStarted(false)
        advanceUntilIdle()
        assertEquals(listOf("unregister", "wifi") + putOn + listOf("connect", "unregister", "put adb_wifi_enabled=0", "register"), ports.calls)
        assertEquals(StartupState.AwaitingApproval, f.state.value)
    }

    @Test
    fun wake_whileAwaitingApproval_triesWithoutPut() = runTest {
        ports.connectResults += AdbConnectResult.NOT_STARTED
        val f = flow()
        f.onServiceStarted(false)
        advanceUntilIdle()
        ports.calls.clear()
        ports.wake!!()
        advanceUntilIdle()
        assertEquals(listOf("unregister", "wifi", "connect", "start", "attach", "unregister", "put adb_wifi_enabled=0"), ports.calls)
        assertEquals(StartupState.Running, f.state.value)
    }

    @Test
    fun wake_attemptFailsAgain_registersOnceAndPutsOnlyInStepOne() = runTest {
        ports.connectResults += listOf(AdbConnectResult.NOT_STARTED, AdbConnectResult.NOT_STARTED)
        val f = flow()
        f.onServiceStarted(false)
        advanceUntilIdle()
        ports.wake!!()
        advanceUntilIdle()
        assertEquals(1, puts())
        assertEquals(StartupState.AwaitingApproval, f.state.value)
        assertEquals("register", ports.calls.last())
    }

    @Test
    fun attempt_pairingRequired_failsWithoutObserver() = runTest {
        ports.connectResults += AdbConnectResult.PAIRING_REQUIRED
        val f = flow()
        f.onServiceStarted(false)
        advanceUntilIdle()
        assertEquals(StartupState.Failed(StartupFailure.PAIRING_REQUIRED, ""), f.state.value)
        assertTrue("register" !in ports.calls)
    }

    @Test
    fun attempt_helperNeverAttaches_failsWithLogTail() = runTest {
        ports.attach = false
        val f = flow()
        f.onServiceStarted(false)
        advanceUntilIdle()
        assertEquals(StartupState.Failed(StartupFailure.HELPER_NOT_STARTED, "log tail"), f.state.value)
        assertEquals("put adb_wifi_enabled=0", ports.calls.last())
    }

    @Test
    fun attempt_startCommandThrows_failsAndStillWritesZero() = runTest {
        ports.startError = IOException("adb command timed out")
        val f = flow()
        f.onServiceStarted(false)
        advanceUntilIdle()
        assertEquals(StartupState.Failed(StartupFailure.HELPER_NOT_STARTED, "adb command timed out"), f.state.value)
        assertEquals("put adb_wifi_enabled=0", ports.calls.last())
    }

    @Test
    fun attempt_portThrowsRuntimeException_failsInsteadOfPropagating() = runTest {
        ports.startError = IllegalStateException("Already connected")
        val f = flow()
        f.onServiceStarted(false)
        advanceUntilIdle()
        assertEquals(StartupState.Failed(StartupFailure.HELPER_NOT_STARTED, "Already connected"), f.state.value)
        assertEquals("put adb_wifi_enabled=0", ports.calls.last())
    }

    @Test
    fun attempt_noPermission_failsWithoutTouchingSettings() = runTest {
        ports.permission = false
        val f = flow()
        f.onServiceStarted(false)
        advanceUntilIdle()
        assertEquals(StartupState.Failed(StartupFailure.NO_PERMISSION, ""), f.state.value)
        assertTrue(ports.calls.none { it.startsWith("put") })
    }

    @Test
    fun attempt_noWifiYet_waitsWithoutPut() = runTest {
        ports.wifiGate = CompletableDeferred()
        val f = flow()
        f.onServiceStarted(false)
        runCurrent()
        assertEquals(StartupState.WaitingForWifi, f.state.value)
        assertEquals(0, puts())
        ports.wifiGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(StartupState.Running, f.state.value)
    }

    @Test
    fun onHelperDied_backsOff5_20_60ThenStops() = runTest {
        val f = flow()
        f.onServiceStarted(false)
        advanceUntilIdle()
        for (delayMs in listOf(5_000L, 20_000L, 60_000L)) {
            val before = puts()
            f.onHelperDied("boom")
            advanceTimeBy(delayMs - 1)
            runCurrent()
            assertEquals(before, puts())
            advanceTimeBy(1)
            advanceUntilIdle()
            assertEquals(before + 1, puts())
        }
        f.onHelperDied("boom")
        advanceUntilIdle()
        assertEquals(StartupState.Failed(StartupFailure.HELPER_CRASH_LOOP, "boom"), f.state.value)
        assertEquals(4, puts())
    }

    @Test
    fun onHelperDied_afterTenStableMinutes_restartsCounter() = runTest {
        val f = flow()
        f.onServiceStarted(false)
        advanceUntilIdle()
        repeat(3) { f.onHelperDied("x"); advanceUntilIdle() }
        advanceTimeBy(StartupFlow.STABLE_MS)
        f.onHelperDied("x")
        assertEquals(StartupState.Restarting(1, 5_000L), f.state.value)
    }

    @Test
    fun onRetry_afterCrashLoop_startsAgainWithPut() = runTest {
        val f = flow()
        f.onServiceStarted(false)
        advanceUntilIdle()
        repeat(4) { f.onHelperDied("x"); advanceUntilIdle() }
        val before = puts()
        f.onRetry()
        advanceUntilIdle()
        assertEquals(before + 1, puts())
        assertEquals(StartupState.Running, f.state.value)
        f.onHelperDied("x")
        assertEquals(StartupState.Restarting(1, 5_000L), f.state.value)
    }

    @Test
    fun onRetryTwice_connectIgnoresCancel_oldZeroBeforeNewOnWithoutOverlap() = runTest {
        val gate = CompletableDeferred<Unit>()
        ports.connectGate = gate
        ports.connectIgnoresCancel = true
        val f = flow()
        f.onServiceStarted(false)
        runCurrent()
        ports.connectGate = null
        f.onRetry()
        runCurrent()
        f.onRetry()
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        val on = "put adb_wifi_enabled=1"
        val off = "put adb_wifi_enabled=0"
        assertEquals(listOf(on, off, on, off), ports.calls.filter { it == on || it == off }, ports.calls.toString())
        assertEquals(StartupState.Running, f.state.value)
    }

    @Test
    fun scopeCancelled_connectIgnoresCancel_skipsStartAndStillWritesZero() = runTest {
        val gate = CompletableDeferred<Unit>()
        ports.connectGate = gate
        ports.connectIgnoresCancel = true
        val scope = TestScope(testScheduler)
        val f = StartupFlow(ports, scope) { testScheduler.currentTime }
        f.onServiceStarted(false)
        runCurrent()
        scope.cancel()
        gate.complete(Unit)
        runCurrent()
        assertTrue("start" !in ports.calls, ports.calls.toString())
        assertEquals("put adb_wifi_enabled=0", ports.calls.last())
    }

    @Test
    fun attempt_registerWakeupsThrows_failsInsteadOfStuck() = runTest {
        ports.connectResults += AdbConnectResult.NOT_STARTED
        ports.registerError = IllegalStateException("receiver leak")
        val f = flow()
        f.onServiceStarted(false)
        advanceUntilIdle()
        assertEquals(StartupState.Failed(StartupFailure.HELPER_NOT_STARTED, "receiver leak"), f.state.value)
    }

    @Test
    fun onRetry_duringFlight_closesFlightBeforeStartingOver() = runTest {
        ports.connectGate = CompletableDeferred()
        val f = flow()
        f.onServiceStarted(false)
        runCurrent()
        ports.connectGate = null
        f.onRetry()
        advanceUntilIdle()
        val firstZero = ports.calls.indexOf("put adb_wifi_enabled=0")
        val secondOn = ports.calls.lastIndexOf("put adb_wifi_enabled=1")
        assertTrue(firstZero in 0 until secondOn, ports.calls.toString())
        assertEquals(StartupState.Running, f.state.value)
    }

    @Test
    fun onServiceStartedAndDeath_duringFlight_areIgnored() = runTest {
        ports.connectGate = CompletableDeferred()
        val f = flow()
        f.onServiceStarted(false)
        runCurrent()
        f.onServiceStarted(false)
        f.onHelperDied("x")
        ports.connectGate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, puts())
        assertEquals(StartupState.Running, f.state.value)
    }

    @Test
    fun attempt_helperDeadAtEnd_schedulesRestart() = runTest {
        ports.alive = false
        val f = flow()
        f.onServiceStarted(false)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(StartupState.Restarting(1, 5_000L), f.state.value)
    }

    @Test
    fun scopeCancelled_duringFlight_stillWritesZero() = runTest {
        ports.connectGate = CompletableDeferred()
        val scope = TestScope(testScheduler)
        val f = StartupFlow(ports, scope) { testScheduler.currentTime }
        f.onServiceStarted(false)
        runCurrent()
        scope.cancel()
        runCurrent()
        assertEquals("put adb_wifi_enabled=0", ports.calls.last())
    }
}
