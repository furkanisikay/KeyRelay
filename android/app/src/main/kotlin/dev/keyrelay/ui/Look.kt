package dev.keyrelay.ui

import android.animation.ValueAnimator
import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import dev.keyrelay.service.StatusKind
import dev.keyrelay.ui.theme.LocalStatusColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.random.Random

/** The mockup's state looks: each has one color trio (the aurora) and one badge shape. */
enum class Look { RELAY, READY, WAITING, ERROR, PAUSED, VERIFY }

fun lookOf(kind: StatusKind) = when (kind) {
    StatusKind.RELAYING -> Look.RELAY
    StatusKind.READY -> Look.READY
    StatusKind.ERROR, StatusKind.REPAIR -> Look.ERROR
    StatusKind.PAUSED, StatusKind.NOT_PAIRED -> Look.PAUSED
    else -> Look.WAITING
}

/** The light line counts as drawn in these looks; moving between drawn and broken plays the connect scene. */
val Look.linked get() = this == Look.RELAY || this == Look.READY || this == Look.PAUSED

@Immutable
data class Aura(val a1: Color, val a2: Color, val a3: Color)

fun auraOf(look: Look) = when (look) {
    Look.RELAY -> Aura(Color(0xFF3B6CFF), Color(0xFF22D3EE), Color(0xFF7A5CFF))
    Look.READY -> Aura(Color(0xFF19C98C), Color(0xFF3EE6C1), Color(0xFF0EA5A5))
    Look.WAITING -> Aura(Color(0xFFF2B544), Color(0xFFFF8A3D), Color(0xFFFFD66B))
    Look.ERROR -> Aura(Color(0xFFFF5468), Color(0xFFFF8A65), Color(0xFFB3264A))
    Look.PAUSED -> Aura(Color(0xFF7C86A8), Color(0xFFA78BFA), Color(0xFF4B5675))
    Look.VERIFY -> Aura(Color(0xFF8B5CF6), Color(0xFFA78BFA), Color(0xFF6D28D9))
}

const val STATE_MS = 400

/** The look's colors, gliding for 400 ms when the look changes. */
@Composable
fun animatedAura(look: Look): Aura {
    val target = auraOf(look)
    val spec = tween<Color>(STATE_MS, easing = MOCKUP_EASE)
    val a1 by animateColorAsState(target.a1, spec, label = "a1")
    val a2 by animateColorAsState(target.a2, spec, label = "a2")
    val a3 by animateColorAsState(target.a3, spec, label = "a3")
    return Aura(a1, a2, a3)
}

/** Animations are off when the system animator duration scale is 0; scenes then jump to their last frame. */
fun motionOn() = ValueAnimator.areAnimatorsEnabled()

// One noise tile for the whole process; the grain is a static texture.
private val noise by lazy {
    val n = 96
    val rnd = Random(7)
    val px = IntArray(n * n) { val v = rnd.nextInt(256); (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
    Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/**
 * The static mesh backdrop: three soft color fields, their dimmer mirror and a grain texture. The brushes are rebuilt
 * only when the colors change (400 ms after a state change); otherwise nothing redraws.
 */
@Composable
fun Modifier.auroraBackdrop(aura: Aura, strength: Float = 1f): Modifier {
    val c = LocalStatusColors.current
    val alpha = c.auroraAlpha * strength
    val grain = c.grainAlpha
    return drawWithCache {
        val w = size.width
        val h = size.height
        fun field(color: Color, x: Float, y: Float, r: Float, a: Float) =
            Brush.radialGradient(0f to color.copy(alpha = a), 0.7f to Color.Transparent, center = Offset(x * w, y * h), radius = r * maxOf(w, h))
        val fields = listOf(
            field(aura.a1, 0.22f, 0.30f, 0.40f, 0.75f * alpha), field(aura.a2, 0.78f, 0.62f, 0.34f, 0.55f * alpha),
            field(aura.a3, 0.50f, 0.96f, 0.40f, 0.55f * alpha),
            field(aura.a1, 0.78f, 0.70f, 0.40f, 0.30f * alpha), field(aura.a2, 0.22f, 0.38f, 0.34f, 0.22f * alpha),
        )
        val grainBrush = ShaderBrush(ImageShader(noise, TileMode.Repeated, TileMode.Repeated))
        onDrawBehind {
            fields.forEach { drawRect(it) }
            drawRect(grainBrush, alpha = grain, blendMode = BlendMode.Overlay)
        }
    }
}

fun glyphOf(look: Look) = when (look) {
    Look.RELAY -> KrIcons.Arrow
    Look.READY -> KrIcons.Check
    Look.ERROR -> KrIcons.Bang
    Look.PAUSED -> KrIcons.Pause
    Look.WAITING, Look.VERIFY -> KrIcons.Dots
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun shapeOf(look: Look): RoundedPolygon = when (look) {
    Look.RELAY -> MaterialShapes.Cookie9Sided
    Look.READY -> MaterialShapes.Circle
    Look.WAITING, Look.VERIFY -> MaterialShapes.Cookie4Sided
    Look.ERROR -> MaterialShapes.Diamond
    Look.PAUSED -> MaterialShapes.Oval
}

/**
 * The status badge: on a state change its shape morphs (cookie, circle, rounded square, diamond, oval) and its glyph
 * pops. Without [glyph] it is only the colored shape, for content placed on top.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MorphBadge(look: Look, size: Dp, modifier: Modifier = Modifier, glyph: Boolean = true) {
    val aura = animatedAura(look)
    var shown by remember { mutableStateOf(look) }
    var morph by remember { mutableStateOf(Morph(shapeOf(look), shapeOf(look))) }
    val progress = remember { Animatable(1f) }
    val pop = remember { Animatable(1f) }
    val path = remember { Path() }
    LaunchedEffect(look) {
        if (look == shown) return@LaunchedEffect
        morph = Morph(shapeOf(shown), shapeOf(look))
        shown = look
        if (!motionOn()) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        progress.snapTo(0f)
        pop.snapTo(0f)
        coroutineScope {
            launch { progress.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 300f)) }
            launch { pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 500f)) }
        }
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val p = morph.toPath(progress.value, path)
            val brush = Brush.sweepGradient(listOf(aura.a1, aura.a2, aura.a3, aura.a1), Offset(0.5f, 0.5f))
            // MaterialShapes are normalized to a unit square.
            withTransform({ scale(this@Canvas.size.width, this@Canvas.size.height, Offset.Zero) }) { drawPath(p, brush) }
        }
        if (glyph) Icon(
            glyphOf(shown), null, tint = Color.White,
            modifier = Modifier.size(size * 0.46f).graphicsLayer {
                val v = pop.value
                scaleX = 0.4f + 0.6f * v
                scaleY = scaleX
                rotationZ = -40f * (1 - v)
                alpha = v.coerceIn(0f, 1f)
            },
        )
    }
}
