package dev.keyrelay.service

import dev.keyrelay.helper.HelperStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RelayStatusTest {
    @Test
    fun toRelayStatus_unsetStringsAndArrays_becomeEmpty() {
        val s = HelperStatus().toRelayStatus()
        assertTrue(s.attached)
        assertEquals("", s.pcName)
        assertEquals("", s.lastError)
        assertEquals(emptyList<String>(), s.keyboards)
        assertTrue(s.enabled)
    }

    @Test
    fun toRelayStatus_onlyRoundTripDiffers_equalStatus() {
        // A ping must not change the status every screen and the notification model listen to (AppState.rtt carries it).
        val a = HelperStatus().apply { connected = true; rttMs = 12 }
        val b = HelperStatus().apply { connected = true; rttMs = 31 }
        assertEquals(a.toRelayStatus(), b.toRelayStatus())
    }

    @Test
    fun toRelayStatus_copiesFlagsAndLists() {
        val h = HelperStatus().apply {
            configured = true
            connected = true
            grabbed = true
            overriding = true
            keyboards = arrayOf("MX Keys Mini Keyboard")
            rejectReason = 2
        }
        val s = h.toRelayStatus()
        assertEquals(listOf(true, true, true, true), listOf(s.configured, s.connected, s.grabbed, s.override))
        assertEquals(listOf("MX Keys Mini Keyboard"), s.keyboards)
        assertEquals(2, s.rejectReason)
    }
}
