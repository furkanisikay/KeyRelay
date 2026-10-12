package dev.keyrelay.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.keyrelay.ui.theme.JetBrainsMono
import dev.keyrelay.ui.theme.LocalStatusColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/** The mockup's press: a short spring squash while the finger is down (Material Expressive press feedback). */
@Composable
fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.95f): Modifier {
    val down by interaction.collectIsPressedAsState()
    val s by animateFloatAsState(if (down) pressed else 1f, spring(dampingRatio = 0.5f, stiffness = 600f), label = "press")
    return graphicsLayer { scaleX = s; scaleY = s }
}

// ---- keycaps ----

/** One keyboard keycap: light top face over a colored edge; [hit] pushes it down with a glow. */
@Composable
fun KeyCap(label: String, hit: Boolean, modifier: Modifier = Modifier, small: Boolean = false) {
    val c = LocalStatusColors.current
    val glow = auraOf(Look.RELAY).a2
    val down by animateFloatAsState(if (hit) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 900f), label = "cap")
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier
            .graphicsLayer { translationY = (-2f + 4f * down) * density }
            .drawBehind {
                val r = CornerRadius(9.dp.toPx())
                if (down > 0f) drawRoundRect(glow, Offset(-4.dp.toPx(), -4.dp.toPx()), Size(size.width + 8.dp.toPx(), size.height + 8.dp.toPx()), CornerRadius(13.dp.toPx()), alpha = 0.35f * down)
                drawRoundRect(c.capLeft, Offset(0f, 4.dp.toPx() * (1 - down)), size, r)
            }
            .background(Brush.verticalGradient(listOf(c.capTop, lerp(c.capTop, c.capLeft, 0.14f))), shape)
            .heightIn(min = if (small) 26.dp else 36.dp)
            .widthIn(min = if (small) 34.dp else 42.dp)
            .padding(horizontal = if (small) 6.dp else 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = JetBrainsMono, fontWeight = FontWeight.ExtraBold, fontSize = if (small) 11.sp else 13.sp, color = c.capInk)
    }
}

/** The shortcut's caps; each change of [play] presses them one after another (130 ms apart), as the shortcut was typed. */
@Composable
fun KeyCapRow(labels: List<String>, play: Int, modifier: Modifier = Modifier) {
    var down by remember { mutableIntStateOf(-1) }
    var seen by remember { mutableIntStateOf(play) }
    LaunchedEffect(play) {
        if (play == seen) return@LaunchedEffect
        seen = play
        if (!motionOn()) return@LaunchedEffect
        for (i in labels.indices) {
            down = i
            delay(CAP_STEP_MS)
        }
        delay(CAP_HOLD_MS)
        down = -1
    }
    Row(modifier.clearAndSetSemantics { contentDescription = labels.joinToString(" ") }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, l -> KeyCap(l, hit = down >= i) }
    }
}

private const val CAP_STEP_MS = 130L
private const val CAP_HOLD_MS = 160L

// ---- identity code cells ----

private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
private const val SCRAMBLE_STEP_MS = 45L
private const val LOCK_FIRST_MS = 80L
private const val LOCK_EACH_MS = 50L

/** When cell [index] stops scrambling and shows its real character. */
internal fun lockTimeMs(index: Int) = LOCK_FIRST_MS + index * LOCK_EACH_MS

/** Rises into place once per [key] (380 ms spring, 50 ms per [index]); with animations off it is simply there. */
@Composable
fun Modifier.rise(key: Any, index: Int): Modifier {
    val v = remember(key) { Animatable(if (motionOn()) 0f else 1f) }
    LaunchedEffect(key) {
        delay(index * RISE_STAGGER_MS)
        v.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 400f))
    }
    return graphicsLayer {
        alpha = v.value.coerceIn(0f, 1f)
        translationY = 16f * density * (1 - v.value)
        scaleX = 0.98f + 0.02f * v.value
        scaleY = scaleX
    }
}

private const val RISE_STAGGER_MS = 50L

/**
 * The identity code in keycap-like cells. On first show it resolves once, letter by letter (~500 ms); screen readers
 * read only the final code. [hidden] leaves the cells' space but draws nothing (the fold scene flies copies of them).
 */
@Composable
fun CodeCells(code: String, modifier: Modifier = Modifier, big: Boolean = false, hidden: Boolean = false, onCell: (Int, Offset, Size) -> Unit = { _, _, _ -> }) {
    val chars = code.filter { it != '-' }
    var shown by remember(code) { mutableStateOf(if (motionOn()) chars.map { BASE32[Random.nextInt(32)] }.joinToString("") else chars) }
    var locked by remember(code) { mutableIntStateOf(if (motionOn()) 0 else chars.length) }
    LaunchedEffect(code) {
        if (!motionOn()) return@LaunchedEffect
        var t = 0L
        while (locked < chars.length) {
            delay(SCRAMBLE_STEP_MS)
            t += SCRAMBLE_STEP_MS
            locked = chars.indices.count { t >= lockTimeMs(it) }
            shown = chars.mapIndexed { i, ch -> if (i < locked) ch else BASE32[Random.nextInt(32)] }.joinToString("")
        }
    }
    val c = LocalStatusColors.current
    Row(modifier.clearAndSetSemantics { contentDescription = code }, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        var index = 0
        code.forEach { ch ->
            if (ch == '-') {
                // Hidden with the cells: a dash left alone stayed on screen through the whole fold scene.
                Text(
                    "-", color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = JetBrainsMono, fontSize = 20.sp,
                    modifier = Modifier.width(12.dp).graphicsLayer { alpha = if (hidden) 0f else 1f },
                )
                return@forEach
            }
            val i = index++
            val lock by animateFloatAsState(if (i < locked) 0f else 1f, spring(dampingRatio = 0.5f), label = "lock$i")
            val w = if (big) 42.dp else 34.dp
            val h = if (big) 54.dp else 44.dp
            Box(
                Modifier.size(w, h)
                    .onGloballyPositioned { onCell(i, it.positionInRoot(), Size(it.size.width.toFloat(), it.size.height.toFloat())) }
                    .graphicsLayer { alpha = if (hidden) 0f else 1f; scaleX = 1f + 0.25f * lock; scaleY = scaleX }
                    .background(c.glass2, RoundedCornerShape(10.dp))
                    .border(1.dp, c.line, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(shown.getOrElse(i) { ch }.toString(), fontFamily = JetBrainsMono, fontWeight = FontWeight.ExtraBold, fontSize = if (big) 26.sp else 20.sp, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

// ---- bin eats the label ----

private val THROW_UP = CubicBezierEasing(0.2f, 0.7f, 0.4f, 1f)
private val THROW_DOWN = CubicBezierEasing(0.6f, 0f, 0.9f, 0.6f)
private const val LETTER_STAGGER_MS = 55L
private const val LETTER_FLIGHT_MS = 420

/** Drives one "bin eats the label" scene; the button reads its values while drawing. */
class BinScene {
    internal val lid = Animatable(0f)
    internal val shrink = Animatable(0f)
    internal val ring = Animatable(1f)
    internal val shake = Animatable(0f)
    internal var letters: List<Animatable<Float, *>> = emptyList()
    internal var hops: List<Animatable<Float, *>> = emptyList()
    var failed by mutableStateOf(false)
        internal set
    var eaten by mutableStateOf(false)
        internal set

    internal fun prepare(count: Int) {
        if (letters.size != count) {
            letters = List(count) { Animatable(0f) }
            hops = List(count) { Animatable(0f) }
        }
    }

    /** Lid opens, the letters (nearest first) arc into the bin, lid closes, the button shrinks to a circle, one ring. */
    suspend fun eat() {
        failed = false
        if (!motionOn()) {
            eaten = true
            return
        }
        lid.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 700f))
        coroutineScope {
            // Nearest letter first: the label starts right next to the bin.
            letters.forEachIndexed { i, a -> launch { delay(i * LETTER_STAGGER_MS); a.animateTo(1f, tween(LETTER_FLIGHT_MS, easing = LinearEasing)) } }
        }
        eaten = true
        lid.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 700f))
        shrink.animateTo(1f, tween(SHRINK_MS, easing = CubicBezierEasing(0.3f, 0.7f, 0.3f, 1f)))
        ring.snapTo(0f)
        ring.animateTo(1f, tween(RING_MS))
    }

    /** The bin cannot eat: letters hop back into place, the button turns red and shakes. */
    suspend fun fail() {
        failed = true
        if (!motionOn()) return
        coroutineScope {
            hops.forEachIndexed { i, a ->
                launch {
                    delay(i * LETTER_STAGGER_MS)
                    a.snapTo(0f)
                    a.animateTo(1f, tween(HOP_MS, easing = LinearEasing))
                }
            }
            launch {
                shake.snapTo(0f)
                shake.animateTo(1f, tween(SHAKE_MS, easing = LinearEasing))
            }
        }
    }

    suspend fun reset() {
        failed = false
        eaten = false
        listOf(lid, shrink, shake).forEach { it.snapTo(0f) }
        ring.snapTo(1f)
        letters.forEach { it.snapTo(0f) }
        hops.forEach { it.snapTo(0f) }
    }

    private companion object {
        const val SHRINK_MS = 280
        const val RING_MS = 400
        const val HOP_MS = 480
        const val SHAKE_MS = 360
    }
}

/** Clips a pill to a circle around the bin as [p] goes 0 -> 1 (the mockup's inset clip, without changing the width). */
private class ShrinkShape(private val p: Float, private val binCenterX: Float, private val binSize: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val pad = 9f * density.density
        val r = binSize / 2 + pad
        val left = (binCenterX - r) * p
        val right = size.width - (size.width - (binCenterX + r)) * p
        val top = (size.height / 2 - r).coerceAtLeast(0f) * p
        val bottom = size.height - (size.height / 2 - r).coerceAtLeast(0f) * p
        val radius = (bottom - top) / 2
        return Outline.Rounded(RoundRect(left, top, right, bottom, CornerRadius(radius)))
    }
}

/**
 * The danger button with a bin icon whose lid and label are separate parts, so the "bin eats the label" scene can
 * play on it. The label is still one string for screen readers.
 */
@Composable
fun BinButton(label: String, scene: BinScene, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val error = MaterialTheme.colorScheme.error
    scene.prepare(label.length)
    // All x positions are in root coordinates; [binX] is the bin centre relative to the button's left edge.
    var originX by remember { mutableStateOf(0f) }
    var binRootX by remember { mutableStateOf(0f) }
    var binSize by remember { mutableStateOf(0f) }
    val binX = binRootX - originX
    val letterX = remember(label) { FloatArray(label.length) }
    val interaction = remember { MutableInteractionSource() }
    val red by animateColorAsState(if (scene.failed) error else Color.Transparent, label = "binFail")
    val content = if (scene.failed) MaterialTheme.colorScheme.onError else error
    val shakeX = scene.shake.value.let { t -> if (t == 0f || t == 1f) 0f else kotlin.math.sin(t * 4 * Math.PI.toFloat()) * 6f * (1 - t) }
    val sp = scene.shrink.value
    Box(
        modifier
            .onGloballyPositioned { originX = it.positionInRoot().x }
            .drawBehind {
                val t = scene.ring.value
                if (t < 1f) drawCircle(error, (binSize / 2 + 9.dp.toPx()) * (1f + 0.9f * t), Offset(binX, size.height / 2), alpha = 0.9f * (1 - t), style = Stroke(2.dp.toPx()))
            }
            .graphicsLayer { translationX = shakeX * density },
    ) {
        Row(
            Modifier
                .graphicsLayer {
                    if (sp > 0f) {
                        shape = ShrinkShape(sp, binX, binSize)
                        clip = true
                    }
                }
                .pressScale(interaction)
                .height(52.dp)
                .background(red, RoundedCornerShape(50))
                .border(2.dp, error.copy(alpha = 0.7f), RoundedCornerShape(50))
                .clickable(interaction, ripple(), enabled = !scene.eaten, role = Role.Button, onClick = onClick)
                .clearAndSetSemantics {
                    contentDescription = label
                    role = Role.Button
                }
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(18.dp).onGloballyPositioned { binRootX = it.positionInRoot().x + it.size.width / 2f; binSize = it.size.width.toFloat() },
            ) {
                Icon(KrIcons.BinBody, null, tint = content, modifier = Modifier.size(18.dp))
                Icon(
                    KrIcons.BinLid, null, tint = content,
                    modifier = Modifier.size(18.dp).graphicsLayer {
                        transformOrigin = TransformOrigin(0.15f, 0.27f)
                        rotationZ = -38f * scene.lid.value
                        translationY = -2f * density * scene.lid.value
                    },
                )
            }
            Spacer(Modifier.width(8.dp))
            label.forEachIndexed { i, ch ->
                val fly = scene.letters.getOrNull(i)?.value ?: 0f
                val hop = scene.hops.getOrNull(i)?.value ?: 0f
                Text(
                    ch.toString(), color = content, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp,
                    modifier = Modifier
                        .onGloballyPositioned { letterX[i] = it.positionInRoot().x + it.size.width / 2f - originX }
                        .graphicsLayer {
                            if (scene.eaten && fly >= 1f) {
                                alpha = 0f
                                return@graphicsLayer
                            }
                            if (fly > 0f) {
                                // X moves linearly, Y rises (ease-out) then falls (ease-in): a natural throw into the bin.
                                translationX = (binX - letterX[i]) * fly
                                translationY = throwY(fly) * density
                                rotationZ = -320f * fly
                                val s = if (fly < 0.45f) 1f else 1f - 0.65f * (fly - 0.45f) / 0.55f
                                scaleX = s
                                scaleY = s
                                alpha = 1f - ((fly - 0.45f) / 0.55f).coerceIn(0f, 1f)
                            } else if (hop > 0f && hop < 1f) {
                                translationY = hopY(hop) * density
                                rotationZ = if (hop < 0.4f) -25f * hop / 0.4f else -25f * (1 - (hop - 0.4f) / 0.6f)
                            }
                        },
                )
            }
        }
    }
}

private fun throwY(t: Float): Float = if (t < 0.45f) -24f * THROW_UP.transform(t / 0.45f) else -24f + 30f * THROW_DOWN.transform((t - 0.45f) / 0.55f)

private fun hopY(t: Float): Float = when {
    t < 0.4f -> -14f * THROW_UP.transform(t / 0.4f)
    t < 0.8f -> -14f + 16f * THROW_DOWN.transform((t - 0.4f) / 0.4f)
    else -> 2f * (1 - (t - 0.8f) / 0.2f)
}
