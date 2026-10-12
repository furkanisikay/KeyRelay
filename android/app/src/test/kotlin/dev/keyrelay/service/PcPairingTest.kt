package dev.keyrelay.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PcPairingTest {
    @Test
    fun pairingDeviceName_userNameSet_usesIt() = assertEquals("My Tab", pairingDeviceName("My Tab", "SM-X910"))

    @Test
    fun pairingDeviceName_blankOrMissing_fallsBackToModel() {
        assertEquals("SM-X910", pairingDeviceName(null, "SM-X910"))
        assertEquals("SM-X910", pairingDeviceName("  ", "SM-X910"))
    }

    @Test
    fun pairingDeviceName_veryLong_cutTo64Chars() = assertEquals(64, pairingDeviceName("a".repeat(300), "x").length)
}
