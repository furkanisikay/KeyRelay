package dev.keyrelay.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.keyrelay.ui.theme.JetBrainsMono
import dev.keyrelay.ui.theme.LocalStatusColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

private const val FLIGHT_MS = 700
private const val MAX_IN_FLIGHT = 4
private const val BURST_MS = 400
private const val CONNECT_MS = 620
private const val BREAK_MS = 420
private const val SPARKS = 7
private const val TRAIL_MAX = 110f

// The light line is drawn in a 600 x 190 design box and scaled to the available width.
private const val BOX_H = 190f
private const val NODE_X = 78f
private const val NODE = 72f

private val BREAK_EASE = CubicBezierEasing(0.6f, 0f, 0.8f, 0.4f)
private val CONNECT_EASE = CubicBezierEasing(0.3f, 0.7f, 0.2f, 1f)

/** Key presses flying along the light line: a fixed pool, at most four at once, each 700 ms, then one arrival burst. */
class KeyFlights {
    internal class Slot {
        val t = Animatable(1f)
        var text: TextLayoutResult? = null
    }

    internal val slots = List(MAX_IN_FLIGHT) { Slot() }
    internal val burst = Animatable(1f)

    /** Starts a flight for [label] if a slot is free; otherwise the press is dropped (never queued). */
    fun fly(scope: CoroutineScope, label: String, measurer: TextMeasurer, style: TextStyle) {
        val slot = slots.firstOrNull { !it.t.isRunning } ?: return
        slot.text = measurer.measure(label, style)
        scope.launch {
            slot.t.snapTo(0f)
            slot.t.animateTo(1f, tween(FLIGHT_MS, easing = LinearEasing))
            // A landed key is forgotten: only caps in flight hold a label.
            slot.text = null
            burst.snapTo(0f)
            burst.animateTo(1f, tween(BURST_MS, easing = MOCKUP_EASE))
        }
    }

    /** Drops the labels of caps still in the air (keys were just hidden); those caps are no longer drawn. */
    fun forget() = slots.forEach { it.text = null }

    /** Plays the arrival burst alone (the connect scene ends with it). */
    suspend fun burst() {
        burst.snapTo(0f)
        burst.animateTo(1f, tween(BURST_MS, easing = MOCKUP_EASE))
    }
}

private fun inOutCubic(t: Float) = if (t < 0.5f) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2

/**
 * The status hero's light line from the tablet to the PC. Static: it is redrawn only when the state changes (400 ms),
 * while the connect/break scene plays, or while a real key press is in flight.
 */
@Composable
fun RelayArc(look: Look, flights: KeyFlights, modifier: Modifier = Modifier) {
    val aura = animatedAura(look)
    val c = LocalStatusColors.current
    val onSurface = MaterialTheme.colorScheme.onSurface
    val error = MaterialTheme.colorScheme.error
    val spec = tween<Float>(STATE_MS, easing = MOCKUP_EASE)
    val halo by animateFloatAsState(when (look) { Look.RELAY, Look.ERROR -> 0.55f; Look.WAITING -> 0.2f; else -> 0.25f }, spec, label = "halo")
    val flow by animateFloatAsState(if (look == Look.RELAY) 0.8f else 0f, spec, label = "flow")
    val dstRing by animateFloatAsState(if (look == Look.RELAY) 0.55f else 0f, spec, label = "dstRing")
    val srcRing by animateFloatAsState(if (look == Look.WAITING) 0.55f else 0f, spec, label = "srcRing")
    val dstAlpha by animateFloatAsState(if (look == Look.ERROR) 0.45f else 1f, spec, label = "dst")
    val waitBadge by animateFloatAsState(if (look == Look.WAITING) 1f else 0f, spec, label = "q")
    val errBadge by animateFloatAsState(if (look == Look.ERROR) 1f else 0f, spec, label = "x")
    // Connect scene: the line draws itself in (or snaps in the middle and pulls back) when the link comes or goes.
    var linked by remember { mutableStateOf(look.linked) }
    var connecting by remember { mutableStateOf(true) }
    val scene = remember { Animatable(1f) }
    LaunchedEffect(look.linked) {
        // Every way out ends on the last frame: a scene cut short by a quick back-and-forth (linked, broken, linked
        // before the middle effect ran) used to leave the line half drawn for good.
        if (look.linked == linked || !motionOn()) {
            linked = look.linked
            connecting = look.linked
            scene.snapTo(1f)
            return@LaunchedEffect
        }
        linked = look.linked
        connecting = look.linked
        scene.snapTo(0f)
        scene.animateTo(1f, tween(if (connecting) CONNECT_MS else BREAK_MS, easing = if (connecting) CONNECT_EASE else BREAK_EASE))
        if (connecting) {
            try {
                flights.burst()
            } finally {
                // Cut short by the next state change: the burst would stay half drawn on the PC node.
                if (!isActive) withContext(NonCancellable) { flights.burst.snapTo(1f) }
            }
        }
    }
    val src = rememberVectorPainter(KrIcons.Tablet)
    val dst = rememberVectorPainter(KrIcons.Pc)
    Spacer(
        modifier.drawWithCache {
            val k = size.height / BOX_H
            val w = size.width
            val y = size.height - 58f * k
            val x0 = NODE_X * k
            val x1 = w - NODE_X * k
            val path = Path().apply {
                moveTo(x0 + 44f * k, y - 4f * k)
                cubicTo(w * 0.3f, -size.height * 0.18f, w * 0.7f, -size.height * 0.18f, x1 - 44f * k, y - 4f * k)
            }
            val measure = PathMeasure().apply { setPath(path, false) }
            val len = measure.length
            val mid = measure.getPosition(len / 2)
            val part = Path()
            val part2 = Path()
            val trail = Path()
            val gradient = Brush.horizontalGradient(listOf(aura.a2, aura.a1, aura.a3))
            val waitDash = PathEffect.dashPathEffect(floatArrayOf(len * 0.016f, len * 0.024f))
            val errDash = PathEffect.dashPathEffect(floatArrayOf(len * 0.41f, len * 0.18f, len * 0.41f, len))
            val dots = PathEffect.dashPathEffect(floatArrayOf(0.1f, len * 0.025f))
            val nodeSize = Size(NODE * k, NODE * k)
            onDrawBehind {
                // Which part of the line exists right now: all of it, the part drawn so far, or two halves pulling back.
                val p = scene.value
                val line: Path = when {
                    p >= 1f -> path
                    connecting -> part.also { it.rewind(); measure.getSegment(0f, len * p, it, true) }
                    else -> part.also {
                        it.rewind()
                        val half = len / 2 * (1 - p)
                        measure.getSegment(0f, half, it, true)
                        part2.rewind()
                        measure.getSegment(len - half, len, part2, true)
                        it.addPath(part2)
                    }
                }
                val effect = if (p < 1f) null else if (look == Look.WAITING) waitDash else if (look == Look.ERROR) errDash else null
                drawPath(line, gradient, alpha = halo * 0.35f, style = Stroke(18f * k, cap = StrokeCap.Round, pathEffect = effect))
                drawPath(line, gradient, alpha = halo * 0.6f, style = Stroke(9f * k, cap = StrokeCap.Round, pathEffect = effect))
                drawPath(line, gradient, style = Stroke(3.2f * k, cap = StrokeCap.Round, pathEffect = effect))
                if (flow > 0f && p >= 1f) drawPath(path, onSurface, alpha = flow, style = Stroke(3.2f * k, cap = StrokeCap.Round, pathEffect = dots))
                drawNode(Offset(x0, y), nodeSize, k, c.node, c.line, aura.a1, srcRing, 1f, 1f) { with(src) { draw(it, colorFilter = ColorFilter.tint(onSurface)) } }
                val b = flights.burst.value
                val pulse = if (b < 0.75f) 1f + 0.12f * sin(PI.toFloat() * b / 0.75f) else 1f
                drawNode(Offset(x1, y), nodeSize, k, c.node, c.line, aura.a1, dstRing, dstAlpha, pulse) { with(dst) { draw(it, colorFilter = ColorFilter.tint(onSurface)) } }
                if (b < 1f) drawBurst(Offset(x1, y), k, b, aura.a2)
                if (waitBadge > 0f) drawBadge(mid, k, c.waiting, Color(0xFF2B1E00), waitBadge, cross = false)
                if (errBadge > 0f) drawBadge(mid, k, error, Color.White, errBadge, cross = true)
                flights.slots.forEach { slot ->
                    val t = slot.t.value
                    val text = slot.text
                    if (t >= 1f || text == null) return@forEach
                    val d = inOutCubic(t) * len
                    val tail = min(TRAIL_MAX * k, d)
                    trail.rewind()
                    measure.getSegment(d - tail, d, trail, true)
                    drawPath(trail, aura.a2, alpha = 0.65f, style = Stroke(5f * k, cap = StrokeCap.Round))
                    drawKeycap(measure.getPosition(d), k * (0.72f + 0.42f * sin(PI.toFloat() * t)), min(1f, t * 7), text, c.capTop, c.capLeft, aura.a2)
                }
            }
        },
    )
}

private inline fun DrawScope.drawNode(
    at: Offset, box: Size, k: Float, fill: Color, line: Color, ring: Color, ringAlpha: Float, alpha: Float, scale: Float,
    icon: DrawScope.(Size) -> Unit,
) {
    if (ringAlpha > 0f) drawCircle(ring, 44f * k, at, alpha = ringAlpha, style = Stroke(2.5f * k))
    scale(scale, at) {
        val tl = Offset(at.x - box.width / 2, at.y - box.height / 2)
        drawRoundRect(fill, tl, box, CornerRadius(24f * k), alpha = alpha)
        drawRoundRect(line, tl, box, CornerRadius(24f * k), alpha = alpha, style = Stroke(1.5f * k))
        val iconSize = 34f * k
        translate(at.x - iconSize / 2, at.y - iconSize / 2) { icon(Size(iconSize, iconSize)) }
    }
}

private fun DrawScope.drawBurst(at: Offset, k: Float, t: Float, color: Color) {
    drawCircle(color, 16f * k * (0.3f + 2.3f * t), at, alpha = 1 - t, style = Stroke(3f * k))
    repeat(SPARKS) { i ->
        val a = (-90f + (i - 3) * 26f) * PI.toFloat() / 180f
        val dx = cos(a)
        val dy = sin(a)
        val push = 20f * k * t
        drawLine(
            color, Offset(at.x + dx * (38f * k + push), at.y + dy * (38f * k + push)), Offset(at.x + dx * (46f * k + push), at.y + dy * (46f * k + push)),
            2.6f * k, StrokeCap.Round, alpha = 1 - t,
        )
    }
}

private fun DrawScope.drawBadge(at: Offset, k: Float, fill: Color, mark: Color, alpha: Float, cross: Boolean) {
    drawCircle(fill, 13f * k, at, alpha = alpha)
    val s = 2.6f * k
    if (cross) {
        drawLine(mark, Offset(at.x - 5 * k, at.y - 5 * k), Offset(at.x + 5 * k, at.y + 5 * k), s, StrokeCap.Round, alpha = alpha)
        drawLine(mark, Offset(at.x + 5 * k, at.y - 5 * k), Offset(at.x - 5 * k, at.y + 5 * k), s, StrokeCap.Round, alpha = alpha)
    } else {
        for (dx in intArrayOf(-1, 0, 1)) drawCircle(mark, s / 2, Offset(at.x + dx * 4.5f * k, at.y), alpha = alpha)
    }
}

private fun DrawScope.drawKeycap(at: Offset, s: Float, alpha: Float, text: TextLayoutResult, top: Color, side: Color, glow: Color) {
    val w = maxOf(32f * s, text.size.width + 14f * s)
    val h = 27f * s
    val tl = Offset(at.x - w / 2, at.y - 16f * s)
    drawRoundRect(glow, tl - Offset(4f * s, 4f * s), Size(w + 8f * s, h + 10f * s), CornerRadius(10f * s), alpha = 0.25f * alpha)
    drawRoundRect(side, tl + Offset(0f, 4f * s), Size(w, h + 2f * s), CornerRadius(8f * s), alpha = alpha)
    drawRoundRect(top, tl, Size(w, h), CornerRadius(8f * s), alpha = alpha)
    drawText(text, topLeft = Offset(at.x - text.size.width / 2f, tl.y + (h - text.size.height) / 2f), alpha = alpha)
}

/** The keycap label style used by the flight; measured once per press, not per frame. */
@Composable
fun keycapStyle() = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = LocalStatusColors.current.capInk)
