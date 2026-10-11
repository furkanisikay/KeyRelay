package dev.keyrelay.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeyPulseTest {
    @Test
    fun allow_firstPress_passes() = assertTrue(KeyPulse().allow(0))

    @Test
    fun allow_fastTyping_atMostFourPerSecond() {
        val pulse = KeyPulse()
        val passed = (0L until 1_000L step 30).count { pulse.allow(it) }
        assertEquals(4, passed)
    }

    @Test
    fun allow_heldKeyRepeat_staysWithinFourPerSecond() {
        // Ten seconds of auto-repeat at 30 per second still makes at most 40 binder calls.
        val pulse = KeyPulse()
        assertTrue((0L until 10_000L step 33).count { pulse.allow(it) } <= 40)
    }

    @Test
    fun signal_keysHidden_neverCarriesTheCode() =
        (0 until 0x300).forEach { assertEquals(KeyPulse.NO_KEY, KeyPulse.signal(it, showKeys = false)) }

    @Test
    fun signal_keysShown_carriesTheCode() = assertEquals(30, KeyPulse.signal(30, showKeys = true))

    @Test
    fun next_appNotWatching_sendsNothingEvenWithKeysShown() {
        val pulse = KeyPulse()
        (0L until 5_000L step 300).forEach { assertEquals(KeyPulse.NOTHING, pulse.next(30, 1, it, watching = false, showKeys = true)) }
    }

    @Test
    fun next_watchingKeysHidden_sendsOnlyTheBlankSignal() =
        assertEquals(KeyPulse.NO_KEY, KeyPulse().next(30, 1, 0, watching = true, showKeys = false))

    @Test
    fun next_watchingKeysShown_sendsTheCode_onPressOnly() {
        val pulse = KeyPulse()
        assertEquals(KeyPulse.NOTHING, pulse.next(30, 0, 0, watching = true, showKeys = true))
        assertEquals(KeyPulse.NOTHING, pulse.next(30, 2, 0, watching = true, showKeys = true))
        assertEquals(30, pulse.next(30, 1, 0, watching = true, showKeys = true))
    }

    @Test
    fun next_notWatching_doesNotUseUpTheThrottle() {
        val pulse = KeyPulse()
        pulse.next(30, 1, 0, watching = false, showKeys = false)
        assertEquals(KeyPulse.NO_KEY, pulse.next(30, 1, 1, watching = true, showKeys = false))
    }

    @Test
    fun allow_pressExactlyAtGap_passes() {
        val pulse = KeyPulse()
        pulse.allow(1_000)
        assertTrue(pulse.allow(1_000 + KeyPulse.MIN_GAP_MS))
    }
}
