package dev.keyrelay.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keyrelay.R
import dev.keyrelay.core.PairingInfo
import dev.keyrelay.core.StartupFlow
import dev.keyrelay.launcher.AdbSetup
import dev.keyrelay.service.AppState
import dev.keyrelay.service.RelayService
import dev.keyrelay.settings.AppSettings
import dev.keyrelay.settings.SettingsStore
import dev.keyrelay.ui.theme.JetBrainsMono
import dev.keyrelay.ui.theme.LocalStatusColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val STEP_COUNT = 4
private const val MAX_WIDTH_DP = 600
private const val TAPS = 7
private const val TAP_MS = 80
private const val TIMES_SIGN = 0xD7.toChar()
private const val ARC_STEP_MS = 120
private const val DONE_HOLD_MS = 900L
private const val SWEEP_MS = 1_600
private const val BEAM_FROM = 0.16f
private const val BEAM_TO = 0.84f
private const val BEAM_FADE_MS = 300
private const val QR_CELLS = 21
private const val QR_SEED = 9

// The mockup's arc opacities, centre first: the dot and the inner arc full, then .65 and .35.
private val ARC_ALPHA = floatArrayOf(1f, 1f, 0.65f, 0.35f)

@Composable
fun WizardScreen(settings: AppSettings, linkPairs: Int, onFinished: () -> Unit) {
    val app = LocalContext.current.applicationContext
    var step by rememberSaveable { mutableIntStateOf(0) }
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    BackHandler(enabled = step > 0) { step-- }
    Box(Modifier.fillMaxSize().auroraBackdrop(animatedAura(Look.RELAY), 0.55f), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = MAX_WIDTH_DP.dp).windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 24.dp, end = 24.dp, top = 30.dp, bottom = 24.dp)) {
            StepBars(step)
            AnimatedContent(
                step,
                transitionSpec = {
                    val direction = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(spatial) { direction * it / 6 } + fadeIn()) togetherWith fadeOut()
                },
                label = "wizardStep",
            ) { s ->
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    when (s) {
                        0 -> DeveloperStep(onNext = { step = 1 })
                        1 -> PairingStep(onNext = { step = 2 }, onBack = { step = 0 })
                        2 -> PcStep(linkPairs, onNext = { step = 3 }, onBack = { step = 1 })
                        else -> DoneStep(settings, onBack = { step = 2 }, onFinish = {
                            AppState.scope.launch {
                                SettingsStore.update(app) { it.copy(onboardingDone = true) }
                                RelayService.start(app)
                                onFinished()
                            }
                        })
                    }
                }
            }
        }
    }
}

/** Done steps fill with the accent (on a step change); the current one carries the mockup's static wave. */
@Composable
private fun StepBars(step: Int) {
    val c = LocalStatusColors.current
    val accent = MaterialTheme.colorScheme.primary
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
        repeat(STEP_COUNT) { i ->
            val fill by animateFloatAsState(if (i < step) 1f else 0f, MaterialTheme.motionScheme.slowSpatialSpec(), label = "bar$i")
            val current = i == step
            Box(
                Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(10.dp)).background(c.glass2).drawWithCache {
                    val half = size.height / 2
                    val seg = size.width / 8
                    val wave = Path().apply {
                        moveTo(0f, half)
                        repeat(8) { k -> quadraticTo(seg * k + seg / 2, if (k % 2 == 0) 0f else size.height, seg * (k + 1), half) }
                    }
                    val stroke = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                    onDrawBehind {
                        drawRect(accent, size = Size(size.width * fill, size.height))
                        if (current) drawPath(wave, accent, style = stroke)
                    }
                },
            )
        }
    }
}

@Composable
private fun StepPage(
    look: Look,
    title: String,
    body: String,
    primary: String,
    onPrimary: () -> Unit,
    onBack: (() -> Unit)?,
    primaryEnabled: Boolean = true,
    illustration: @Composable BoxScope.() -> Unit,
    info: String? = null,
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    val c = LocalStatusColors.current
    val shape = RoundedCornerShape(42.dp)
    Column {
        Box(
            Modifier.fillMaxWidth().height(240.dp).clip(shape).background(c.glass).auroraBackdrop(auraOf(look)).border(1.dp, c.line, shape),
            contentAlignment = Alignment.Center,
            content = illustration,
        )
        Row(Modifier.padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 32.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.055).em, modifier = Modifier.weight(1f, fill = false))
            info?.let { InfoButton(title, it) }
        }
        Text(body, fontSize = 15.sp, lineHeight = 23.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        extra()
        val interaction = remember { MutableInteractionSource() }
        Button(
            onPrimary, Modifier.fillMaxWidth().padding(top = 20.dp).height(58.dp).pressScale(interaction, 0.98f),
            enabled = primaryEnabled, interactionSource = interaction,
        ) { Text(primary, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp) }
        onBack?.let { GhostButton(stringResource(R.string.cd_back), it) }
    }
}

@Composable
private fun GhostButton(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick, Modifier.fillMaxWidth().padding(top = 10.dp).height(50.dp).pressScale(interaction, 0.98f),
        border = BorderStroke(1.dp, LocalStatusColors.current.line), interactionSource = interaction,
    ) { Text(text, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface) }
}

/** The mockup's check row: READY a green check, WAITING dots, ERROR a red bang. */
@Composable
private fun CheckRow(look: Look, title: String, hint: String) {
    val status = LocalStatusColors.current
    val tint = when (look) {
        Look.READY -> status.ready
        Look.ERROR -> MaterialTheme.colorScheme.error
        else -> status.waiting
    }
    KrCard(Modifier.fillMaxWidth().padding(top = 10.dp), shape = RoundedCornerShape(22.dp), padding = 14.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(tint.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                Icon(glyphOf(look), null, tint = tint, modifier = Modifier.size(16.dp))
            }
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                if (hint.isNotEmpty()) Text(hint, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// Indirect, false = unknown: DEVELOPMENT_SETTINGS_ENABLED is always 0 for third-party apps (developer.android.com/reference/android/provider/Settings.Global).
private fun developerOptionsOn(context: Context): Boolean {
    val r = context.contentResolver
    fun on(key: String) = runCatching { Settings.Global.getInt(r, key, 0) == 1 }.getOrDefault(false)
    // adb_wifi_enabled is a hidden key; a newer platform may refuse it with SecurityException, hence runCatching.
    return on(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) || on(Settings.Global.ADB_ENABLED) || on(StartupFlow.ADB_WIFI_ENABLED)
}

@Composable
private fun DeveloperStep(onNext: () -> Unit) {
    val context = LocalContext.current
    var devOn by remember { mutableStateOf(developerOptionsOn(context)) }
    val blocker = remember { AutoBlocker.shouldWarn(context) }
    LifecycleResumeEffect(Unit) {
        devOn = developerOptionsOn(context)
        onPauseOrDispose { }
    }
    StepPage(
        Look.RELAY, stringResource(R.string.wizard_dev_title), stringResource(R.string.wizard_dev_body),
        // Never locked: an unknown state is not "off", and the next step needs wireless debugging anyway (the real gate).
        primary = stringResource(R.string.wizard_next), onPrimary = onNext, onBack = null,
        illustration = { SevenTaps(devOn) },
        info = stringResource(R.string.info_wireless_debugging),
    ) {
        CheckRow(
            if (devOn) Look.READY else Look.WAITING, stringResource(if (devOn) R.string.wizard_dev_on else R.string.wizard_dev_unknown),
            if (devOn) "" else stringResource(R.string.wizard_dev_hint),
        )
        val action = if (devOn) Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS else Settings.ACTION_DEVICE_INFO_SETTINGS
        GhostButton(stringResource(if (devOn) R.string.wizard_open_developer else R.string.wizard_open_about)) { context.startActivity(Intent(action)) }
        if (blocker) {
            // A warning, not an error: the setting cannot be read, so it shows on every Samsung.
            CheckRow(Look.WAITING, stringResource(R.string.wizard_blocker_title), stringResource(R.string.wizard_blocker_body))
            GhostButton(stringResource(R.string.wizard_blocker_open)) { context.startActivity(AutoBlocker.settingsIntent()) }
        }
    }
}

/** The badge with "x7" over seven dots; the dots light one after another when developer options turn on. */
@Composable
private fun SevenTaps(devOn: Boolean) {
    val a2 = auraOf(Look.RELAY).a2
    val lit = remember { Animatable(if (devOn) TAPS.toFloat() else 0f) }
    LaunchedEffect(devOn) {
        val target = if (devOn) TAPS.toFloat() else 0f
        if (devOn && motionOn()) lit.animateTo(target, tween(TAPS * TAP_MS, easing = LinearEasing)) else lit.snapTo(target)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            MorphBadge(Look.RELAY, 120.dp, glyph = false)
            Text("${TIMES_SIGN}$TAPS", fontFamily = JetBrainsMono, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, letterSpacing = (-0.06).em, color = Color.White)
        }
        Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            repeat(TAPS) { i ->
                Box(
                    Modifier.size(12.dp).graphicsLayer { alpha = if (lit.value > i) 1f else 0.3f }.drawBehind {
                        drawCircle(a2, size.minDimension, alpha = 0.3f)
                        drawCircle(a2)
                    },
                )
            }
        }
    }
}

private fun lookOf(state: AdbPairing.State) = when (state) {
    AdbPairing.State.Done -> Look.READY
    is AdbPairing.State.Failed -> Look.ERROR
    else -> Look.WAITING
}

@Composable
private fun PairingStep(onNext: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext
    val haptics = LocalHapticFeedback.current
    val state by AdbPairing.state.collectAsStateWithLifecycle()
    var code by rememberSaveable { mutableStateOf("") }
    // Read once per visit: a pairing finished during this visit moves the wizard on by itself.
    val alreadyDone = remember { AdbSetup.hasSecureSettingsPermission(context) }
    // null: not asked yet in this visit; asked only from the row that says what the notification is for.
    var notifyAnswer by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val notifyMissing = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notifyAnswer = granted
        if (granted) PairingNotification.show(app)
    }
    DisposableEffect(Unit) {
        if (!alreadyDone && !notifyMissing) PairingNotification.show(app)
        if (!alreadyDone) AdbPairing.startDiscovery(app)
        onDispose {
            AdbPairing.stopDiscovery()
            if (AdbPairing.state.value != AdbPairing.State.Done) PairingNotification.cancel(app)
        }
    }
    LaunchedEffect(state) {
        if (alreadyDone || state != AdbPairing.State.Done) return@LaunchedEffect
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        // The green result stays a moment before the next step.
        delay(DONE_HOLD_MS)
        onNext()
    }
    val statusText = when (val s = state) {
        AdbPairing.State.Searching -> stringResource(R.string.wizard_pair_searching)
        is AdbPairing.State.Found -> stringResource(R.string.wizard_pair_found)
        AdbPairing.State.Working -> stringResource(R.string.wizard_pair_working)
        AdbPairing.State.Done -> stringResource(R.string.wizard_pair_done)
        is AdbPairing.State.Failed -> stringResource(R.string.wizard_pair_failed, s.detail)
    }
    val idle = state != AdbPairing.State.Working && state != AdbPairing.State.Done
    StepPage(
        Look.RELAY, stringResource(R.string.wizard_pair_title), stringResource(R.string.wizard_pair_body),
        primary = stringResource(if (alreadyDone) R.string.wizard_next else R.string.wizard_pair_action),
        onPrimary = { if (alreadyDone) onNext() else AppState.scope.launch { AdbPairing.submit(app, code) } },
        onBack = onBack,
        primaryEnabled = alreadyDone || (AdbPairing.hasPort() && idle && AdbPairing.isValidCode(code)),
        illustration = { WifiMark(if (alreadyDone) AdbPairing.State.Done else state) },
    ) {
        if (alreadyDone) {
            CheckRow(Look.READY, stringResource(R.string.wizard_pair_already), "")
        } else {
            CheckRow(lookOf(state), statusText, stringResource(R.string.wizard_pair_hint))
            CodeField(code) { code = it }
            if (notifyMissing && notifyAnswer != true) {
                // Refused: the boxes above still work; the row only explains why the notification is missing.
                CheckRow(Look.WAITING, stringResource(R.string.wizard_notify_title), stringResource(if (notifyAnswer == false) R.string.wizard_notify_denied else R.string.wizard_notify_body))
                if (notifyAnswer == null) GhostButton(stringResource(R.string.wizard_notify_allow)) { notifications.launch(Manifest.permission.POST_NOTIFICATIONS) }
            }
        }
        GhostButton(stringResource(R.string.wizard_open_developer)) { context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) }
    }
}

/**
 * The wireless debugging mark. Honest wait: only while the real pairing runs do the arcs light up from the centre
 * outwards. Success (green) and failure (red) reach the arcs as one color wave from the centre.
 */
@Composable
private fun WifiMark(state: AdbPairing.State) {
    val target = when (lookOf(state)) {
        Look.READY -> LocalStatusColors.current.ready
        Look.ERROR -> MaterialTheme.colorScheme.error
        else -> auraOf(Look.RELAY).a2
    }
    val parts = KrIcons.WifiParts
    val wave = if (state == AdbPairing.State.Working && motionOn()) {
        rememberInfiniteTransition(label = "pairWait").animateFloat(
            0f, parts.size.toFloat(), infiniteRepeatable(tween(parts.size * ARC_STEP_MS * 2, easing = LinearEasing)), label = "arc",
        )
    } else {
        null
    }
    Box(Modifier.size(150.dp)) {
        parts.forEachIndexed { i, part ->
            val color by animateColorAsState(target, tween(STATE_MS, delayMillis = i * ARC_STEP_MS, easing = MOCKUP_EASE), label = "arc$i")
            Icon(
                part, null, tint = color,
                modifier = Modifier.matchParentSize().graphicsLayer { alpha = wave?.let { if (it.value.toInt() == i) 1f else 0.25f } ?: ARC_ALPHA[i] },
            )
        }
    }
}

@Composable
private fun CodeField(code: String, onChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val c = LocalStatusColors.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val label = stringResource(R.string.wizard_code_label)
    Text(label,fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
    BasicTextField(
        value = code,
        onValueChange = { v -> onChange(v.filter { it in '0'..'9' }.take(AdbPairing.CODE_LENGTH)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        interactionSource = interaction,
        // The visible boxes carry no semantics; the field itself is announced with its label.
        // A one-time code, not a password: keeps password managers from offering to save it.
        modifier = Modifier.semantics {
            contentDescription = label
            contentType = ContentType.SmsOtpCode
        },
        decorationBox = { inner ->
            Box {
                // The real field stays composed (it owns focus and the keyboard) but draws nothing; the boxes show the digits.
                Box(Modifier.graphicsLayer { alpha = 0f }) { inner() }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(AdbPairing.CODE_LENGTH) { i ->
                        val current = focused && i == code.length.coerceAtMost(AdbPairing.CODE_LENGTH - 1)
                        val shape = RoundedCornerShape(16.dp)
                        Box(
                            Modifier.size(46.dp, 58.dp).clip(shape).background(c.glass)
                                .border(if (current) 2.dp else 1.dp, if (current) colors.primary else c.line, shape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(code.getOrNull(i)?.toString().orEmpty(), fontFamily = JetBrainsMono, fontWeight = FontWeight.ExtraBold, fontSize = 24.sp)
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun PcStep(linkPairs: Int, onNext: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    // A PC paired from a keyrelay:// link uses the app-level dialog, not this step's; its closing moves the step on too.
    val linkPairsAtStart = remember { linkPairs }
    LaunchedEffect(linkPairs) { if (linkPairs != linkPairsAtStart) onNext() }
    // Survives rotation: a pairing in progress re-attaches to its job when the dialog comes back.
    var scanned by rememberSaveable(stateSaver = PairingInfoSaver) { mutableStateOf<PairingInfo?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    StepPage(
        Look.RELAY, stringResource(R.string.wizard_pc_title), stringResource(R.string.wizard_pc_body),
        primary = stringResource(R.string.wizard_pc_scan),
        onPrimary = {
            message = null
            scanPairingQr(context, onResult = { scanned = it }, onError = { message = it })
        },
        onBack = onBack,
        illustration = { QrViewfinder() },
    ) {
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
        GhostButton(stringResource(R.string.wizard_pc_later), onNext)
    }
    scanned?.let { info ->
        PairConfirmDialog(info) { paired, _ ->
            scanned = null
            if (paired) onNext()
        }
    }
}

// Decorative 21 x 21 code, the mockup's qrCells(): three finder squares and a fixed pseudo random fill.
private fun qrCell(x: Int, y: Int): Boolean {
    val finder = (x < 7 && y < 7) || (x > 13 && y < 7) || (x < 7 && y > 13)
    if (!finder) return (x * 7 + y * 13 + x * y + QR_SEED * (x + 3 * y)) % 5 < 2
    val fx = x % 7
    val fy = y % 7
    return fx == 0 || fx == 6 || fy == 0 || fy == 6 || (fx in 2..4 && fy in 2..4)
}

/**
 * The mockup's small viewfinder: green corners, a QR and the scan line. The line sweeps once when the step opens and
 * fades out; a line resting on the QR read on the device as a left-over stroke behind the pairing dialog.
 */
@Composable
private fun QrViewfinder() {
    val ready = LocalStatusColors.current.ready
    val beam = remember { Animatable(BEAM_FROM) }
    val beamAlpha = remember { Animatable(if (motionOn()) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!motionOn()) return@LaunchedEffect
        beam.animateTo(BEAM_TO, tween(SWEEP_MS, easing = MOCKUP_EASE))
        beamAlpha.animateTo(0f, tween(BEAM_FADE_MS))
    }
    Box(Modifier.size(170.dp)) {
        // Own layer: the sweeping line above does not redraw the 441 QR cells.
        Canvas(Modifier.matchParentSize().graphicsLayer()) {
            val arm = 38.dp.toPx()
            val r = 14.dp.toPx()
            val w = 4.dp.toPx()
            val h = w / 2
            val corner = Path().apply {
                moveTo(h, arm)
                lineTo(h, r)
                arcTo(Rect(h, h, h + 2 * (r - h), h + 2 * (r - h)), 180f, 90f, false)
                lineTo(arm, h)
            }
            repeat(4) { k -> rotate(90f * k) { drawPath(corner, ready, style = Stroke(w)) } }
            val inset = 22.dp.toPx()
            val pad = 6.dp.toPx()
            drawRoundRect(Color.White, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset), CornerRadius(6.dp.toPx()))
            val cell = (size.width - 2 * inset - 2 * pad) / QR_CELLS
            for (y in 0 until QR_CELLS) for (x in 0 until QR_CELLS) {
                if (qrCell(x, y)) drawRect(Color(0xFF0B111C), Offset(inset + pad + x * cell, inset + pad + y * cell), Size(cell, cell))
            }
        }
        Canvas(Modifier.matchParentSize()) {
            val fade = beamAlpha.value
            if (fade <= 0f) return@Canvas
            val left = 6.dp.toPx()
            val right = size.width - left
            val at = size.height * beam.value
            val brush = Brush.horizontalGradient(listOf(Color.Transparent, ready, Color.Transparent), left, right)
            drawLine(brush, Offset(left, at), Offset(right, at), 12.dp.toPx(), alpha = 0.25f * fade)
            drawLine(brush, Offset(left, at), Offset(right, at), 3.dp.toPx(), StrokeCap.Round, alpha = fade)
        }
    }
}

@Composable
private fun DoneStep(settings: AppSettings, onBack: () -> Unit, onFinish: () -> Unit) {
    StepPage(
        Look.READY, stringResource(R.string.wizard_done_title), stringResource(R.string.wizard_done_body, appLabel(settings.targets.firstOrNull())),
        primary = stringResource(R.string.wizard_finish), onPrimary = onFinish, onBack = onBack,
        illustration = { DoneMark() },
    ) {
        CheckRow(Look.READY, stringResource(R.string.wizard_done_check), stringResource(R.string.wizard_done_hint))
    }
}

/** The ready mark: the circle, then the check are drawn once (~0.5 s) between the shortcut keys that now reach the PC. */
@Composable
private fun DoneMark() {
    val ready = LocalStatusColors.current.ready
    val ring = remember { Animatable(if (motionOn()) 0f else 1f) }
    val tick = remember { Animatable(if (motionOn()) 0f else 1f) }
    LaunchedEffect(Unit) {
        launch { ring.animateTo(1f, tween(400, easing = MOCKUP_EASE)) }
        tick.animateTo(1f, tween(350, delayMillis = 150, easing = MOCKUP_EASE))
    }
    val check = remember { Path().apply { moveTo(8f, 12f); lineTo(11f, 15f); lineTo(16f, 9f) } }
    val part = remember { Path() }
    val measure = remember { PathMeasure().apply { setPath(check, false) } }
    Box(Modifier.size(200.dp)) {
        KeyCap("Alt", hit = false, Modifier.align(Alignment.TopStart).offset(y = 80.dp), small = true)
        KeyCap("Tab", hit = false, Modifier.align(Alignment.TopEnd).offset(y = 40.dp), small = true)
        KeyCap("Win", hit = false, Modifier.align(Alignment.BottomStart).offset(x = 76.dp), small = true)
        Canvas(Modifier.size(120.dp).align(Alignment.Center)) {
            val k = size.width / 24f
            withTransform({ scale(k, k, Offset.Zero) }) {
                drawArc(ready, -90f, 360f * ring.value, false, Offset(3f, 3f), Size(18f, 18f), style = Stroke(1.6f))
                part.rewind()
                measure.getSegment(0f, measure.length * tick.value, part, true)
                drawPath(part, ready, style = Stroke(2.2f, cap = StrokeCap.Round))
            }
        }
    }
}

/** Samsung Auto Blocker has no readable setting (P2 Task 1, section 4): every Samsung device gets the warning. */
private object AutoBlocker {
    fun shouldWarn(context: Context) = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    fun settingsIntent(): Intent = Intent(Settings.ACTION_SECURITY_SETTINGS)
}
