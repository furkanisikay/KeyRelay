package dev.keyrelay.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KeyboardGroupingTest {
    private val evKey = 1 shl 1
    private val evRel = 1 shl 2

    private fun keys(vararg codes: Int) = ByteArray(96).also { b -> codes.forEach { b[it / 8] = (b[it / 8].toInt() or (1 shl (it % 8))).toByte() } }
    private fun node(path: String, uniq: String = "aa:bb", phys: String = "host/input0", ev: Int = evKey, vararg codes: Int) =
        InputNode(path, path, phys, uniq, ev, keys(*codes))

    @Test
    fun group_typingNodeWithConsumerControl_groupsSameUniq() {
        val kb = node("/dev/input/event13", codes = intArrayOf(30, 57))
        val consumer = node("/dev/input/event16", codes = intArrayOf(164))
        val result = KeyboardGrouping.group(listOf(kb, consumer))
        assertEquals(listOf(listOf(kb.path, consumer.path)), result.map { k -> k.nodes.map { it.path } })
    }

    @Test
    fun group_mouseWithSameUniq_isExcluded() {
        val kb = node("/dev/input/event13", codes = intArrayOf(30, 57))
        val mouse = node("/dev/input/event18", ev = evKey or evRel, codes = intArrayOf(272))
        assertEquals(listOf(kb.path), KeyboardGrouping.group(listOf(kb, mouse)).single().nodes.map { it.path })
    }

    @Test
    fun group_differentUniq_notGrouped() {
        val kb = node("/dev/input/event13", uniq = "aa", codes = intArrayOf(30, 57))
        val other = node("/dev/input/event16", uniq = "bb", codes = intArrayOf(164))
        assertEquals(listOf(kb.path), KeyboardGrouping.group(listOf(kb, other)).single().nodes.map { it.path })
    }

    @Test
    fun group_emptyUniq_fallsBackToPhysRoot() {
        val kb = node("/dev/input/event3", uniq = "", phys = "usb-1/input0", codes = intArrayOf(30, 57))
        val consumer = node("/dev/input/event4", uniq = "", phys = "usb-1/input1", codes = intArrayOf(164))
        val stranger = node("/dev/input/event5", uniq = "", phys = "usb-2/input1", codes = intArrayOf(164))
        assertEquals(listOf(kb.path, consumer.path), KeyboardGrouping.group(listOf(kb, consumer, stranger)).single().nodes.map { it.path })
    }

    @Test
    fun group_builtInNodesWithoutLetters_noKeyboard() {
        val gpio = node("/dev/input/event6", uniq = "", phys = "", codes = intArrayOf(114, 115, 116))
        val touch = node("/dev/input/event10", uniq = "", phys = "", ev = evKey or (1 shl 3), codes = intArrayOf(330))
        assertTrue(KeyboardGrouping.group(listOf(gpio, touch)).isEmpty())
    }

    @Test
    fun group_twoTypingNodesSameDevice_yieldsOneKeyboard() {
        val a = node("/dev/input/event13", codes = intArrayOf(30, 57))
        val b = node("/dev/input/event14", codes = intArrayOf(30, 57))
        assertEquals(1, KeyboardGrouping.group(listOf(a, b)).size)
    }

    private val evAbs = 1 shl 3

    @Test
    fun group_touchpadSiblingSameUniq_isExcluded() {
        val kb = node("/dev/input/event13", codes = intArrayOf(30, 57))
        val touchpad = node("/dev/input/event15", ev = evKey or evAbs, codes = intArrayOf(330, 272))
        assertEquals(listOf(kb.path), KeyboardGrouping.group(listOf(kb, touchpad)).single().nodes.map { it.path })
    }

    @Test
    fun group_absNodeWithoutTouch_isGrouped() {
        val kb = node("/dev/input/event13", codes = intArrayOf(30, 57))
        val consumer = node("/dev/input/event16", ev = evKey or evAbs, codes = intArrayOf(164))
        assertEquals(listOf(kb.path, consumer.path), KeyboardGrouping.group(listOf(kb, consumer)).single().nodes.map { it.path })
    }

    @Test
    fun group_emptyUniqAndEmptyPhys_doesNotGroupStrangers() {
        val kb = node("/dev/input/event3", uniq = "", phys = "", codes = intArrayOf(30, 57))
        val other = node("/dev/input/event4", uniq = "", phys = "", codes = intArrayOf(164))
        assertEquals(listOf(kb.path), KeyboardGrouping.group(listOf(kb, other)).single().nodes.map { it.path })
    }
}
