package dev.keyrelay.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RelayStateMachineTest {
    private class Recorder : RelayStateMachine.Output {
        val log = mutableListOf<String>()
        override fun sendKey(code: Int, value: Int) { log += "key $code $value" }
        override fun sendSas() { log += "sas" }
        override fun sendLock() { log += "lock" }
        override fun setGrab(on: Boolean) { log += "grab $on" }
        override fun onToggled(enabled: Boolean, override: Boolean) { log += "toggled enabled=$enabled override=$override" }
    }

    private val out = Recorder()
    private val m = RelayStateMachine(out)

    private fun ready(targetFront: Boolean) {
        m.setConnected(true)
        m.setPcReady(true)
        m.onForeground(targetFront, targetEvent = true)
    }

    private fun keys(vararg events: Pair<Int, Int>) = events.forEach { (code, value) -> m.onKey(code, value) }
    private fun combo(key: Int = RelayStateMachine.KEY_GRAVE, ctrl: Int = 29, alt: Int = 56) = keys(ctrl to 1, alt to 1, key to 1, key to 0, alt to 0, ctrl to 0)
    private fun fresh() = out.log.clear()

    @Test
    fun onForeground_targetFrontAndReady_grabs() {
        ready(targetFront = true)
        assertEquals(listOf("grab true"), out.log)
    }

    @Test
    fun onForeground_targetLeavesWhileKeyHeld_releasesAfterKeyUp() {
        ready(true); fresh()
        m.onKey(30, 1)
        m.onForeground(false, targetEvent = true)
        assertEquals(listOf("key 30 1"), out.log)
        m.onKey(30, 0)
        assertEquals(listOf("key 30 1", "key 30 0", "grab false"), out.log)
    }

    @Test
    fun setConnected_dropWhileKeyHeld_keyUpStillSentThenUngrab() {
        ready(true); fresh()
        m.onKey(56, 1)
        m.setConnected(false)
        m.onKey(56, 0)
        assertEquals(listOf("key 56 1", "key 56 0", "grab false"), out.log)
    }

    @Test
    fun onKey_toggleWhileTargetFront_disablesAndHidesToggleKey() {
        ready(true); fresh()
        combo()
        assertEquals(
            listOf("key 29 1", "key 56 1", "toggled enabled=false override=false", "key 56 0", "key 29 0", "grab false"),
            out.log,
        )
    }

    @Test
    fun onKey_toggleTwiceTargetNotFront_secondPressReturnsToTablet() {
        ready(false); fresh()
        combo()
        assertEquals(listOf("toggled enabled=true override=true", "grab true"), out.log)
        fresh()
        combo()
        assertEquals(
            listOf("key 29 1", "key 56 1", "toggled enabled=true override=false", "key 56 0", "key 29 0", "grab false"),
            out.log,
        )
        assertFalse(m.grabbed)
    }

    @Test
    fun onKey_toggleTwiceTargetFrontDisabled_secondPressReturnsToTablet() {
        ready(true)
        m.setEnabled(false)
        assertFalse(m.grabbed)
        combo()
        assertTrue(m.grabbed)
        combo()
        assertFalse(m.grabbed)
    }

    @Test
    fun onKey_ctrlAltDelWhileGrabbed_sendsSasInsteadOfDelete() {
        ready(true); fresh()
        keys(29 to 1, 56 to 1, 111 to 1, 111 to 2, 111 to 0, 56 to 0, 29 to 0)
        assertEquals(listOf("key 29 1", "key 56 1", "sas", "key 56 0", "key 29 0"), out.log)
    }

    @Test
    fun releaseHeld_keyHeldWhenNodeLost_sendsKeyUpAndUngrabs() {
        ready(true); fresh()
        keys(29 to 1, 30 to 1)
        m.onForeground(targetForeground = false, targetEvent = true)
        m.releaseHeld()
        assertEquals(listOf("key 29 1", "key 30 1", "key 29 0", "key 30 0", "grab false"), out.log)
    }

    @Test
    fun onKey_winLWhileGrabbed_sendsLockInsteadOfL() {
        ready(true); fresh()
        keys(125 to 1, 38 to 1, 38 to 0, 125 to 0)
        assertEquals(listOf("key 125 1", "lock", "key 125 0"), out.log)
    }

    @Test
    fun onKey_ctrlAltDelNotGrabbed_sendsNothing() {
        keys(29 to 1, 56 to 1, 111 to 1, 111 to 0, 56 to 0, 29 to 0)
        assertTrue(out.log.isEmpty())
    }

    @Test
    fun onForeground_nonTargetEventWithOverride_keepsGrab() {
        ready(false)
        combo(); fresh()
        m.onForeground(false, targetEvent = false)
        assertTrue(out.log.isEmpty())
        assertTrue(m.grabbed)
    }

    @Test
    fun onForeground_otherAppWhileOverride_keepsOverrideAndGrab() {
        ready(true)
        m.onForeground(false, targetEvent = false)
        combo(); fresh()
        m.onForeground(true, targetEvent = false)
        m.onForeground(false, targetEvent = false)
        assertTrue(out.log.isEmpty())
        assertTrue(m.override)
        assertTrue(m.grabbed)
        combo()
        assertFalse(m.override)
        assertFalse(m.grabbed)
    }

    @Test
    fun onForeground_targetEventWithOverride_clearsOverride() {
        ready(false)
        combo(); fresh()
        m.onForeground(true, targetEvent = true)
        assertEquals(listOf("toggled enabled=true override=false"), out.log)
        assertTrue(m.grabbed)
        m.onForeground(false, targetEvent = true)
        assertFalse(m.grabbed)
    }

    @Test
    fun setPcReady_false_releasesGrab() {
        ready(true); fresh()
        m.setPcReady(false)
        assertEquals(listOf("grab false"), out.log)
    }

    @Test
    fun onKey_outOfRangeCodeOrValue_ignored() {
        ready(true); fresh()
        keys(-1 to 1, 0x300 to 1, 30 to 3)
        assertTrue(out.log.isEmpty())
        m.onForeground(false, targetEvent = true)
        assertEquals(listOf("grab false"), out.log)
    }

    @Test
    fun onKey_releaseWithoutPress_doesNotBlockGrabChange() {
        ready(true); fresh()
        m.onKey(30, 0)
        m.onForeground(false, targetEvent = true)
        assertEquals(listOf("key 30 0", "grab false"), out.log)
    }

    @Test
    fun setConnected_twice_grabsOnce() {
        ready(true)
        m.setConnected(true)
        assertEquals(listOf("grab true"), out.log)
    }

    @Test
    fun onKey_ctrlSpaceWithoutAlt_forwardsSpace() {
        ready(true); fresh()
        keys(29 to 1, 57 to 1, 57 to 0, 29 to 0)
        assertEquals(listOf("key 29 1", "key 57 1", "key 57 0", "key 29 0"), out.log)
    }

    @Test
    fun onKey_customToggleKey_replacesGrave() {
        m.toggleKey = RelayStateMachine.KEY_SPACE
        ready(true); fresh()
        combo(key = RelayStateMachine.KEY_GRAVE)
        assertTrue(m.enabled)
        combo(key = RelayStateMachine.KEY_SPACE)
        assertFalse(m.enabled)
    }

    @Test
    fun onKey_pressTwiceWithoutRelease_singleReleaseUnblocksGrab() {
        ready(true); fresh()
        keys(30 to 1, 30 to 1)
        m.onForeground(false, targetEvent = true)
        m.onKey(30, 0)
        assertEquals("grab false", out.log.last())
    }

    @Test
    fun onKey_repeatWhileGrabbed_forwardsValue2() {
        ready(true); fresh()
        keys(30 to 1, 30 to 2, 30 to 0)
        assertEquals(listOf("key 30 1", "key 30 2", "key 30 0"), out.log)
    }

    @Test
    fun releaseHeld_toggleComboHeldWhenNodeLost_neverSendsSwallowedKey() {
        m.toggleKey = RelayStateMachine.KEY_SPACE
        ready(true)
        keys(29 to 1, 56 to 1, 57 to 1)
        fresh()
        m.releaseHeld()
        assertFalse(out.log.contains("key 57 0"))
        m.setEnabled(true)
        fresh()
        keys(57 to 1, 57 to 0)
        assertEquals(listOf("key 57 1", "key 57 0"), out.log)
    }

    @Test
    fun onKey_rightCtrlAndAltGr_toggle() {
        ready(true); fresh()
        combo(ctrl = 97, alt = 100)
        assertFalse(m.enabled)
    }
}
