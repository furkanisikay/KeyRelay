package dev.keyrelay.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.keyrelay.R
import dev.keyrelay.core.KeyPulse
import dev.keyrelay.core.RelayStateMachine
import dev.keyrelay.core.StartupState
import dev.keyrelay.core.defaultPc
import dev.keyrelay.service.AppState
import dev.keyrelay.service.HelperConnection
import dev.keyrelay.service.Notifications
import dev.keyrelay.service.RelayService
import dev.keyrelay.service.RelayStatus
import dev.keyrelay.service.StatusKind
import dev.keyrelay.service.statusKind
import dev.keyrelay.settings.AppSettings
import dev.keyrelay.settings.SettingsStore
import dev.keyrelay.ui.theme.JetBrainsMono
import dev.keyrelay.ui.theme.LocalStatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val WAVE_POINTS = 18
private const val COUNT_UP_MS = 900
private const val TWO_PANE_MIN_DP = 840

internal fun formatRtt(ms: Int) = if (ms >= 0) "$ms ms" else "—"

internal fun toggleKeyName(key: Int) = when (key) {
    RelayStateMachine.KEY_GRAVE -> "`"
    RelayStateMachine.KEY_INSERT -> "Ins"
    else -> "Space"
}

internal fun shortcutLabel(key: Int) = "Ctrl Alt " + toggleKeyName(key)

private const val BLANK_CAP = "•"
private const val ROW_1 = "QWERTYUIOP"
private const val ROW_2 = "ASDFGHJKL"
private const val ROW_3 = "ZXCVBNM"
private const val DIGITS = "1234567890"

/** The keycap label for a key code (US layout names); KeyPulse.NO_KEY, the hidden-keys signal, is a blank cap. */
internal fun keyLabel(code: Int): String = when (code) {
    KeyPulse.NO_KEY -> BLANK_CAP
    in 16..25 -> ROW_1[code - 16].toString()
    in 30..38 -> ROW_2[code - 30].toString()
    in 44..50 -> ROW_3[code - 44].toString()
    in 2..11 -> DIGITS[code - 2].toString()
    12 -> "-"
    13 -> "="
    26 -> "["
    27 -> "]"
    39 -> ";"
    40 -> "'"
    41 -> "`"
    43 -> "\\"
    51 -> ","
    52 -> "."
    53 -> "/"
    1 -> "Esc"
    14 -> "⌫"
    15 -> "Tab"
    28, 96 -> "⏎"
    29, 97 -> "Ctrl"
    42, 54 -> "⇧"
    56 -> "Alt"
    100 -> "AltGr"
    125, 126 -> "Win"
    57 -> "Space"
    58 -> "Caps"
    102 -> "Home"
    103 -> "↑"
    104 -> "PgUp"
    105 -> "←"
    106 -> "→"
    107 -> "End"
    108 -> "↓"
    109 -> "PgDn"
    110 -> "Ins"
    111 -> "Del"
    in 59..68 -> "F${code - 58}"
    87 -> "F11"
    88 -> "F12"
    else -> BLANK_CAP
}

@Composable
fun StatusScreen(settings: AppSettings, onOpenPcs: () -> Unit, onOpenSettings: (() -> Unit)?) {
    val context = LocalContext.current
    val relay by AppState.relay.collectAsStateWithLifecycle()
    val startup by AppState.startup.collectAsStateWithLifecycle()
    WatchRtt()
    val kind = statusKind(relay, startup, settings.pcs.defaultPc != null)
    val look = lookOf(kind)
    val haptics = LocalHapticFeedback.current
    var previous by remember { mutableStateOf(kind) }
    LaunchedEffect(kind) {
        // Taking the keyboard is the transition worth feeling (spec: haptics at critical transitions).
        if (kind == StatusKind.RELAYING && previous != StatusKind.RELAYING) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        previous = kind
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val missing = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && missing) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    // Key flights: only while this screen is started and the keyboard really goes to the PC.
    val flights = remember { KeyFlights() }
    var lastKey by remember { mutableStateOf<String?>(null) }
    var keyHit by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val capStyle = keycapStyle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val relaying = kind == StatusKind.RELAYING
    val showKeys by rememberUpdatedState(settings.showKeys)
    LaunchedEffect(relaying) {
        if (!relaying) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // The helper sends key events only while this block runs (screen visible and relaying).
            HelperConnection.setKeyWatch(true)
            try {
                AppState.keys.collect { code ->
                    // The app checks the setting too: a helper still on an older config must not put a key on screen.
                    val label = keyLabel(if (showKeys) code else KeyPulse.NO_KEY)
                    lastKey = label
                    keyHit++
                    if (motionOn()) flights.fly(scope, label, measurer, capStyle)
                }
            } finally {
                HelperConnection.setKeyWatch(false)
            }
        }
    }
    // The last key stays on the chip only as long as a flight lasts; no key history is kept.
    LaunchedEffect(keyHit) {
        delay(LAST_KEY_MS)
        lastKey = null
    }
    // Hiding keys takes them off screen at once: the chip empties and caps in the air lose their labels.
    LaunchedEffect(settings.showKeys) {
        if (settings.showKeys) return@LaunchedEffect
        lastKey = null
        flights.forget()
    }
    val aura = animatedAura(look)
    Box(Modifier.fillMaxSize().auroraBackdrop(aura, strength = 0.55f)) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 26.dp, vertical = 24.dp)) {
            val wide = maxWidth >= TWO_PANE_MIN_DP.dp
            val hero: @Composable (Modifier) -> Unit = { m ->
                StatusHero(kind, look, relay, startup, settings, flights, lastKey, keyHit, onRetry = { RelayService.retry(context) }, onOpenPcs = onOpenPcs, modifier = m)
            }
            Column(if (wide) Modifier.fillMaxSize() else Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                AppBar(onOpenPcs, onOpenSettings)
                Spacer(Modifier.height(16.dp))
                if (!settings.introSeen) {
                    IntroCard(appLabel(settings.targets.firstOrNull() ?: AppSettings.TEAMVIEWER), shortcutLabel(settings.toggleKey)) {
                        AppState.scope.launch { SettingsStore.update(context) { it.copy(introSeen = true) } }
                    }
                    Spacer(Modifier.height(16.dp))
                }
                if (wide) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        hero(Modifier.weight(1.45f).fillMaxHeight())
                        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { SideTiles(relay, settings, look) }
                    }
                } else {
                    hero(Modifier.fillMaxWidth().heightIn(min = 460.dp))
                    Spacer(Modifier.height(16.dp))
                    SideTiles(relay, settings, look)
                }
            }
        }
    }
}

/** Shown once on the status screen until "Got it": what the app does, in three plain sentences. */
@Composable
private fun IntroCard(app: String, shortcut: String, onDone: () -> Unit) {
    KrCard(Modifier.fillMaxWidth(), padding = 20.dp) {
        Text(stringResource(R.string.intro_title), fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = TightSpacing)
        Text(
            stringResource(R.string.intro_body, app, shortcut), fontSize = 15.sp, lineHeight = 22.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
        )
        HeroButton(stringResource(R.string.intro_ok), onDone)
    }
}

@Composable
private fun AppBar(onOpenPcs: () -> Unit, onOpenSettings: (() -> Unit)?) {
    val press = remember { MutableInteractionSource() }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // The mark only sinks under the finger; it does nothing else, so it is hidden from screen readers.
        KeyRelayLogo(34.dp, Modifier.clickable(press, null) {}.clearAndSetSemantics {}, press)
        Text(stringResource(R.string.app_name), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = TightSpacing, modifier = Modifier.weight(1f))
        BoxIconButton(onOpenPcs, stringResource(R.string.cd_pcs)) { Icon(KrIcons.Pc, null) }
        onOpenSettings?.let { BoxIconButton(it, stringResource(R.string.cd_settings)) { Icon(KrIcons.Sliders, null) } }
    }
}

@Composable
private fun StatusHero(
    kind: StatusKind,
    look: Look,
    relay: RelayStatus,
    startup: StartupState,
    settings: AppSettings,
    flights: KeyFlights,
    lastKey: String?,
    keyHit: Int,
    onRetry: () -> Unit,
    onOpenPcs: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val c = LocalStatusColors.current
    val aura = animatedAura(look)
    val shape = RoundedCornerShape(40.dp)
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    Column(
        modifier.clip(shape).background(c.glass)
            .drawBehind {
                // The hero's own glow, upper right, in the state color.
                drawCircle(
                    Brush.radialGradient(listOf(aura.a1.copy(alpha = 0.4f), Color.Transparent), Offset(size.width * 0.7f, size.height * 0.1f), size.width * 0.45f),
                    size.width * 0.45f, Offset(size.width * 0.7f, size.height * 0.1f),
                )
            }
            .border(1.dp, mix(aura.a1, c.line, 0.35f), shape)
            .padding(start = 26.dp, end = 26.dp, top = 24.dp, bottom = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MorphBadge(look, 54.dp)
            Text(
                stringResource(Notifications.titleRes(kind)).uppercase(Locale.getDefault()), fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.18.em,
                color = mix(aura.a1, colors.onSurface, 0.65f), modifier = Modifier.weight(1f),
            )
            if (kind == StatusKind.RELAYING && lastKey != null) LastKeyChip(lastKey, keyHit)
            ShowKeysButton(settings.showKeys)
        }
        AnimatedContent(kind, transitionSpec = { (slideInVertically(spatial) { it / 3 } + fadeIn()) togetherWith fadeOut() }, label = "headline") { k ->
            Text(
                headline(k, relay), fontSize = 46.sp, lineHeight = 46.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.055).em,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
            )
        }
        Text(subline(kind, relay, startup, settings), fontSize = 15.sp, color = colors.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        RelayArc(look, flights, Modifier.fillMaxWidth().height(190.dp).padding(vertical = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val off = c.faint
            StatusChip(relay.keyboards.firstOrNull() ?: stringResource(R.string.chip_no_keyboard), if (relay.keyboards.isEmpty()) off else c.ready, glow = relay.keyboards.isNotEmpty())
            StatusChip(stringResource(if (relay.grabbed) R.string.chip_locked else R.string.chip_unlocked), if (relay.grabbed) c.ready else off, glow = relay.grabbed)
        }
        when (kind) {
            StatusKind.ERROR, StatusKind.APPROVAL -> HeroButton(stringResource(R.string.action_retry), onRetry)
            StatusKind.NOT_PAIRED, StatusKind.REPAIR -> HeroButton(stringResource(R.string.action_add_pc), onOpenPcs)
            else -> Unit
        }
    }
}

@Composable
private fun HeroButton(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Button(onClick, Modifier.padding(top = 12.dp).height(52.dp).pressScale(interaction), interactionSource = interaction) {
        Text(text, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
    }
}

@Composable
private fun LastKeyChip(label: String, hit: Int) {
    val c = LocalStatusColors.current
    var down by remember { mutableStateOf(false) }
    LaunchedEffect(hit) {
        if (!motionOn()) return@LaunchedEffect
        down = true
        delay(KEY_HIT_MS)
        down = false
    }
    val name = stringResource(R.string.last_key)
    // Accessibility services read the label only, never the typed key.
    Row(
        Modifier.clearAndSetSemantics { contentDescription = name }.height(34.dp).clip(RoundedCornerShape(50)).background(c.glass2).border(1.dp, c.line, RoundedCornerShape(50)).padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(name, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        KeyCap(label, hit = down, small = true)
    }
}

private const val KEY_HIT_MS = 150L
private const val LAST_KEY_MS = 1_100L

/** The eye next to the key flight: shows or hides which keys go to the PC (off by default). */
@Composable
private fun ShowKeysButton(shown: Boolean) {
    val context = LocalContext.current
    val c = LocalStatusColors.current
    val label = stringResource(R.string.show_keys)
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(50)).background(c.glass2).border(1.dp, c.line, RoundedCornerShape(50))
            .toggleable(shown, role = Role.Switch) { on -> AppState.scope.launch { SettingsStore.update(context) { it.copy(showKeys = on) } } }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { Icon(if (shown) KrIcons.Eye else KrIcons.EyeOff, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface) }
}

@Composable
private fun headline(kind: StatusKind, relay: RelayStatus): String = when (kind) {
    StatusKind.RELAYING -> stringResource(R.string.headline_relaying, relay.pcName)
    StatusKind.READY -> stringResource(R.string.headline_ready)
    StatusKind.CONNECTING -> stringResource(R.string.headline_connecting)
    StatusKind.PC_NOT_READY -> stringResource(R.string.headline_pc_not_ready)
    StatusKind.PAUSED -> stringResource(R.string.headline_paused)
    StatusKind.STARTING -> stringResource(R.string.headline_starting)
    StatusKind.NO_WIFI -> stringResource(R.string.headline_no_wifi)
    StatusKind.APPROVAL -> stringResource(R.string.headline_approval)
    StatusKind.ERROR -> stringResource(R.string.headline_error)
    StatusKind.NOT_PAIRED -> stringResource(R.string.headline_not_paired)
    StatusKind.REPAIR -> stringResource(R.string.headline_repair)
}

@Composable
private fun subline(kind: StatusKind, relay: RelayStatus, startup: StartupState, settings: AppSettings): String {
    val target = appLabel(settings.targets.firstOrNull())
    return when (kind) {
        StatusKind.RELAYING -> stringResource(R.string.sub_relaying, target)
        StatusKind.READY -> stringResource(R.string.sub_ready, target)
        StatusKind.CONNECTING -> stringResource(R.string.sub_connecting, relay.endpoint.ifEmpty { relay.pcName })
        StatusKind.APPROVAL -> stringResource(R.string.detail_approval)
        StatusKind.ERROR -> (startup as? StartupState.Failed)?.let { stringResource(Notifications.failureRes(it.failure)) }.orEmpty()
        StatusKind.REPAIR -> stringResource(R.string.sub_repair)
        StatusKind.PC_NOT_READY -> stringResource(R.string.sub_pc_not_ready)
        StatusKind.PAUSED -> stringResource(R.string.sub_paused, shortcutLabel(settings.toggleKey))
        else -> ""
    }
}

@Composable
private fun SideTiles(relay: RelayStatus, settings: AppSettings, look: Look) {
    val context = LocalContext.current
    val aura = animatedAura(look)
    // The shortcut caps press themselves whenever routing is toggled (the shortcut, the switch or the tile).
    var toggles by remember { mutableIntStateOf(0) }
    var seen by remember { mutableStateOf(Triple(relay.attached, relay.enabled, relay.override)) }
    LaunchedEffect(relay.attached, relay.enabled, relay.override) {
        val now = Triple(relay.attached, relay.enabled, relay.override)
        // A helper attaching reports its state for the first time; that is not a toggle.
        if (seen.first && now.first && now != seen) toggles++
        seen = now
    }
    val target = settings.targets.firstOrNull()
    val icon by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, target) {
        value = target?.let { pkg -> withContext(Dispatchers.IO) { InstalledApps.icon(context, pkg) } }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LatencyTile(aura, Modifier.weight(1f))
            Tile(Modifier.weight(1f)) {
                val bmp = icon
                if (bmp != null) Image(bmp, null, Modifier.size(30.dp).clip(RoundedCornerShape(10.dp))) else TileIcon(KrIcons.Arrows, aura.a1)
                Spacer(Modifier.height(4.dp))
                InfoLabel(stringResource(R.string.tile_target), stringResource(R.string.info_target))
                TileValue(appLabel(target).ifEmpty { "—" })
                TileLabel(stringResource(if (relay.targetForeground) R.string.tile_target_front else R.string.tile_target_back), Modifier.padding(top = 4.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Tile(Modifier.weight(1f)) {
                TileIcon(KrIcons.Tunnel, aura.a1)
                InfoLabel(stringResource(R.string.tile_connection), stringResource(R.string.info_security))
                TileValue(relay.endpoint.substringBeforeLast(':').removeSurrounding("[", "]").ifEmpty { "—" })
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(KrIcons.Lock, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    TileLabel(stringResource(R.string.tile_connection_hint))
                }
            }
            Tile(Modifier.weight(1f), RoundedCornerShape(30.dp, 12.dp, 30.dp, 30.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TileIcon(KrIcons.Keyboard, aura.a1)
                    InfoLabel(stringResource(R.string.tile_shortcut), stringResource(R.string.info_shortcut))
                }
                KeyCapRow(listOf("Ctrl", "Alt", toggleKeyName(settings.toggleKey)), toggles, Modifier.padding(top = 12.dp))
                TileLabel(stringResource(R.string.shortcut_options), Modifier.padding(top = 8.dp))
            }
        }
        SwitchCard(stringResource(R.string.routing_title), stringResource(R.string.routing_hint), relay.enabled, stringResource(R.string.info_routing)) {
            RelayService.setEnabled(context, it)
        }
    }
}

/** The only part that reads the round trip: a ping recomposes and redraws this tile (own layer), not the screen. */
@Composable
private fun LatencyTile(aura: Aura, modifier: Modifier) {
    val rtt by AppState.rtt.collectAsStateWithLifecycle()
    val history = remember { mutableStateListOf<Int>() }
    LaunchedEffect(rtt) {
        if (rtt >= 0) {
            history += rtt
            if (history.size > WAVE_POINTS) history.removeAt(0)
        }
    }
    Tile(modifier.graphicsLayer(), RoundedCornerShape(30.dp, 30.dp, 30.dp, 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TileIcon(KrIcons.Gauge, aura.a1)
            InfoLabel(stringResource(R.string.tile_latency), stringResource(R.string.info_latency))
        }
        LatencyNumber(rtt)
        LatencyWave(history, aura.a2, Modifier.fillMaxWidth().height(46.dp).padding(top = 8.dp))
    }
}

@Composable
private fun Tile(modifier: Modifier,shape: RoundedCornerShape = RoundedCornerShape(28.dp), content: @Composable ColumnScope.() -> Unit) =
    KrCard(modifier.heightIn(min = 124.dp), shape = shape, padding = 16.dp, content = content)

@Composable
private fun TileLabel(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun InfoLabel(text: String, info: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    TileLabel(text, Modifier.weight(1f, fill = false))
    InfoButton(text, info)
}

@Composable
private fun TileValue(text: String) =
    Text(text, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.03).em, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))

/** The latency counts up once from 0 the first time it is known (900 ms), then just follows the value. */
@Composable
private fun LatencyNumber(rttMs: Int) {
    val shown = remember { Animatable(0f) }
    var counted by remember { mutableStateOf(false) }
    LaunchedEffect(rttMs) {
        if (rttMs < 0) return@LaunchedEffect
        if (!counted && motionOn()) shown.animateTo(rttMs.toFloat(), tween(COUNT_UP_MS, easing = MOCKUP_EASE)) else shown.snapTo(rttMs.toFloat())
        counted = true
    }
    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(top = 6.dp)) {
        Text(
            if (rttMs < 0) "—" else shown.value.toInt().toString(),
            fontFamily = JetBrainsMono, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, letterSpacing = (-0.05).em,
        )
        if (rttMs >= 0) Text("ms", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
    }
}

/** A smooth line through the recent round trips with a fading area under it; redrawn only when a new value arrives. */
@Composable
private fun LatencyWave(history: List<Int>, color: Color, modifier: Modifier) {
    val bg = MaterialTheme.colorScheme.background
    Spacer(
        modifier.drawWithCache {
            val n = history.size
            val line = Path()
            val area = Path()
            var lastPoint = Offset.Zero
            if (n >= 2) {
                val max = (history.max() * 1.25f).coerceAtLeast(1f)
                val step = size.width / (n - 1)
                fun pt(i: Int) = Offset(i.coerceIn(0, n - 1) * step, size.height - 6f - history[i.coerceIn(0, n - 1)] / max * (size.height - 14f))
                line.moveTo(pt(0).x, pt(0).y)
                for (i in 0 until n - 1) {
                    val p0 = pt(i - 1)
                    val p1 = pt(i)
                    val p2 = pt(i + 1)
                    val p3 = pt(i + 2)
                    line.cubicTo(p1.x + (p2.x - p0.x) / 6, p1.y + (p2.y - p0.y) / 6, p2.x - (p3.x - p1.x) / 6, p2.y - (p3.y - p1.y) / 6, p2.x, p2.y)
                }
                lastPoint = pt(n - 1) - Offset(4.dp.toPx(), 0f)
                area.addPath(line)
                area.lineTo(size.width, size.height)
                area.lineTo(0f, size.height)
                area.close()
            }
            val fill = Brush.verticalGradient(listOf(color.copy(alpha = 0.45f), Color.Transparent))
            val stroke = Stroke(2.4.dp.toPx())
            onDrawBehind {
                if (n < 2) return@onDrawBehind
                drawPath(area, fill)
                drawPath(line, color, style = stroke)
                drawCircle(bg, 6.dp.toPx(), lastPoint)
                drawCircle(color, 4.5.dp.toPx(), lastPoint)
            }
        },
    )
}
