package dev.keyrelay.ui

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.keyframes
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keyrelay.R
import dev.keyrelay.core.Endpoint
import dev.keyrelay.core.PairedPc
import dev.keyrelay.core.PairingInfo
import dev.keyrelay.core.defaultPc
import dev.keyrelay.service.AppState
import dev.keyrelay.settings.AppSettings
import dev.keyrelay.settings.SettingsStore
import dev.keyrelay.ui.theme.LocalStatusColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Base64
import kotlin.math.roundToInt

private const val LIST_PANE_DP = 390
private const val TWO_PANE_MIN_DP = 840
private const val STACK_ICON_DP = 22
private const val STACK_STEP_DP = 16
private val FOLD_EASE = CubicBezierEasing(0.5f, 0f, 0.75f, 0f)
private val GRAVITY = CubicBezierEasing(0.55f, 0f, 1f, 0.45f)

/** The "container comes to you" scene for one target app; values are read while drawing the overlay. */
private class TargetScene {
    val icon = Animatable(Offset.Zero, Offset.VectorConverter)
    val iconScale = Animatable(1f)
    val iconTurn = Animatable(0f)
    val kap = Animatable(Offset.Zero, Offset.VectorConverter)
    val bounce = Animatable(1f)
    var flying by mutableStateOf<AppEntry?>(null)
    var from by mutableStateOf(Rect.Zero)
    var arriving by mutableStateOf<String?>(null)
    var leaving by mutableStateOf<String?>(null)
    val leave = Animatable(0f)
    var running by mutableStateOf(false)
    var traveling by mutableStateOf(false)
    var pending: Pair<AppEntry, Boolean>? = null
}

@Composable
fun PcsScreen(settings: AppSettings, onOpenPc: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val relay by AppState.relay.collectAsStateWithLifecycle()
    val rtt by AppState.rtt.collectAsStateWithLifecycle()
    WatchRtt()
    // Survives rotation: a pairing in progress re-attaches to its job when the dialog comes back.
    var scanned by rememberSaveable(stateSaver = PairingInfoSaver) { mutableStateOf<PairingInfo?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var fresh by remember { mutableStateOf<String?>(null) }
    var removeFailed by remember { mutableStateOf(false) }
    val folds = remember { mutableStateMapOf<String, Animatable<Float, *>>() }
    val apps by produceState(emptyList<AppEntry>()) { value = withContext(Dispatchers.IO) { InstalledApps.load(context) } }
    // Ordered once per load: a row must not jump away while the user is switching it.
    val ordered = remember(apps) { TargetApps.order(apps, settings.targets) }
    val scope = rememberCoroutineScope()
    val scene = remember { TargetScene() }
    val iconBounds = remember { mutableStateMapOf<String, Rect>() }
    var stackBounds by remember { mutableStateOf(Rect.Zero) }
    var summaryAt by remember { mutableStateOf(Offset.Zero) }
    var rootAt by remember { mutableStateOf(Offset.Zero) }
    val aura = animatedAura(if (relay.connected) Look.RELAY else Look.PAUSED)

    fun play(app: AppEntry, on: Boolean) = scope.launch {
        scene.running = true
        var next: Pair<AppEntry, Boolean>? = app to on
        // A tap during a scene does not restart it; only the latest one plays afterwards (the last event wins).
        while (next != null) {
            val (a, o) = next
            if (o) {
                scene.arriving = a.packageName
                val inStack = ordered.count { it.packageName in settings.targets && it.packageName != a.packageName }
                comeToYou(scene, a, iconBounds[a.packageName] ?: Rect.Zero, stackBounds, inStack, density.density)
            } else {
                scene.leaving = a.packageName
                scene.leave.snapTo(0f)
                scene.leave.animateTo(1f, tween(220))
                scene.leaving = null
            }
            next = scene.pending
            scene.pending = null
        }
        scene.running = false
    }

    fun toggleTarget(app: AppEntry, on: Boolean) {
        // The change itself always lands at once; the scene only presents it.
        AppState.scope.launch { SettingsStore.update(context) { s -> s.copy(targets = if (on) s.targets + app.packageName else s.targets - app.packageName) } }
        when {
            !motionOn() -> Unit
            scene.running -> scene.pending = app to on
            else -> play(app, on)
        }
    }

    fun remove(pc: PairedPc, bin: BinScene, wide: Boolean) = scope.launch {
        val fold = if (wide) suspend {
            val a = Animatable(0f)
            folds[pc.id] = a
            a.animateTo(1f, tween(300, easing = FOLD_EASE))
            Unit
        } else null
        removeFailed = !removePc(context, pc, bin, fold)
        folds.remove(pc.id)
        if (!removeFailed) {
            selected = null
            if (!wide) onBack()
        }
    }

    Box(Modifier.fillMaxSize().auroraBackdrop(aura, 0.55f).onGloballyPositioned { rootAt = it.positionInRoot() }) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val wide = maxWidth >= TWO_PANE_MIN_DP.dp
            val current = settings.pcs.firstOrNull { it.id == selected } ?: settings.pcs.defaultPc
            Row(Modifier.fillMaxSize()) {
                Box(if (wide) Modifier.width(LIST_PANE_DP.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 110.dp)) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BoxIconButton(onBack, stringResource(R.string.cd_back)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) }
                                Text(
                                    stringResource(R.string.pcs_title), fontSize = 42.sp, lineHeight = 40.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.06).em,
                                    modifier = Modifier.padding(start = 14.dp),
                                )
                            }
                        }
                        items(settings.pcs, key = { it.id }) { pc ->
                            val connected = relay.connected && pc.isDefault
                            PcCard(
                                pc, connected, rtt, selected = wide && pc.id == current?.id, fold = folds[pc.id]?.value ?: 0f, fresh = fresh == pc.id,
                                modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = spring(dampingRatio = 0.7f, stiffness = 400f)),
                                onLanded = { fresh = null },
                            ) {
                removeFailed = false
                if (wide) selected = pc.id else onOpenPc(pc.id)
            }
                        }
                        if (settings.pcs.isEmpty()) {
                            item { Text(stringResource(R.string.pcs_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 10.dp)) }
                        }
                        item { SectionTitle(stringResource(R.string.targets_title), stringResource(R.string.info_targets_pick)) }
                        item {
                            TargetsSummary(
                                ordered.filter { (it.packageName in settings.targets || it.packageName == scene.leaving) && it.packageName != scene.arriving }, scene,
                                Modifier.graphicsLayer { alpha = if (scene.traveling) 0f else 1f }
                                    .onGloballyPositioned { summaryAt = it.positionInRoot() },
                                onStack = { stackBounds = it },
                            )
                        }
                        items(ordered, key = { it.packageName }) { app ->
                            TargetRow(app, app.packageName in settings.targets, onIcon = { iconBounds[app.packageName] = it }) { on -> toggleTarget(app, on) }
                        }
                    }
                    AddPcFab(Modifier.align(Alignment.BottomStart).padding(20.dp)) {
                        scanPairingQr(context, onResult = { scanned = it }, onError = { message = it })
                    }
                }
                if (wide) {
                    val c = LocalStatusColors.current
                    val shape = RoundedCornerShape(38.dp)
                    Box(Modifier.weight(1f).fillMaxHeight().padding(top = 14.dp, end = 14.dp, bottom = 14.dp).clip(shape).background(c.glass).border(1.dp, c.line, shape)) {
                        if (current == null) {
                            Icon(KrIcons.Pc, null, Modifier.size(64.dp).align(Alignment.Center), tint = c.faint)
                        } else {
                            val bin = remember(current.id) { BinScene() }
                            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 30.dp)) {
                                PcDetailPane(
                                    current, relay.connected && current.isDefault, rtt, bin, removeFailed, wide = true,
                                    onRemove = { remove(current, bin, wide = true) },
                                )
                            }
                        }
                    }
                }
            }
        }
        // The overlay layer: the summary copy that travels to the switched row and the icon dropping into it.
        if (scene.traveling) {
            TargetsSummary(
                ordered.filter { (it.packageName in settings.targets || it.packageName == scene.leaving) && it.packageName != scene.arriving }, scene,
                Modifier.offset { (summaryAt - rootAt + scene.kap.value).round() },
            )
        }
        scene.flying?.let { app ->
            val size = with(density) { scene.from.width.toDp() }
            Box(
                Modifier.offset { (scene.from.topLeft - rootAt + scene.icon.value).round() }.size(size)
                    .graphicsLayer { scaleX = scene.iconScale.value; scaleY = scaleX; rotationZ = scene.iconTurn.value }
                    .shadow(8.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp)),
            ) { app.icon?.let { Image(it, null, Modifier.fillMaxSize()) } }
        }
    }
    scanned?.let { info ->
        PairConfirmDialog(info) { paired, id ->
            scanned = null
            if (paired && id != null) {
                selected = id
                fresh = id
            }
        }
    }
    message?.let { text ->
        AlertDialog(
            onDismissRequest = { message = null },
            confirmButton = { TextButton({ message = null }) { Text(stringResource(R.string.action_ok)) } },
            text = { Text(text) },
        )
    }
}

private fun Offset.round() = IntOffset(x.roundToInt(), y.roundToInt())

private val b64 = Base64.getUrlEncoder().withoutPadding()

/** A scanned pairing as four strings (name, addresses, key hash, code) for saved instance state; empty = none. */
internal val PairingInfoSaver = listSaver<PairingInfo?, String>(
    save = { info -> info?.let { listOf(it.pcName, it.endpoints.joinToString(","), b64.encodeToString(it.spkiSha256), b64.encodeToString(it.code)) }.orEmpty() },
    restore = { s ->
        if (s.size != 4) null else runCatching {
            PairingInfo(s[0], s[1].split(',').map { Endpoint.parse(it, 0) }, Base64.getUrlDecoder().decode(s[2]), Base64.getUrlDecoder().decode(s[3]))
        }.getOrNull()
    },
)

/** The icon pops up, the summary slides under it, the icon drops in, the summary bounces and goes home. */
private suspend fun comeToYou(scene: TargetScene, app: AppEntry, from: Rect, stack: Rect, count: Int, density: Float) {
    scene.from = from
    scene.icon.snapTo(Offset.Zero)
    scene.iconScale.snapTo(1f)
    scene.iconTurn.snapTo(0f)
    scene.kap.snapTo(Offset.Zero)
    scene.flying = app
    scene.traveling = true
    coroutineScope {
        launch { scene.icon.animateTo(Offset(0f, -54f * density), tween(240, easing = MOCKUP_EASE)) }
        launch { scene.iconScale.animateTo(1.1f, tween(240)) }
        scene.iconTurn.animateTo(-8f, tween(240))
    }
    // The new slot sits right after the icons already in the stack.
    val slotX = if (count == 0) stack.left + STACK_ICON_DP / 2f * density else stack.right - STACK_ICON_DP / 2f * density + STACK_STEP_DP * density
    val slot = Offset(slotX, stack.center.y)
    scene.kap.animateTo(from.center - slot, spring(dampingRatio = 0.55f, stiffness = 400f))
    coroutineScope {
        launch { scene.icon.animateTo(Offset.Zero, tween(280, easing = GRAVITY)) }
        launch { scene.iconTurn.animateTo(0f, tween(280)) }
        scene.iconScale.animateTo(STACK_ICON_DP * density / from.width.coerceAtLeast(1f), tween(280, easing = GRAVITY))
    }
    scene.arriving = null
    scene.flying = null
    scene.bounce.snapTo(0.82f)
    scene.bounce.animateTo(1f, keyframes { durationMillis = 300; 1.06f at 150 })
    delay(220)
    scene.kap.animateTo(Offset.Zero, spring(dampingRatio = 0.55f, stiffness = 300f))
    scene.traveling = false
}

/** "Targets [icons] N apps": the container that comes to a switched-on row. */
@Composable
private fun TargetsSummary(stack: List<AppEntry>, scene: TargetScene, modifier: Modifier = Modifier, onStack: (Rect) -> Unit = {}) {
    val c = LocalStatusColors.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier.padding(start = 6.dp, top = 10.dp).clip(shape).background(c.glass).border(1.dp, c.line, shape).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.targets_summary), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(
            Modifier.height(STACK_ICON_DP.dp)
                .graphicsLayer { scaleY = scene.bounce.value; transformOrigin = TransformOrigin(0.5f, 1f) }
                .onGloballyPositioned { onStack(it.boundsInRoot()) },
        ) {
            stack.forEachIndexed { i, app ->
                val leaving = scene.leaving == app.packageName
                Box(
                    Modifier.offset(x = (i * STACK_STEP_DP).dp).size(STACK_ICON_DP.dp)
                        .graphicsLayer {
                            if (leaving) {
                                val t = scene.leave.value
                                scaleX = 1 - t
                                scaleY = 1 - t
                                rotationZ = 90f * t
                                alpha = 1 - t
                            }
                        }
                        .clip(RoundedCornerShape(7.dp)).border(2.dp, MaterialTheme.colorScheme.background, RoundedCornerShape(7.dp)),
                ) { app.icon?.let { Image(it, null, Modifier.fillMaxSize()) } }
            }
            Spacer(Modifier.width((STACK_ICON_DP + (stack.size - 1).coerceAtLeast(0) * STACK_STEP_DP).dp))
        }
        AnimatedContent(stack.size, transitionSpec = { (slideInVertically { -it } + fadeIn()) togetherWith (fadeOut()) }, label = "targetCount") { k ->
            Text(
                if (k == 0) stringResource(R.string.targets_none) else pluralStringResource(R.plurals.targets_count, k, k),
                fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AddPcFab(modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(22.dp)
    Row(
        modifier.pressScale(interaction).shadow(18.dp, shape, ambientColor = primary, spotColor = primary).height(66.dp).clip(shape).background(primary)
            .clickable(interaction, ripple(), role = Role.Button, onClick = onClick).padding(horizontal = 26.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(KrIcons.Scan, null, tint = MaterialTheme.colorScheme.onPrimary)
        Text(stringResource(R.string.pcs_add_qr), color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
    }
}

@Composable
private fun PcCard(
    pc: PairedPc,
    connected: Boolean,
    rttMs: Int,
    selected: Boolean,
    fold: Float,
    fresh: Boolean,
    modifier: Modifier,
    onLanded: () -> Unit,
    onClick: () -> Unit,
) {
    val c = LocalStatusColors.current
    val accent = auraOf(Look.RELAY).a1
    val shape = RoundedCornerShape(if (selected) 18.dp else 28.dp)
    // A freshly paired PC lands with a green ring once.
    val land = remember { Animatable(if (fresh && motionOn()) 0f else 1f) }
    LaunchedEffect(fresh) {
        if (!fresh) return@LaunchedEffect
        land.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 300f))
        onLanded()
    }
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier.padding(top = 10.dp).fillMaxWidth()
            .graphicsLayer {
                // Removing: the card tips back and fades before the list closes the gap.
                rotationX = 75f * fold
                cameraDistance = 12f * density
                val s = (1f - 0.3f * fold) * (0.92f + 0.08f * land.value)
                scaleX = s
                scaleY = s
                alpha = 1f - fold
            }
            .then(if (land.value < 1f) Modifier.border((4 * (1 - land.value)).dp, c.ready, shape) else Modifier)
            .pressScale(interaction, 0.985f)
            .sharedPcBounds(pc.id)
            .clip(shape).background(if (selected) mix(accent, c.glass, 0.22f) else c.glass)
            .border(1.dp, if (selected) accent.copy(alpha = 0.5f) else c.line, shape)
            .clickable(interaction, ripple(), onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PcAvatar(connected, 54.dp)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(pc.name, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                if (connected) stringResource(R.string.pc_connected, formatRtt(rttMs))
                else stringResource(R.string.pc_last_seen, DateUtils.getRelativeTimeSpanString(pc.lastSeenMs).toString()),
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (pc.isDefault) DefaultBadge()
    }
}

@Composable
private fun TargetRow(app: AppEntry, checked: Boolean, onIcon: (Rect) -> Unit, onChange: (Boolean) -> Unit) {
    KrCard(Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(22.dp), padding = 0.dp) {
        // The whole row is the switch, so TalkBack reads the app name with its on/off state.
        Row(Modifier.toggleable(checked, role = Role.Switch, onValueChange = onChange).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            val icon = app.icon
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).onGloballyPositioned { onIcon(it.boundsInRoot()) }) {
                if (icon != null) Image(icon, null, Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(app.label, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                Text(stringResource(R.string.targets_hint), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = null)
        }
    }
}
