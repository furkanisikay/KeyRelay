package dev.keyrelay.service

import dev.keyrelay.core.Endpoint
import dev.keyrelay.core.PairedPc
import dev.keyrelay.settings.AppSettings
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.security.GeneralSecurityException

class RelayConfigFactoryTest {
    private val a = Endpoint("homepc.ts.net", 8443)
    private val b = Endpoint("192.168.1.10", 47111)
    private val pc = PairedPc(ByteArray(16) { 3 }, "homepc", listOf(a, b), ByteArray(32) { 4 }, byteArrayOf(9), 0L, true)
    private val uids = mapOf(AppSettings.TEAMVIEWER to 10290)

    @Test
    fun build_defaultPc_passesConnectionInOrder() {
        val c = RelayConfigFactory.build(AppSettings(pcs = listOf(pc)), uids::get) { byteArrayOf(1, 2) }
        assertEquals(listOf("homepc.ts.net:8443", "192.168.1.10:47111"), c.endpoints.toList())
        assertArrayEquals(byteArrayOf(1, 2), c.token)
        assertArrayEquals(pc.deviceId, c.deviceId)
        assertArrayEquals(intArrayOf(10290), c.targetUids)
    }

    @Test
    fun build_tokenCannotBeOpened_configuresNoConnection() {
        val c = RelayConfigFactory.build(AppSettings(pcs = listOf(pc)), uids::get) { throw GeneralSecurityException("key gone") }
        assertEquals(0, c.endpoints.size)
        assertEquals(0, c.token.size)
        assertArrayEquals(intArrayOf(10290), c.targetUids)
    }

    @Test
    fun build_missingPackage_skippedFromTargets() {
        val c = RelayConfigFactory.build(AppSettings(targets = setOf(AppSettings.TEAMVIEWER, "not.installed")), uids::get) { it }
        assertArrayEquals(intArrayOf(10290), c.targetUids)
    }

    @Test
    fun build_showKeys_followsTheSetting_offByDefault() {
        assertEquals(false, RelayConfigFactory.build(AppSettings(), uids::get) { it }.showKeys)
        assertEquals(true, RelayConfigFactory.build(AppSettings(showKeys = true), uids::get) { it }.showKeys)
    }

    @Test
    fun build_invertScroll_eachDirectionFollowsItsOwnSetting() {
        val off = RelayConfigFactory.build(AppSettings(), uids::get) { it }
        val horizontal = RelayConfigFactory.build(AppSettings(invertHorizontalScroll = true), uids::get) { it }
        assertEquals(listOf(false, false), listOf(off.invertHorizontalScroll, off.invertVerticalScroll))
        assertEquals(listOf(true, false), listOf(horizontal.invertHorizontalScroll, horizontal.invertVerticalScroll))
    }

    @Test
    fun build_noPc_noEndpointsButSettingsPassed() {
        val c = RelayConfigFactory.build(AppSettings(enabled = false, pingIntervalMs = 2_000, pongTimeoutMs = 5_000), uids::get) { it }
        assertEquals(0, c.endpoints.size)
        assertEquals(false, c.enabled)
        assertEquals(2_000L, c.pingIntervalMs)
        assertEquals(5_000L, c.pongTimeoutMs)
    }
}
