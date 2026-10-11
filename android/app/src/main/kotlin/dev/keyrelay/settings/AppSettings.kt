package dev.keyrelay.settings

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.keyrelay.core.ConnectionConfig
import dev.keyrelay.core.PairedPc
import dev.keyrelay.core.PairedPcCodec
import dev.keyrelay.core.RelayStateMachine

data class AppSettings(
    val pcs: List<PairedPc> = emptyList(),
    val targets: Set<String> = setOf(TEAMVIEWER),
    val enabled: Boolean = true,
    val pingIntervalMs: Long = ConnectionConfig.DEFAULT_PING_INTERVAL_MS,
    val pongTimeoutMs: Long = ConnectionConfig.DEFAULT_PONG_TIMEOUT_MS,
    val sendTimeoutMs: Long = ConnectionConfig.DEFAULT_SEND_TIMEOUT_MS,
    val toggleKey: Int = RelayStateMachine.KEY_GRAVE,
    val onboardingDone: Boolean = false,
    /** Whether the status screen may show which keys go to the PC; off means the key never leaves the helper. */
    val showKeys: Boolean = false,
    /** The status screen's one-time "How it works" card was dismissed. */
    val introSeen: Boolean = false,
) {
    /** A value equal to its default is not stored, so a default changed in a later version reaches existing installs. */
    fun writeTo(p: MutablePreferences) {
        val d = AppSettings()
        fun <T> put(key: Preferences.Key<T>, value: T, default: T) {
            if (value == default) p.remove(key) else p[key] = value
        }
        put(PCS, PairedPcCodec.encode(pcs), PairedPcCodec.encode(d.pcs))
        put(TARGETS, targets, d.targets)
        put(ENABLED, enabled, d.enabled)
        put(PING, pingIntervalMs, d.pingIntervalMs)
        put(PONG, pongTimeoutMs, d.pongTimeoutMs)
        put(SEND, sendTimeoutMs, d.sendTimeoutMs)
        put(TOGGLE, toggleKey, d.toggleKey)
        put(ONBOARDED, onboardingDone, d.onboardingDone)
        put(INTRO_SEEN, introSeen, d.introSeen)
        // Privacy setting: always stored, so a future default can never change the user's own choice.
        p[SHOW_KEYS] = showKeys
    }

    companion object {
        const val TEAMVIEWER = "com.teamviewer.teamviewer.market.mobile"
        private val PCS = stringPreferencesKey("pcs")
        private val TARGETS = stringSetPreferencesKey("targets")
        private val ENABLED = booleanPreferencesKey("enabled")
        private val PING = longPreferencesKey("ping_interval_ms")
        private val PONG = longPreferencesKey("pong_timeout_ms")
        private val SEND = longPreferencesKey("send_timeout_ms")
        private val TOGGLE = intPreferencesKey("toggle_key")
        private val ONBOARDED = booleanPreferencesKey("onboarding_done")
        private val SHOW_KEYS = booleanPreferencesKey("show_keys")
        private val INTRO_SEEN = booleanPreferencesKey("intro_seen")

        /** Values the helper cannot use fall back to defaults: a bad pair of times would crash it on every start. */
        fun from(p: Preferences): AppSettings {
            val d = AppSettings()
            val ping = p[PING] ?: d.pingIntervalMs
            val pong = p[PONG] ?: d.pongTimeoutMs
            val timesValid = ping > 0 && pong > ping
            return AppSettings(
                pcs = PairedPcCodec.decode(p[PCS].orEmpty()),
                targets = p[TARGETS] ?: d.targets,
                enabled = p[ENABLED] ?: d.enabled,
                pingIntervalMs = if (timesValid) ping else d.pingIntervalMs,
                pongTimeoutMs = if (timesValid) pong else d.pongTimeoutMs,
                sendTimeoutMs = (p[SEND] ?: d.sendTimeoutMs).takeIf { it > 0 } ?: d.sendTimeoutMs,
                toggleKey = (p[TOGGLE] ?: d.toggleKey).takeIf { it in RelayStateMachine.TOGGLE_KEYS } ?: d.toggleKey,
                onboardingDone = p[ONBOARDED] ?: d.onboardingDone,
                showKeys = p[SHOW_KEYS] ?: d.showKeys,
                introSeen = p[INTRO_SEEN] ?: d.introSeen,
            )
        }
    }
}
