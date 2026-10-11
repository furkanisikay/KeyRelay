package dev.keyrelay.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class KeyRepeatTest {
    private val r = KeyRepeat().apply { delayMs = 500; intervalMs = 50 }
    private val a = 30
    private val b = 48
    private val leftShift = 42

    @Test
    fun onKey_heldWhileGrabbed_repeatsAfterDelayThenEveryInterval() {
        r.onKey(a, 1, grabbed = true, nowMs = 0)
        assertEquals(KeyRepeat.NONE, r.due(499))
        assertEquals(a, r.due(500))
        assertEquals(KeyRepeat.NONE, r.due(549))
        assertEquals(a, r.due(550))
    }

    @Test
    fun onKey_release_stopsAtOnce() {
        r.onKey(a, 1, grabbed = true, nowMs = 0)
        r.onKey(a, 0, grabbed = true, nowMs = 520)
        assertEquals(KeyRepeat.NONE, r.due(10_000))
        assertEquals(Long.MAX_VALUE, r.nextMs)
    }

    @Test
    fun onKey_modifierLockOrMediaKey_neverRepeats() {
        for (code in intArrayOf(leftShift, 29, 56, 125, 58, 69, 70, 99, 115, 113)) {
            r.onKey(code, 1, grabbed = true, nowMs = 0)
            assertEquals(KeyRepeat.NONE, r.due(10_000), "code $code")
        }
    }

    @Test
    fun onKey_newPress_stopsPreviousAndRepeatsTheNewKey() {
        r.onKey(a, 1, grabbed = true, nowMs = 0)
        r.onKey(b, 1, grabbed = true, nowMs = 600)
        assertEquals(KeyRepeat.NONE, r.due(1_099))
        assertEquals(b, r.due(1_100))
    }

    @Test
    fun onKey_modifierPressedDuringRepeat_stopsIt_andOldKeysReleaseChangesNothing() {
        r.onKey(a, 1, grabbed = true, nowMs = 0)
        r.onKey(leftShift, 1, grabbed = true, nowMs = 600)
        assertEquals(KeyRepeat.NONE, r.due(5_000))
        r.onKey(b, 1, grabbed = true, nowMs = 5_000)
        r.onKey(a, 0, grabbed = true, nowMs = 5_010)
        assertEquals(b, r.due(5_500))
    }

    @Test
    fun onKey_notGrabbed_doesNotRepeat() {
        r.onKey(a, 1, grabbed = false, nowMs = 0)
        assertEquals(KeyRepeat.NONE, r.due(10_000))
    }

    @Test
    fun stop_ungrabOrDisconnect_endsRepeat() {
        r.onKey(a, 1, grabbed = true, nowMs = 0)
        assertEquals(a, r.due(500))
        r.stop()
        assertEquals(KeyRepeat.NONE, r.due(10_000))
    }

    @Test
    fun due_lateWakeUp_sendsOneRepeatNotABurst() {
        r.onKey(a, 1, grabbed = true, nowMs = 0)
        assertEquals(a, r.due(2_000))
        assertEquals(KeyRepeat.NONE, r.due(2_049))
        assertEquals(2_050L, r.nextMs)
    }
}
