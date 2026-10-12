package dev.keyrelay.service

import dev.keyrelay.core.StartupState

/** One answer to "what is KeyRelay doing", shared by the status screen, the notification and the tile. */
enum class StatusKind { STARTING, NO_WIFI, APPROVAL, ERROR, NOT_PAIRED, REPAIR, PAUSED, CONNECTING, PC_NOT_READY, READY, RELAYING }

fun statusKind(relay: RelayStatus, startup: StartupState, hasPc: Boolean): StatusKind = when {
    startup is StartupState.Failed -> StatusKind.ERROR
    startup is StartupState.AwaitingApproval -> StatusKind.APPROVAL
    startup is StartupState.WaitingForWifi -> StatusKind.NO_WIFI
    !relay.attached -> StatusKind.STARTING
    !hasPc -> StatusKind.NOT_PAIRED
    relay.rejectReason != 0 -> StatusKind.REPAIR
    !relay.enabled -> StatusKind.PAUSED
    !relay.connected -> StatusKind.CONNECTING
    relay.grabbed -> StatusKind.RELAYING
    !relay.pcReady -> StatusKind.PC_NOT_READY
    else -> StatusKind.READY
}
