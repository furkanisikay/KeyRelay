package dev.keyrelay.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.keyrelay.ui.theme.LocalStatusColors

/** The mockup's line icon set: 24 unit grid, 2 unit round strokes, tinted by LocalContentColor. */
object KrIcons {
    private fun icon(name: String, vararg paths: String, width: Float = 2f) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        paths.forEach {
            addPath(
                addPathNodes(it), stroke = SolidColor(Color.Black), strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

    val Tablet by lazy { icon("tab", "M6.5 2h11a2.5 2.5 0 0 1 2.5 2.5v15a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z", "M11 18h2") }
    val Pc by lazy { icon("pc", "M5 4h14a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2z", "M8 20h8M12 16v4") }
    val Sliders by lazy { icon("sliders", "M4 7h10M18 7h2M4 17h4M12 17h8", "M14 7a2 2 0 1 0 4 0a2 2 0 1 0-4 0z", "M8 17a2 2 0 1 0 4 0a2 2 0 1 0-4 0z") }
    val Arrow by lazy { icon("arrow", "M5 12h13M13 6l6 6-6 6") }
    val Check by lazy { icon("check", "M5 12.5l4.5 4.5L19 7") }
    val Dots by lazy { icon("dots", "M6 12h.01M12 12h.01M18 12h.01", width = 3.4f) }
    val Bang by lazy { icon("bang", "M12 6v7M12 17.5v.5", width = 3f) }
    val Pause by lazy { icon("pause", "M9 6v12M15 6v12", width = 3f) }
    val Lock by lazy { icon("lock", "M7 11h10a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2z", "M8 11V8a4 4 0 0 1 8 0v3") }
    val Arrows by lazy { icon("arrows", "M4 9h13l-3-3M20 15H7l3 3") }
    val Scan by lazy { icon("scan", "M3 7V5a2 2 0 0 1 2-2h2M17 3h2a2 2 0 0 1 2 2v2M21 17v2a2 2 0 0 1-2 2h-2M7 21H5a2 2 0 0 1-2-2v-2M7 12h10") }
    val Keyboard by lazy { icon("kbd", "M4 6h16a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2z", "M6 10h.01M10 10h.01M14 10h.01M18 10h.01M7 14h10") }
    val Gauge by lazy { icon("gauge", "M4 18a9 9 0 1 1 16 0", "M12 14l4-5") }
    val Shield by lazy { icon("shield", "M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6z", "M9 12l2 2 4-4") }
    val Fingerprint by lazy {
        icon("fp", "M12 11v3a8 8 0 0 1-2 5M8 10a4 4 0 0 1 8 0v2M5 13a7 7 0 0 1 .5-6M18.5 7A7 7 0 0 1 19 13c0 2-.4 4-1 5.5M12 3a9 9 0 0 1 4 1")
    }
    val Route by lazy { icon("route", "M4 19a2 2 0 1 0 4 0a2 2 0 1 0-4 0z", "M16 5a2 2 0 1 0 4 0a2 2 0 1 0-4 0z", "M8 19h7a3.5 3.5 0 0 0 0-7H9a3.5 3.5 0 0 1 0-7h7") }
    val Tunnel by lazy { icon("tunnel", "M3 20V11a9 9 0 0 1 18 0v9M7 20v-8a5 5 0 0 1 10 0v8M3 20h18") }
    val Eye by lazy { icon("eye", "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0z") }
    val EyeOff by lazy { icon("eyeOff", "M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z", "M9 12a3 3 0 1 0 6 0a3 3 0 1 0-6 0z", "M3 3l18 18") }
    val BinBody by lazy { icon("binBody", "M6 6.5l1 13.5h10l1-13.5", "M10 10.5v6M14 10.5v6") }
    val Info by lazy { icon("info", "M12 21a9 9 0 1 0 0-18a9 9 0 1 0 0 18z", "M12 11v5", "M12 7.5h.01") }
    val BinLid by lazy { icon("binLid", "M3.5 6.5h17", "M9 6.5V4h6v2.5") }

    /** The wizard's wireless debugging mark in separate parts, centre first, so the arcs can light up one by one. */
    val WifiParts by lazy {
        listOf("M12 20h.01", "M8.53 16.11a6 6 0 0 1 6.95 0", "M5 12.55a11 11 0 0 1 14.08 0", "M1.42 9a16 16 0 0 1 21.16 0")
            .mapIndexed { i, d -> icon("wifi$i", d, width = if (i == 0) 2.6f else 1.8f) }
    }
}

/**
 * The brand mark: an isometric keycap with an arrow leaving it, over a light ring. Static; the cap only sinks while
 * pressed (the mockup's 250 ms spring).
 */
@Composable
fun KeyRelayLogo(size: Dp, modifier: Modifier = Modifier, interaction: MutableInteractionSource = remember { MutableInteractionSource() }) {
    val c = LocalStatusColors.current
    val ring = c.capLeft
    val pressed by interaction.collectIsPressedAsState()
    val sink by animateFloatAsState(if (pressed) 6f else 0f, spring(dampingRatio = 0.5f), label = "logoPress")
    val left = remember { Path().apply { moveTo(8f, 26f); lineTo(32f, 38f); lineTo(32f, 50f); lineTo(8f, 38f); close() } }
    val right = remember { Path().apply { moveTo(56f, 26f); lineTo(32f, 38f); lineTo(32f, 50f); lineTo(56f, 38f); close() } }
    val arrow = remember { Path().apply { moveTo(6f, 12f); lineTo(17f, 12f); moveTo(13f, 8f); lineTo(17f, 12f); lineTo(13f, 16f) } }
    // The SVG's matrix(1 .5 -1 .5 32 14): a square seen from above at 30 degrees.
    val iso = remember { Matrix().apply { this[0, 0] = 1f; this[0, 1] = 0.5f; this[1, 0] = -1f; this[1, 1] = 0.5f; this[3, 0] = 32f; this[3, 1] = 14f } }
    Canvas(modifier.size(size)) {
        val s = this.size.width / 64f
        withTransform({ scale(s, s, Offset.Zero) }) {
            drawOval(ring.copy(alpha = 0.35f), Offset(6f, 29f), Size(52f, 26f), style = Stroke(1.6f))
            drawPath(left, c.capLeft)
            drawPath(right, c.capRight)
            withTransform({ translate(0f, sink); transform(iso) }) {
                drawRoundRect(c.capTop, Offset.Zero, Size(24f, 24f), CornerRadius(5f))
                drawPath(arrow, c.capInk, style = Stroke(2.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
