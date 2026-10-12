package dev.keyrelay.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keyrelay.R
import dev.keyrelay.core.RelayStateMachine
import dev.keyrelay.service.AppState
import dev.keyrelay.settings.AppSettings
import dev.keyrelay.settings.SettingsStore
import dev.keyrelay.ui.theme.JetBrainsMono
import kotlinx.coroutines.launch
import java.text.NumberFormat

private const val MAX_WIDTH_DP = 720

/** The values the Settings screen offers; defaults live in ConnectionConfig. */
object SettingsChoices {
    val PING_MS = longArrayOf(2_000, 5_000, 10_000)
    val PONG_MS = longArrayOf(5_000, 10_000, 20_000)
    val SEND_MS = longArrayOf(500, 1_000, 2_000)
}

/**
 * Checked against the stored values inside the write: two quick taps can both pass the screen's (older) check, and a
 * stored pair with pong not above ping would be reset to defaults on read.
 */
internal fun AppSettings.withTimes(ping: Long = pingIntervalMs, pong: Long = pongTimeoutMs) =
    if (pong > ping) copy(pingIntervalMs = ping, pongTimeoutMs = pong) else this

@Composable
fun SettingsScreen(settings: AppSettings, onRunWizard: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val relay by AppState.relay.collectAsStateWithLifecycle()
    // The service pushes every settings change to the helper; the screen only writes the store.
    val save: ((AppSettings) -> AppSettings) -> Unit = { transform -> AppState.scope.launch { SettingsStore.update(context, transform) } }
    var shortcutPicked by remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize().auroraBackdrop(animatedAura(if (relay.connected) Look.RELAY else Look.PAUSED), 0.55f), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = MAX_WIDTH_DP.dp).verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            ScreenTopBar(stringResource(R.string.settings_title), onBack)
            SectionTitle(stringResource(R.string.settings_shortcut), stringResource(R.string.info_shortcut))
            KrCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                // The caps press themselves once when another key is picked, as on the status screen's shortcut tile.
                KeyCapRow(listOf("Ctrl", "Alt", toggleKeyName(settings.toggleKey)), shortcutPicked)
                Text(stringResource(R.string.settings_shortcut_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    RelayStateMachine.TOGGLE_KEYS.forEachIndexed { i, key ->
                        SegmentedButton(
                            selected = settings.toggleKey == key,
                            onClick = {
                                if (settings.toggleKey != key) shortcutPicked++
                                save { it.copy(toggleKey = key) }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = RelayStateMachine.TOGGLE_KEYS.size),
                            label = { Text(toggleKeyName(key), fontFamily = JetBrainsMono) },
                        )
                    }
                }
            }
            SectionTitle(stringResource(R.string.settings_privacy))
            SwitchCard(stringResource(R.string.show_keys), stringResource(R.string.settings_show_keys_hint), settings.showKeys, stringResource(R.string.info_show_keys)) { on ->
                save { it.copy(showKeys = on) }
            }
            SectionTitle(stringResource(R.string.settings_mouse), stringResource(R.string.info_invert_scroll))
            SwitchCard(stringResource(R.string.invert_vertical_scroll), stringResource(R.string.invert_vertical_scroll_hint), settings.invertVerticalScroll) { on ->
                save { it.copy(invertVerticalScroll = on) }
            }
            Box(Modifier.padding(top = 8.dp)) {
                SwitchCard(stringResource(R.string.invert_horizontal_scroll), stringResource(R.string.invert_horizontal_scroll_hint), settings.invertHorizontalScroll) { on ->
                    save { it.copy(invertHorizontalScroll = on) }
                }
            }
            SectionTitle(stringResource(R.string.settings_connection), stringResource(R.string.info_check))
            KrCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                // A reply wait not longer than the ping interval would drop healthy connections; such pairs are disabled.
                ChoiceRow(R.string.settings_ping, R.string.settings_ping_hint, SettingsChoices.PING_MS, settings.pingIntervalMs, { it < settings.pongTimeoutMs }) { v ->
                    save { it.withTimes(ping = v) }
                }
                ChoiceRow(R.string.settings_pong, R.string.settings_pong_hint, SettingsChoices.PONG_MS, settings.pongTimeoutMs, { it > settings.pingIntervalMs }) { v ->
                    save { it.withTimes(pong = v) }
                }
                ChoiceRow(R.string.settings_send, R.string.settings_send_hint, SettingsChoices.SEND_MS, settings.sendTimeoutMs, { true }) { v ->
                    save { it.copy(sendTimeoutMs = v) }
                }
            }
            SectionTitle(stringResource(R.string.settings_keyboards), stringResource(R.string.info_keyboards))
            if (relay.keyboards.isEmpty()) {
                Text(stringResource(R.string.settings_no_keyboard), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 8.dp))
            }
            relay.keyboards.forEach { name ->
                KrCard(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.settings_keyboard_hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SectionTitle(stringResource(R.string.settings_setup))
            OutlinedButton(onRunWizard, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text(stringResource(R.string.settings_run_wizard), fontWeight = FontWeight.Bold) }
            Text(
                stringResource(R.string.about_fonts), fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 18.dp),
            )
        }
    }
}

@Composable
private fun ChoiceRow(title: Int, hint: Int, options: LongArray, selected: Long, allowed: (Long) -> Boolean, onSelect: (Long) -> Unit) {
    Text(stringResource(title), fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
    Text(stringResource(hint), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        options.forEachIndexed { i, ms ->
            SegmentedButton(
                selected = ms == selected,
                onClick = { onSelect(ms) },
                enabled = allowed(ms),
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                label = { Text(stringResource(R.string.unit_seconds, NumberFormat.getInstance().format(ms / 1000.0)), fontFamily = JetBrainsMono) },
            )
        }
    }
}
