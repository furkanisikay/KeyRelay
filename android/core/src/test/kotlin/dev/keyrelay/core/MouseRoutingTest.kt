package dev.keyrelay.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MouseRoutingTest {
    private val evKey = 1 shl 1
    private val evRel = 1 shl 2

    private fun keys(vararg codes: Int) = ByteArray(96).also { b -> codes.forEach { b[it / 8] = (b[it / 8].toInt() or (1 shl (it % 8))).toByte() } }
    private fun node(name: String, ev: Int = evKey or evRel, vararg codes: Int) = InputNode("/dev/input/event18", name, "", "aa:bb", ev, keys(*codes))

    // MX Master 3S over Bluetooth (getevent -lp on SM-X910).
    private val mxMaster = intArrayOf(0x110, 0x111, 0x112, 0x113, 0x114, 0x115, 0x116, 0x117)

    @Test
    fun isMouse_mouseWithExtraButtons_true() {
        assertTrue(MouseRouting.isMouse(node("MX Master 3S Mouse", codes = mxMaster)))
    }

    @Test
    fun isMouse_ownVirtualMouse_false() {
        assertFalse(MouseRouting.isMouse(node(MouseRouting.VIRTUAL_NAME, codes = mxMaster)))
    }

    @Test
    fun isMouse_onlyLeftAndRight_false() {
        assertFalse(MouseRouting.isMouse(node("Basic Mouse", codes = intArrayOf(0x110, 0x111))))
    }

    @Test
    fun isMouse_keyboardOrTouchpadWithoutRel_false() {
        assertFalse(MouseRouting.isMouse(node("Keyboard", ev = evKey, codes = intArrayOf(30, 57, 0x110, 0x112))))
    }

    @Test
    fun route_extraButtons_onlyToMachineWhetherGrabbedOrNot() {
        for (code in 0x112..0x117) {
            assertEquals(MouseRouting.TO_MACHINE, MouseRouting.route(1, code, grabbed = true))
            assertEquals(MouseRouting.TO_MACHINE, MouseRouting.route(1, code, grabbed = false))
        }
    }

    @Test
    fun route_grabbed_motionWheelMscSynToVirtual() {
        val events = listOf(2 to 0, 2 to 1, 2 to 6, 2 to 8, 2 to 11, 2 to 12, 4 to 4, 0 to 0)
        for ((type, code) in events) assertEquals(MouseRouting.TO_VIRTUAL, MouseRouting.route(type, code, grabbed = true), "$type/$code")
    }

    @Test
    fun route_leftRightGrabbed_toVirtualAndMachine() {
        for (code in 0x110..0x111) assertEquals(MouseRouting.TO_MACHINE or MouseRouting.TO_VIRTUAL, MouseRouting.route(1, code, grabbed = true))
    }

    @Test
    fun route_notGrabbed_leftOnlyHeldByMachineMotionLeftToAndroid() {
        assertEquals(MouseRouting.TO_MACHINE, MouseRouting.route(1, 0x110, grabbed = false))
        assertEquals(MouseRouting.NOTHING, MouseRouting.route(2, 0, grabbed = false))
        assertEquals(MouseRouting.NOTHING, MouseRouting.route(0, 0, grabbed = false))
    }

    @Test
    fun virtualValue_invertVertical_flipsOnlyVerticalWheels() {
        assertEquals(-1, MouseRouting.virtualValue(2, 8, 1, invertHorizontal = false, invertVertical = true))
        assertEquals(120, MouseRouting.virtualValue(2, 11, -120, invertHorizontal = false, invertVertical = true))
        assertEquals(1, MouseRouting.virtualValue(2, 6, 1, invertHorizontal = false, invertVertical = true))
        assertEquals(5, MouseRouting.virtualValue(2, 1, 5, invertHorizontal = false, invertVertical = true))
    }

    @Test
    fun virtualValue_invertHorizontal_flipsOnlyHorizontalWheels() {
        assertEquals(-1, MouseRouting.virtualValue(2, 6, 1, invertHorizontal = true, invertVertical = false))
        assertEquals(-120, MouseRouting.virtualValue(2, 12, 120, invertHorizontal = true, invertVertical = false))
        assertEquals(1, MouseRouting.virtualValue(2, 8, 1, invertHorizontal = true, invertVertical = false))
        assertEquals(-3, MouseRouting.virtualValue(2, 0, -3, invertHorizontal = true, invertVertical = false))
    }

    @Test
    fun virtualValue_nonRelEventWithWheelCode_unchanged() {
        // EV_KEY code 8 is KEY_7, EV_MSC code 11 is not a wheel: only EV_REL is flipped.
        assertEquals(1, MouseRouting.virtualValue(1, 8, 1, invertHorizontal = true, invertVertical = true))
        assertEquals(1, MouseRouting.virtualValue(4, 11, 1, invertHorizontal = true, invertVertical = true))
    }

    @Test
    fun route_nonKeyEventWithButtonCode_notSentToMachine() {
        assertEquals(MouseRouting.TO_VIRTUAL, MouseRouting.route(4, 0x112, grabbed = true))
    }
}
