package dev.keyrelay.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ForegroundTrackerTest {
    private val target = 10123
    private fun tracker() = ForegroundTracker { it == target }

    @Test
    fun onForegroundActivitiesChanged_targetComesToFront_becomesForeground() {
        val t = tracker()
        assertTrue(t.onForegroundActivitiesChanged(100, target, true))
        assertTrue(t.targetForeground)
    }

    @Test
    fun onForegroundActivitiesChanged_targetLeaves_notForeground() {
        val t = tracker().apply { onForegroundActivitiesChanged(100, target, true) }
        assertTrue(t.onForegroundActivitiesChanged(100, target, false))
        assertFalse(t.targetForeground)
    }

    @Test
    fun onForegroundActivitiesChanged_sameEventTwice_isIdempotent() {
        val t = tracker()
        t.onForegroundActivitiesChanged(100, target, true)
        assertFalse(t.onForegroundActivitiesChanged(100, target, true))
    }

    @Test
    fun onProcessDied_targetDiesWhileForeground_notForeground() {
        val t = tracker().apply { onForegroundActivitiesChanged(100, target, true) }
        assertTrue(t.onProcessDied(100))
        assertFalse(t.targetForeground)
    }

    @Test
    fun seed_targetAlreadyOnTop_isForegroundWithoutEvents() {
        val t = tracker().apply { seed(mapOf(100 to target, 300 to 1000)) }
        assertTrue(t.targetForeground)
    }

    @Test
    fun onForegroundActivitiesChanged_twoTargetProcessesOneLeaves_staysForeground() {
        val t = tracker().apply {
            onForegroundActivitiesChanged(100, target, true)
            onForegroundActivitiesChanged(101, target, true)
        }
        assertFalse(t.onForegroundActivitiesChanged(100, target, false))
        assertTrue(t.targetForeground)
    }

    private val other = 10200

    @Test
    fun onForegroundActivitiesChanged_otherAppComesFront_targetNotForeground() {
        val t = tracker().apply { onForegroundActivitiesChanged(100, target, true) }
        assertTrue(t.onForegroundActivitiesChanged(200, other, true))
        assertFalse(t.targetForeground)
    }

    @Test
    fun onForegroundActivitiesChanged_targetFalseAfterOtherTrue_staysNotForeground() {
        val t = tracker().apply {
            onForegroundActivitiesChanged(100, target, true)
            onForegroundActivitiesChanged(200, other, true)
        }
        assertFalse(t.onForegroundActivitiesChanged(100, target, false))
        assertFalse(t.targetForeground)
    }

    @Test
    fun onForegroundActivitiesChanged_overlayOpensAndCloses_targetReturns() {
        val t = tracker().apply {
            onForegroundActivitiesChanged(100, target, true)
            onForegroundActivitiesChanged(300, other, true)
        }
        assertTrue(t.onForegroundActivitiesChanged(300, other, false))
        assertTrue(t.targetForeground)
    }

    @Test
    fun onForegroundActivitiesChanged_targetTrueAfterOther_targetWins() {
        val t = tracker().apply {
            onForegroundActivitiesChanged(200, other, true)
            onForegroundActivitiesChanged(100, target, true)
        }
        assertTrue(t.targetForeground)
    }

    @Test
    fun seed_ignoresNonTargetPids_theirLaterFalseChangesNothing() {
        val t = tracker().apply { seed(mapOf(100 to target, 300 to other)) }
        assertTrue(t.targetForeground)
        assertFalse(t.onForegroundActivitiesChanged(300, other, false))
    }

    @Test
    fun seed_calledTwice_replacesPreviousState() {
        val t = tracker().apply { seed(mapOf(100 to target)) }
        assertTrue(t.targetForeground)
        t.seed(emptyMap())
        assertFalse(t.targetForeground)
    }

    @Test
    fun onProcessDied_unknownPid_reportsNoChange() {
        val t = tracker().apply { onForegroundActivitiesChanged(100, target, true) }
        assertFalse(t.onProcessDied(999))
        assertTrue(t.targetForeground)
    }
}
