package dev.keyrelay.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.keyrelay.core.ConnectionConfig
import dev.keyrelay.core.Endpoint
import dev.keyrelay.core.PairedPc
import dev.keyrelay.core.RelayStateMachine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AppSettingsTest {
    private fun pc() = PairedPc(ByteArray(16) { 1 }, "PC", listOf(Endpoint("10.0.0.1", 47111)), ByteArray(32), byteArrayOf(9), 5L, true)

    @Test
    fun from_emptyPreferences_returnsDefaults() = assertEquals(AppSettings(), AppSettings.from(emptyPreferences()))

    @Test
    fun writeTo_thenFrom_roundTrips() {
        val original = AppSettings(
            pcs = listOf(pc()), targets = setOf("a.b"), enabled = false, pingIntervalMs = 2_000, pongTimeoutMs = 5_000,
            sendTimeoutMs = 500, toggleKey = RelayStateMachine.KEY_INSERT, onboardingDone = true, showKeys = true,
            introSeen = true, invertHorizontalScroll = true, invertVerticalScroll = true,
        )
        val prefs = mutablePreferencesOf()
        original.writeTo(prefs)
        val back = AppSettings.from(prefs)
        assertEquals(original.copy(pcs = emptyList()), back.copy(pcs = emptyList()))
        assertEquals(listOf(pc().id), back.pcs.map { it.id })
    }

    @Test
    fun writeTo_thenFrom_emptyTargetsStayEmpty() {
        val prefs = mutablePreferencesOf()
        AppSettings(targets = emptySet()).writeTo(prefs)
        assertEquals(emptySet<String>(), AppSettings.from(prefs).targets)
    }

    @Test
    fun from_pongNotAbovePing_fallsBackToDefaults() {
        val prefs = mutablePreferencesOf()
        AppSettings(pingIntervalMs = 10_000, pongTimeoutMs = 10_000).writeTo(prefs)
        val s = AppSettings.from(prefs)
        assertEquals(ConnectionConfig.DEFAULT_PING_INTERVAL_MS, s.pingIntervalMs)
        assertEquals(ConnectionConfig.DEFAULT_PONG_TIMEOUT_MS, s.pongTimeoutMs)
    }

    @Test
    fun from_unknownToggleKey_fallsBackToGrave() {
        val prefs = mutablePreferencesOf()
        AppSettings(toggleKey = 30).writeTo(prefs)
        assertEquals(RelayStateMachine.KEY_GRAVE, AppSettings.from(prefs).toggleKey)
    }

    @Test
    fun writeTo_defaultValues_storesOnlyThePrivacyChoice() {
        val prefs = mutablePreferencesOf()
        AppSettings().writeTo(prefs)
        assertEquals(setOf("show_keys"), prefs.asMap().keys.map { it.name }.toSet())
    }

    @Test
    fun writeTo_valueBackToDefault_removesStoredKey() {
        val prefs = mutablePreferencesOf()
        AppSettings(toggleKey = RelayStateMachine.KEY_INSERT).writeTo(prefs)
        AppSettings().writeTo(prefs)
        assertNull(prefs[intPreferencesKey("toggle_key")])
    }

    @Test
    fun from_settingSavedAsOldDefault_readsCurrentDefault() {
        // Another field changed; the shortcut was left at its default and must follow a later default change.
        val prefs = mutablePreferencesOf()
        AppSettings(enabled = false).writeTo(prefs)
        assertNull(prefs[intPreferencesKey("toggle_key")])
        assertEquals(AppSettings().toggleKey, AppSettings.from(prefs).toggleKey)
    }

    @Test
    fun showKeys_offByDefault_andTheUsersChoiceIsAlwaysStored() {
        assertEquals(false, AppSettings.from(emptyPreferences()).showKeys)
        val prefs = mutablePreferencesOf()
        AppSettings(showKeys = true).writeTo(prefs)
        AppSettings(showKeys = false).writeTo(prefs)
        // Stored even though it equals today's default: a later default must not turn key display on.
        assertEquals(false, prefs[booleanPreferencesKey("show_keys")])
        assertEquals(false, AppSettings.from(prefs).showKeys)
    }

    @Test
    fun from_damagedPcEntry_keepsTheOthers() {
        val prefs = mutablePreferencesOf()
        AppSettings(pcs = listOf(pc())).writeTo(prefs)
        val key = stringPreferencesKey("pcs")
        prefs[key] = prefs[key] + "\nv1 garbage"
        assertEquals(1, AppSettings.from(prefs).pcs.size)
    }
}
