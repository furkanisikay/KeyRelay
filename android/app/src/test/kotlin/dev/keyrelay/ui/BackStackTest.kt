package dev.keyrelay.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackStackTest {
    @Test
    fun back_pcsOverStatus_returnsToStatus() {
        val stack = mutableListOf<Route>(Route.Status, Route.Pcs)
        assertTrue(stack.back())
        assertEquals(listOf(Route.Status), stack)
    }

    @Test
    fun back_pcDetailOnNarrowScreen_closesDetailFirstThenPcs() {
        val stack = mutableListOf(Route.Status, Route.Pcs, Route.PcDetail("a"))
        stack.back()
        assertEquals(listOf(Route.Status, Route.Pcs), stack)
        stack.back()
        assertEquals(listOf(Route.Status), stack)
    }

    @Test
    fun back_settings_returnsToStatus() {
        val stack = mutableListOf<Route>(Route.Status, Route.Settings)
        assertTrue(stack.back())
        assertEquals(listOf(Route.Status), stack)
    }

    @Test
    fun back_lastScreen_staysAndReportsNothingToDo() {
        val stack = mutableListOf<Route>(Route.Status)
        assertFalse(stack.back())
        assertEquals(listOf(Route.Status), stack)
    }
}
