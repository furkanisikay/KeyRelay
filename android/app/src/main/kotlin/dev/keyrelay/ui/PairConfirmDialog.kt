package dev.keyrelay.ui

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.keyrelay.R
import dev.keyrelay.core.Frame
import dev.keyrelay.core.PairingInfo
import dev.keyrelay.core.PairingUri
import dev.keyrelay.core.identityCode
import dev.keyrelay.service.PairResult
import dev.keyrelay.service.PairingJobs
import dev.keyrelay.ui.theme.Brand
import dev.keyrelay.ui.theme.JetBrainsMono
import dev.keyrelay.ui.theme.LocalStatusColors
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Where the pairing scene is; it decides the dialog's look and which parts are on screen. */
internal enum class PairPhase { CONFIRM, FOLDING, WAITING, FLYING, VERIFY, OK, DONE, FAILED }

internal fun lookOf(phase: PairPhase) = when (phase) {
    PairPhase.CONFIRM -> Look.RELAY
    PairPhase.FOLDING, PairPhase.WAITING -> Look.WAITING
    PairPhase.FLYING -> Look.RELAY
    PairPhase.VERIFY -> Look.VERIFY
    PairPhase.OK, PairPhase.DONE -> Look.READY
    PairPhase.FAILED -> Look.ERROR
}

private const val COLS = 4
private const val PARTICLES = 16
private const val FAIL_HOLD_MS = 2_200L
private const val ORBIT_MS = 1_200
private const val ORBIT_EXIT_MS = 300
// Radius as a share of the dialog width; with the centre at (-0.6r, -0.8r) from the plane the circle stays inside.
private const val ORBIT_SHARE = 0.27f
private val ORBIT_START = atan2(0.8f, 0.6f)
// The plane icon's nose points about 25 degrees above the x axis.
private const val ORBIT_NOSE = 25f
private val VIOLET = Brand.Verify
private val FOLD_IN = CubicBezierEasing(0.55f, 0f, 0.25f, 1f)
private val FALL = CubicBezierEasing(0.55f, 0f, 1f, 0.45f)
private val FLY_Y = CubicBezierEasing(0.1f, 0.6f, 0.3f, 1f)

/**
 * The PC to show as paired, decided only by this pairing job's own result: a rejected or expired code is a failure even
 * when a PC with the same key is already saved (re-pairing after the PC forgot this tablet).
 */
internal fun pairedIdOf(result: PairResult): String? = (result as? PairResult.Paired)?.id

/** The failure line of this pairing job's result; null on success. */
internal fun pairFailRes(result: PairResult): Int? = when (result) {
    is PairResult.Rejected ->
        if (result.reason == Frame.Reject.PAIRING_NOT_ALLOWED_HERE) R.string.pair_not_allowed_here else R.string.pair_rejected
    is PairResult.Unreachable -> R.string.pair_unreachable
    PairResult.KeyMismatch -> R.string.pair_key_mismatch
    PairResult.NotSaved -> R.string.pair_not_saved
    is PairResult.Paired -> null
}

/** Wire pairs of the 4 x 2 code grid: the top row, the columns, then the bottom row. */
internal val WIRES = listOf(0 to 1, 1 to 2, 2 to 3, 0 to 4, 1 to 5, 2 to 6, 3 to 7, 4 to 5, 5 to 6, 6 to 7)

/** The fold-into-one scene's moving parts; all preallocated, nothing is created per frame. */
private class FoldScene(n: Int) {
    val caps = List(n) { Animatable(Offset.Zero, Offset.VectorConverter) }
    val spin = List(n) { Animatable(0f) }
    val shrink = List(n) { Animatable(0f) }
    val state = mutableStateListOf<Int>().apply { repeat(n) { add(0) } }
    val wires = List(WIRES.size) { Animatable(0f) }
    val wireOk = mutableStateListOf<Boolean>().apply { repeat(WIRES.size) { add(false) } }
    val wireFade = Animatable(1f)
    val target = Animatable(0f)
    val burst = Animatable(0f)
    val check = Animatable(0f)
    var active by mutableStateOf(false)
}

/**
 * Every pairing passes through here: a scanned QR or an opened keyrelay:// link never pairs without this "Pair".
 * "Pair" folds into a paper plane that hovers while the real TLS pairing runs, flies to the PC on success and the
 * code folds into one PC card; on failure the plane drops and the reason stays readable.
 */
@Composable
fun PairConfirmDialog(info: PairingInfo, onDone: (paired: Boolean, pcId: String?) -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val code = remember(info) { identityCode(info.spkiSha256) }
    var phase by remember { mutableStateOf(PairPhase.CONFIRM) }
    var failure by remember { mutableStateOf<String?>(null) }
    var pairedId by remember { mutableStateOf<String?>(null) }
    val busy = phase != PairPhase.CONFIRM && phase != PairPhase.DONE
    // Plane parts: sheet fold, plane in, flight, drop.
    val sheet = remember { Animatable(0f) }
    val planeIn = remember { Animatable(0f) }
    val flight = remember { Animatable(0f) }
    val drop = remember { Animatable(0f) }
    val badgePop = remember { Animatable(1f) }
    val orbit = remember { Animatable(0f) }
    val orbitRadius = remember { Animatable(1f) }
    val fold = remember { FoldScene(8) }
    // Geometry in this dialog's own coordinates.
    var origin by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(Size.Zero) }
    var planeAt by remember { mutableStateOf(Offset.Zero) }
    var badgeAt by remember { mutableStateOf(Offset.Zero) }
    val cellAt = remember { Array(8) { Offset.Zero } }
    var cellSize by remember { mutableStateOf(Size.Zero) }
    var codeCenterY by remember { mutableStateOf(0f) }
    val trail = remember { Path() }
    val trailPart = remember { Path() }
    val trailMeasure = remember { PathMeasure() }
    val trailDash = remember(density) { with(density) { PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 7.dp.toPx())) } }

    fun pairFailText(r: PairResult) = pairFailRes(r)?.let { context.getString(it) }

    /** Follows [job] to its end. [motion] false jumps every scene to its last frame (also used when re-attaching). */
    suspend fun follow(job: Deferred<PairResult>, motion: Boolean, foldIn: Boolean) = coroutineScope {
        if (foldIn && motion) {
            sheet.animateTo(1f, tween(360, easing = CubicBezierEasing(0.3f, 0.7f, 0.3f, 1f)))
            launch { planeIn.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 500f)) }
        } else {
            sheet.snapTo(1f)
            planeIn.snapTo(1f)
        }
        phase = PairPhase.WAITING
        // Honest wait: the plane circles exactly as long as the real pairing call runs, then spirals back to its spot.
        val circling = if (motion && !job.isCompleted) {
            launch {
                while (true) {
                    orbit.snapTo(0f)
                    orbit.animateTo(1f, tween(ORBIT_MS, easing = LinearEasing))
                }
            }
        } else {
            null
        }
        // Success is this job's own result only; a saved PC with the same key never turns a rejection into success.
        val result = job.await()
        if (circling != null) {
            orbitRadius.animateTo(0f, tween(ORBIT_EXIT_MS, easing = MOCKUP_EASE))
            circling.cancel()
        }
        orbit.snapTo(0f)
        orbitRadius.snapTo(1f)
        val id = pairedIdOf(result)
        run {
            if (id != null) {
                pairedId = id
                phase = PairPhase.FLYING
                if (motion) {
                    val s = planeAt
                    val e = badgeAt
                    trail.rewind()
                    trail.moveTo(s.x, s.y)
                    trail.quadraticTo(s.x + (e.x - s.x) * 0.4f, e.y, e.x, e.y)
                    trailMeasure.setPath(trail, false)
                    flight.animateTo(1f, tween(620, easing = LinearEasing))
                    launch { badgePop.snapTo(0f); badgePop.animateTo(1f, tween(320)) }
                }
                foldIntoOne(fold, motion, cellAt, cellSize, Offset(boxSize.width / 2, codeCenterY + with(density) { 10.dp.toPx() }), density.density) { phase = it }
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                phase = PairPhase.DONE
            } else {
                failure = pairFailText(result)
                phase = PairPhase.FAILED
                if (motion) drop.animateTo(1f, tween(420, easing = FALL))
                sheet.animateTo(0f, if (motion) spring(dampingRatio = 0.6f) else tween(0))
                delay(FAIL_HOLD_MS)
                listOf(planeIn, flight, drop).forEach { it.snapTo(0f) }
                phase = PairPhase.CONFIRM
            }
        }
    }

    fun start() {
        if (phase != PairPhase.CONFIRM) return
        phase = PairPhase.FOLDING
        failure = null
        // The network call starts with the tap; the fold animation runs alongside it.
        val job = PairingJobs.start(context, info)
        scope.launch { follow(job, motionOn(), foldIn = true) }
    }

    // Shown again (rotation, recreation) while this code's pairing runs or after it succeeded: re-attach, no new call.
    LaunchedEffect(info) {
        val job = PairingJobs.find(info) ?: return@LaunchedEffect
        phase = PairPhase.WAITING
        follow(job, motion = motionOn() && !job.isCompleted, foldIn = false)
    }

    fun close(paired: Boolean, id: String?) {
        PairingJobs.forget(info)
        onDone(paired, id)
    }

    val look = lookOf(phase)
    val aura = animatedAura(look)
    val c = LocalStatusColors.current
    Dialog(onDismissRequest = { if (!busy) close(phase == PairPhase.DONE, pairedId) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val shape = RoundedCornerShape(34.dp)
        Box(
            Modifier.padding(24.dp).widthIn(max = 460.dp).clip(shape).background(MaterialTheme.colorScheme.background)
                .auroraBackdrop(aura, strength = 0.4f).border(1.dp, c.line, shape)
                .onGloballyPositioned { origin = it.positionInRoot(); boxSize = Size(it.size.width.toFloat(), it.size.height.toFloat()) },
        ) {
            Column(Modifier.padding(start = 28.dp, end = 28.dp, top = 26.dp, bottom = 20.dp)) {
                val pop = badgePop.value
                Box(
                    Modifier.size(60.dp)
                        .graphicsLayer { val s = 1f + 0.15f * sin(PI.toFloat() * pop); scaleX = s; scaleY = s; alpha = if (fold.active || phase == PairPhase.DONE) 0f else 1f }
                        .clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(aura.a1, aura.a3)))
                        .onGloballyPositioned { val p = it.positionInRoot() - origin; badgeAt = p + Offset(it.size.width / 2f, it.size.height / 2f) },
                    contentAlignment = Alignment.Center,
                ) { Icon(KrIcons.Pc, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
                AnimatedContent(
                    when (phase) {
                        PairPhase.VERIFY -> stringResource(R.string.pair_verifying)
                        PairPhase.OK, PairPhase.DONE -> stringResource(R.string.pair_paired)
                        else -> stringResource(R.string.pair_confirm_title, info.pcName)
                    },
                    transitionSpec = { (slideInVertically { it / 3 } + fadeIn()) togetherWith fadeOut() }, label = "pairTitle",
                ) { t ->
                    Text(t, fontSize = 28.sp, lineHeight = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = TightSpacing, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp))
                }
                val hideDetails = phase >= PairPhase.VERIFY && phase != PairPhase.FAILED
                Column(Modifier.graphicsLayer { alpha = if (hideDetails) 0f else 1f }) {
                    Text(stringResource(R.string.pc_identity), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
                CodeCells(
                    code, Modifier.padding(top = 8.dp).onGloballyPositioned { codeCenterY = it.positionInRoot().y - origin.y + it.size.height / 2f },
                    hidden = fold.active || phase == PairPhase.DONE,
                ) { i, at, size -> cellAt[i] = at - origin; cellSize = size }
                Column(Modifier.graphicsLayer { alpha = if (hideDetails) 0f else 1f }) {
                    Text(
                        info.endpoints.joinToString("\n"), fontFamily = JetBrainsMono, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp),
                    )
                }
                Text(
                    if (phase == PairPhase.DONE) stringResource(R.string.pair_done_body, info.pcName) else stringResource(R.string.pair_confirm_body),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp),
                )
                SendStatus(phase, info.pcName, failure)
                Row(Modifier.fillMaxWidth().padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                    if (phase == PairPhase.DONE) {
                        PillButton(stringResource(R.string.action_ok)) { close(true, pairedId) }
                    } else {
                        TextButton({ close(false, null) }, enabled = !busy, modifier = Modifier.graphicsLayer { alpha = if (busy) 0f else 1f }) {
                            Text(stringResource(R.string.action_cancel), fontWeight = FontWeight.ExtraBold)
                        }
                        PlaneButton(
                            phase, sheet.value, planeIn.value, flight.value, drop.value, orbit, orbitRadius, boxSize.width * ORBIT_SHARE,
                            onPlaced = { planeAt = it - origin }, onClick = ::start,
                            flightTo = { t -> planeOffset(t, planeAt, badgeAt) },
                        )
                    }
                }
            }
            // The plane's dashed trail and the fold scene draw over the whole dialog.
            Canvas(Modifier.matchParentSize()) {
                val t = flight.value
                if (phase == PairPhase.FLYING && t > 0f) {
                    trailPart.rewind()
                    trailMeasure.getSegment(0f, trailMeasure.length * t, trailPart, true)
                    drawPath(trailPart, aura.a2, alpha = 0.8f, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, pathEffect = trailDash))
                }
            }
            if (fold.active || phase == PairPhase.DONE) {
                FoldLayer(fold, code, cellSize, Offset(boxSize.width / 2, codeCenterY + with(density) { 10.dp.toPx() }), Modifier.matchParentSize())
            }
        }
    }
}

/** Where the plane is during its flight: X moves linearly, Y eases out, so the path is a natural arc. */
private fun planeOffset(t: Float, from: Offset, to: Offset) = Offset((to.x - from.x) * t, (to.y - from.y) * FLY_Y.transform(t))

@Composable
private fun SendStatus(phase: PairPhase, pcName: String, failure: String?) {
    val text = when (phase) {
        PairPhase.WAITING, PairPhase.FOLDING -> stringResource(R.string.pair_sending, pcName)
        // The button returns to "Pair" after FAIL_HOLD_MS; the reason stays until the next attempt so it can be read.
        PairPhase.FAILED, PairPhase.CONFIRM -> failure.orEmpty()
        else -> ""
    }
    if (text.isEmpty()) return
    val failed = phase == PairPhase.FAILED || phase == PairPhase.CONFIRM
    Text(
        text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp).semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun PillButton(text: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.pressScale(interaction).height(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
            .clickable(interaction, ripple(), role = Role.Button, onClick = onClick).padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp) }
}

/** rect -> trapezoid -> triangle as [f] goes 0 -> 1; always four corners, so every frame is the same polygon. */
private fun sheetPoint(f: Float, i: Int): Offset {
    val rect = RECT[i]
    val trap = TRAP[i]
    val tri = TRI[i]
    return if (f < 0.45f) lerpOffset(rect, trap, f / 0.45f) else lerpOffset(trap, tri, (f - 0.45f) / 0.55f)
}

private fun lerpOffset(a: Offset, b: Offset, t: Float) = Offset(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)

private val RECT = arrayOf(Offset(0f, 0f), Offset(1f, 0f), Offset(1f, 1f), Offset(0f, 1f))
private val TRAP = arrayOf(Offset(0.24f, 0f), Offset(0.76f, 0f), Offset(1f, 1f), Offset(0f, 1f))
private val TRI = arrayOf(Offset(0.5f, 0f), Offset(0.5f, 0f), Offset(0.68f, 1f), Offset(0.32f, 1f))

@Composable
private fun PlaneButton(
    phase: PairPhase,
    sheet: Float,
    planeIn: Float,
    flight: Float,
    drop: Float,
    orbit: Animatable<Float, *>,
    orbitRadius: Animatable<Float, *>,
    orbitPx: Float,
    onPlaced: (Offset) -> Unit,
    onClick: () -> Unit,
    flightTo: (Float) -> Offset,
) {
    val failed = phase == PairPhase.FAILED
    val color by animateColorAsState(if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, label = "planeBtn")
    val interaction = remember { MutableInteractionSource() }
    val label = stringResource(if (failed) R.string.pair_failed_label else R.string.pair_confirm)
    val shape = GenericShape { size, _ ->
        val p0 = sheetPoint(sheet, 0)
        moveTo(p0.x * size.width, p0.y * size.height)
        for (i in 1..3) {
            val p = sheetPoint(sheet, i)
            lineTo(p.x * size.width, p.y * size.height)
        }
        close()
    }
    Box(
        Modifier.pressScale(interaction).height(52.dp).widthIn(min = 132.dp)
            .clickable(interaction, ripple(), enabled = phase == PairPhase.CONFIRM, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = label; role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        // The button face itself is what folds: a pill while idle, a polygon while folding.
        Box(Modifier.matchParentSize().clip(if (sheet == 0f) CircleShape else shape).graphicsLayer { alpha = if (planeIn >= 1f && !failed) 0f else 1f }.background(color))
        Text(
            label, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 24.dp).graphicsLayer { alpha = if (sheet > 0.05f) 0f else 1f },
        )
        if (planeIn > 0f && !(failed && drop >= 1f)) {
            Icon(
                PaperPlane, null, tint = Color.Unspecified,
                modifier = Modifier.size(38.dp)
                    .onGloballyPositioned { onPlaced(it.positionInRoot() + Offset(it.size.width / 2f, it.size.height / 2f)) }
                    .offset { flightTo(flight).let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } }
                    .graphicsLayer {
                        val s = (0.5f + 0.5f * planeIn) * (1f - 0.45f * flight)
                        scaleX = s
                        scaleY = s
                        // A circle through the plane's own spot, centred up and to the left so it stays inside the dialog;
                        // shrinking the radius brings the plane back to that spot, where the flight starts.
                        val r = orbitPx * orbitRadius.value
                        val phi = ORBIT_START - orbit.value * 2 * PI.toFloat()
                        val heading = atan2(-cos(phi), sin(phi)) * 180f / PI.toFloat()
                        val turn = if (orbit.value > 0f) (heading + ORBIT_NOSE + 540f) % 360f - 180f else 0f
                        rotationZ = -25f * (1 - planeIn) + turn * orbitRadius.value - 12f * flight + 75f * drop
                        translationX = r * (cos(phi) - cos(ORBIT_START))
                        translationY = r * (sin(phi) - sin(ORBIT_START)) + 70f * density * FALL.transform(drop)
                        alpha = (1f - drop) * (if (flight >= 1f) 0f else 1f)
                    },
            )
        }
    }
}

/** The mockup's paper plane: three flat faces. */
private val PaperPlane by lazy {
    ImageVector.Builder("plane", 24.dp, 24.dp, 24f, 24f).apply {
        addPath(addPathNodes("M2 11.5 22 2 11 13.5Z"), fill = SolidColor(Color.White))
        addPath(addPathNodes("M11 13.5 22 2 14.5 22Z"), fill = SolidColor(Color(0xFFC9D6FF)))
        addPath(addPathNodes("M11 13.5 9.5 19l3-3.2"), fill = SolidColor(Color(0xFF9FB4FF)))
    }.build()
}

/** Code -> circuit -> verified -> one card: lift into a grid, wire up (violet), turn green, fold to the centre, pop, one burst. */
private suspend fun foldIntoOne(s: FoldScene, motion: Boolean, cellAt: Array<Offset>, cell: Size, center: Offset, density: Float, onPhase: (PairPhase) -> Unit) {
    val n = s.caps.size
    val gap = 18f * density
    val rows = n / COLS
    fun grid(i: Int) = Offset(
        center.x + ((i % COLS) - (COLS - 1) / 2f) * (cell.width + gap) - cell.width / 2,
        center.y + ((i / COLS) - (rows - 1) / 2f) * (cell.height + gap) - cell.height / 2,
    )
    s.caps.forEachIndexed { i, a -> a.snapTo(cellAt[i]) }
    s.active = true
    onPhase(PairPhase.VERIFY)
    if (!motion) {
        s.state.indices.forEach { s.state[it] = 2 }
        s.shrink.forEach { it.snapTo(1f) }
        s.target.snapTo(1f)
        s.check.snapTo(1f)
        s.burst.snapTo(1f)
        onPhase(PairPhase.OK)
        s.active = false
        return
    }
    coroutineScope {
        s.caps.forEachIndexed { i, a ->
            launch {
                delay(i * 35L)
                launch { s.spin[i].animateTo(0f, keyframes { durationMillis = 460; (if (i % 2 == 1) 9f else -9f) at 230 }) }
                a.animateTo(grid(i), spring(dampingRatio = 0.7f, stiffness = 260f))
            }
        }
    }
    s.state.indices.forEach { s.state[it] = 1 }
    coroutineScope { s.wires.forEachIndexed { k, w -> launch { delay(k * 45L); w.animateTo(1f, tween(220, easing = MOCKUP_EASE)) } } }
    onPhase(PairPhase.OK)
    for (i in 0 until n) {
        s.state[i] = 2
        WIRES.forEachIndexed { k, (_, b) -> if (b == i) s.wireOk[k] = true }
        delay(45)
    }
    delay(220)
    coroutineScope {
        launch { s.wireFade.animateTo(0f, tween(200)) }
        s.caps.forEachIndexed { i, a ->
            launch {
                delay(i * 22L)
                launch { s.spin[i].animateTo(if (i % 2 == 1) 140f else -140f, tween(420, easing = FOLD_IN)) }
                launch { s.shrink[i].animateTo(1f, tween(420, easing = FOLD_IN)) }
                a.animateTo(center - Offset(cell.width / 2, cell.height / 2), tween(420, easing = FOLD_IN))
            }
        }
        launch {
            delay(330)
            s.target.animateTo(1f, keyframes { durationMillis = 440; 1.15f at 264 })
            launch { s.burst.animateTo(1f, tween(520, easing = CubicBezierEasing(0.1f, 0.7f, 0.3f, 1f))) }
            delay(120)
            s.check.animateTo(1f, tween(300, easing = MOCKUP_EASE))
        }
    }
    // The scene is over: wires and caps leave the drawing entirely, only the card with its check stays.
    s.wires.forEach { it.snapTo(0f) }
    s.wireOk.indices.forEach { s.wireOk[it] = false }
    s.wireFade.snapTo(1f)
    s.active = false
}

/** Draws the fold scene: flying code caps, the wires between them, then the single PC card with its check and burst. */
@Composable
private fun FoldLayer(s: FoldScene, code: String, cell: Size, center: Offset, modifier: Modifier) {
    val ok = LocalStatusColors.current.ready
    val node = LocalStatusColors.current.node
    val density = LocalDensity.current
    val chars = code.filter { it != '-' }
    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            if (s.active) WIRES.forEachIndexed { k, (a, b) ->
                val p = s.wires[k].value
                if (p <= 0f) return@forEachIndexed
                val pa = s.caps[a].value
                val pb = s.caps[b].value
                val horizontal = b - a == 1
                val from = if (horizontal) Offset(pa.x + cell.width, pa.y + cell.height / 2) else Offset(pa.x + cell.width / 2, pa.y + cell.height)
                val to = if (horizontal) Offset(pb.x, pb.y + cell.height / 2) else Offset(pb.x + cell.width / 2, pb.y)
                drawLine(if (s.wireOk[k]) ok else VIOLET, from, lerpOffset(from, to, p), 3.dp.toPx(), StrokeCap.Round, alpha = s.wireFade.value)
            }
            val t = s.burst.value
            if (t > 0f && t < 1f) {
                drawCircle(ok, 40.dp.toPx() * (1f + 1.6f * t), center, alpha = 0.9f * (1 - t), style = Stroke(2.dp.toPx()))
                for (i in 0 until PARTICLES) {
                    val a = i / PARTICLES.toFloat() * 2 * PI.toFloat() + 0.2f
                    val r = (70f + (i % 3) * 18f) * density.density * t
                    drawCircle(if (i % 3 == 0) Color.White else ok, 3.5.dp.toPx() * (1f - 0.7f * t), center + Offset(cos(a) * r, sin(a) * r), alpha = 1 - t)
                }
            }
        }
        s.caps.forEachIndexed { i, a -> key(i) { if (s.active && s.shrink[i].value < 1f) {
            val st = s.state[i]
            val border by animateColorAsState(when (st) { 1 -> VIOLET; 2 -> ok; else -> LocalStatusColors.current.line }, label = "capB$i")
            Box(
                Modifier
                    .offset { IntOffset(a.value.x.roundToInt(), a.value.y.roundToInt()) }
                    .size(with(density) { cell.width.toDp() }, with(density) { cell.height.toDp() })
                    .graphicsLayer {
                        rotationZ = s.spin[i].value
                        val k = 1f - 0.8f * s.shrink[i].value
                        scaleX = k
                        scaleY = k
                        alpha = 1f - s.shrink[i].value
                    }
                    .clip(RoundedCornerShape(10.dp)).background(lerp(node, border, if (st == 0) 0f else 0.18f)).border(1.5.dp, border, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(chars[i].toString(), fontFamily = JetBrainsMono, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = if (st == 0) MaterialTheme.colorScheme.secondary else border)
            }
        } } }
        val tv = s.target.value
        if (tv > 0f) {
            val half = with(density) { 40.dp.toPx() }
            Box(
                Modifier.offset { IntOffset((center.x - half).roundToInt(), (center.y - half).roundToInt()) }.size(80.dp)
                    .graphicsLayer { scaleX = tv; scaleY = tv; rotationZ = -90f * (1 - tv.coerceAtMost(1f)) }
                    .clip(RoundedCornerShape(26.dp)).background(Brush.linearGradient(listOf(Color(0xFF19C98C), Color(0xFF0EA5A5)))),
                contentAlignment = Alignment.Center,
            ) { Icon(KrIcons.Pc, null, tint = Color.White, modifier = Modifier.size(36.dp)) }
            CheckBadge(s.check.value, Offset(center.x + half - with(density) { 22.dp.toPx() }, center.y + half - with(density) { 22.dp.toPx() }), ok)
        }
    }
}

@Composable
private fun CheckBadge(progress: Float, topLeft: Offset, color: Color) {
    val path = remember { Path() }
    val measure = remember { PathMeasure() }
    val check = remember { Path().apply { moveTo(5f, 12.5f); lineTo(9.5f, 17f); lineTo(19f, 7f) } }
    Canvas(Modifier.offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }.size(30.dp)) {
        drawCircle(Color.White)
        measure.setPath(check, false)
        path.rewind()
        measure.getSegment(0f, measure.length * progress, path, true)
        val k = size.width / 24f
        withTransform({ scale(k, k, Offset.Zero) }) { drawPath(path, color, style = Stroke(3f, cap = StrokeCap.Round)) }
    }
}

/** Google code scanner: no camera permission, the scan UI runs in Play services. */
fun scanPairingQr(context: Context, onResult: (PairingInfo) -> Unit, onError: (String) -> Unit) {
    val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    GmsBarcodeScanning.getClient(context, options).startScan()
        .addOnSuccessListener { code ->
            val info = runCatching { PairingUri.parse(code.rawValue.orEmpty()) }.getOrNull()
            if (info != null) onResult(info) else onError(context.getString(R.string.pair_not_keyrelay))
        }
        .addOnFailureListener { onError(it.message.orEmpty()) }
}
