package dev.keyrelay.service

import dev.keyrelay.core.StartupFailure
import dev.keyrelay.core.StartupState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class StatusKindTest {
    private val live = RelayStatus(attached = true, configured = true, connected = true, pcReady = true, pcName = "homepc")

    @Test
    fun statusKind_startupFailure_winsOverEverything() =
        assertEquals(StatusKind.ERROR, statusKind(live, StartupState.Failed(StartupFailure.HELPER_NOT_STARTED, ""), true))

    @Test
    fun statusKind_awaitingApproval_isApproval() = assertEquals(StatusKind.APPROVAL, statusKind(RelayStatus(), StartupState.AwaitingApproval, true))

    @Test
    fun statusKind_waitingForWifi_isNoWifi() = assertEquals(StatusKind.NO_WIFI, statusKind(RelayStatus(), StartupState.WaitingForWifi, true))

    @Test
    fun statusKind_noHelperYet_isStarting() = assertEquals(StatusKind.STARTING, statusKind(RelayStatus(), StartupState.Restarting(1, 5_000), true))

    @Test
    fun statusKind_noPc_isNotPaired() = assertEquals(StatusKind.NOT_PAIRED, statusKind(live, StartupState.Running, false))

    @Test
    fun statusKind_rejected_isRepair() = assertEquals(StatusKind.REPAIR, statusKind(live.copy(rejectReason = 2), StartupState.Running, true))

    @Test
    fun statusKind_disabled_isPausedEvenWhenGrabbedFlagLags() =
        assertEquals(StatusKind.PAUSED, statusKind(live.copy(enabled = false, grabbed = true), StartupState.Running, true))

    @Test
    fun statusKind_notConnected_isConnecting() = assertEquals(StatusKind.CONNECTING, statusKind(live.copy(connected = false), StartupState.Running, true))

    @Test
    fun statusKind_grabbed_isRelaying() = assertEquals(StatusKind.RELAYING, statusKind(live.copy(grabbed = true), StartupState.Running, true))

    @Test
    fun statusKind_pcNotReady_isPcNotReady() = assertEquals(StatusKind.PC_NOT_READY, statusKind(live.copy(pcReady = false), StartupState.Running, true))

    @Test
    fun statusKind_connectedIdle_isReady() = assertEquals(StatusKind.READY, statusKind(live, StartupState.Running, true))
}
