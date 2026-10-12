package dev.keyrelay.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RttWatchTest {
    @Test
    fun onRtt_noScreenWatching_sendsNothing() = assertFalse(RttWatch().onRtt())

    @Test
    fun onRtt_screenWatching_sends() {
        val w = RttWatch()
        w.setWatching(true)
        assertTrue(w.onRtt())
    }

    @Test
    fun setWatching_firstOn_sendsLatestAtOnce_repeatedOnDoesNot() {
        val w = RttWatch()
        assertTrue(w.setWatching(true))
        assertFalse(w.setWatching(true))
    }

    @Test
    fun setWatching_offAfterOn_stopsSending() {
        val w = RttWatch()
        w.setWatching(true)
        assertFalse(w.setWatching(false))
        assertFalse(w.onRtt())
        assertTrue(w.setWatching(true))
    }
}
