package dev.keyrelay.ui

import dev.keyrelay.core.ConnectionConfig
import dev.keyrelay.settings.AppSettings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WizardSettingsTest {
    @Test
    fun settingsChoices_defaults_areOffered() {
        assertTrue(ConnectionConfig.DEFAULT_PING_INTERVAL_MS in SettingsChoices.PING_MS)
        assertTrue(ConnectionConfig.DEFAULT_PONG_TIMEOUT_MS in SettingsChoices.PONG_MS)
        assertTrue(ConnectionConfig.DEFAULT_SEND_TIMEOUT_MS in SettingsChoices.SEND_MS)
    }

    @Test
    fun settingsChoices_everyPing_hasALongerPong() = assertTrue(SettingsChoices.PING_MS.all { p -> SettingsChoices.PONG_MS.any { it > p } })

    @Test
    fun withTimes_validPair_isApplied() {
        val s = AppSettings().withTimes(ping = 2_000).withTimes(pong = 5_000)
        assertEquals(2_000L, s.pingIntervalMs)
        assertEquals(5_000L, s.pongTimeoutMs)
    }

    @Test
    fun withTimes_quickTapsMakeAnInvalidPair_storedPairKept() {
        // Ping 2 / pong 20, then ping 10 and pong 5 tapped before the screen redraws.
        val stored = AppSettings(pingIntervalMs = 2_000, pongTimeoutMs = 20_000)
        val afterPing = stored.withTimes(ping = 10_000)
        val afterPong = afterPing.withTimes(pong = 5_000)
        assertEquals(10_000L, afterPong.pingIntervalMs)
        assertEquals(20_000L, afterPong.pongTimeoutMs)
        assertEquals(stored, stored.withTimes(ping = 20_000))
    }

    @Test
    fun isValidCode_sixAsciiDigits_accepted() = assertTrue(AdbPairing.isValidCode("482931"))

    @Test
    fun isValidCode_wrongLengthOrLetters_rejected() {
        assertFalse(AdbPairing.isValidCode(""))
        assertFalse(AdbPairing.isValidCode("48293"))
        assertFalse(AdbPairing.isValidCode("4829310"))
        assertFalse(AdbPairing.isValidCode("48a931"))
    }

    @Test
    fun isValidCode_nonAsciiDigits_rejected() {
        // Arabic-Indic and full-width digits pass Char.isDigit but adbd compares the code byte for byte.
        for (zero in intArrayOf(0x0660, 0xFF10)) {
            assertFalse(AdbPairing.isValidCode("482931".map { (it - '0' + zero).toChar() }.joinToString("")))
        }
    }
}
