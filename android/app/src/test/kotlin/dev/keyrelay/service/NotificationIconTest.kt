package dev.keyrelay.service

import dev.keyrelay.R
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NotificationIconTest {
    @Test
    fun iconRes_eachStateGroup_hasItsOwnShape() {
        assertEquals(R.drawable.ic_stat_keyrelay, Notifications.iconRes(StatusKind.RELAYING))
        assertEquals(R.drawable.ic_stat_ready, Notifications.iconRes(StatusKind.READY))
        assertEquals(R.drawable.ic_stat_paused, Notifications.iconRes(StatusKind.PAUSED))
        for (k in listOf(StatusKind.STARTING, StatusKind.NO_WIFI, StatusKind.CONNECTING, StatusKind.PC_NOT_READY)) {
            assertEquals(R.drawable.ic_stat_waiting, Notifications.iconRes(k), k.name)
        }
        for (k in listOf(StatusKind.ERROR, StatusKind.APPROVAL, StatusKind.NOT_PAIRED, StatusKind.REPAIR)) {
            assertEquals(R.drawable.ic_stat_alert, Notifications.iconRes(k), k.name)
        }
    }

    @Test
    fun iconRes_fiveShapes_allDifferent() =
        assertEquals(5, StatusKind.entries.map(Notifications::iconRes).toSet().size)
}
