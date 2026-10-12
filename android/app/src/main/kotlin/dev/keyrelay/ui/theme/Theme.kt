package dev.keyrelay.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.keyrelay.R

/** The approved mockup's palette; always used (no wallpaper colors) so the app keeps its own identity. */
object Brand {
    val Ink = Color(0xFF0B111C)
    val Node = Color(0xFF111A29)
    val Text = Color(0xFFEEF3FF)
    val Muted = Color(0xFF9AA8C0)
    val Faint = Color(0xFF5E6C8C)
    val Accent = Color(0xFF4C7DFF)
    val AccentLight = Color(0xFF89A8FF)
    val Ready = Color(0xFF2FD49C)
    val Waiting = Color(0xFFF2B544)
    val Error = Color(0xFFFF6B6B)
    val Verify = Color(0xFF8B5CF6)
}

/**
 * The mockup's colors Material has no slot for: status (ready, waiting), the glass surfaces drawn over the state
 * backdrop, the keycap faces and how strongly the backdrop shows.
 */
@Immutable
data class StatusColors(
    val ready: Color,
    val waiting: Color,
    val faint: Color,
    val glass: Color,
    val glass2: Color,
    val line: Color,
    val node: Color,
    val capTop: Color,
    val capLeft: Color,
    val capRight: Color,
    val capInk: Color,
    val auroraAlpha: Float,
    val grainAlpha: Float,
)

private val DarkStatus = StatusColors(
    ready = Brand.Ready, waiting = Brand.Waiting, faint = Brand.Faint,
    glass = Color(0x94111A2C), glass2 = Color(0x0FFFFFFF), line = Color(0x1AFFFFFF), node = Brand.Node,
    capTop = Color(0xFFF2F6FF), capLeft = Color(0xFF9FB4FF), capRight = Color(0xFF6F8BEA), capInk = Color(0xFF1E3A8F),
    auroraAlpha = 0.9f, grainAlpha = 0.11f,
)

private val LightStatus = StatusColors(
    ready = Color(0xFF0E9F6E), waiting = Color(0xFFB7791F), faint = Color(0xFF8A97B0),
    glass = Color(0xA8FFFFFF), glass2 = Color(0x0D0B111C), line = Color(0x1A0B111C), node = Color.White,
    capTop = Color.White, capLeft = Color(0xFFC3D1FF), capRight = Color(0xFF9DB2F2), capInk = Color(0xFF1E3A8F),
    auroraAlpha = 0.6f, grainAlpha = 0.07f,
)

val LocalStatusColors = staticCompositionLocalOf { DarkStatus }

private val DarkBrand = darkColorScheme(
    primary = Brand.Accent, onPrimary = Color.White, primaryContainer = Color(0xFF1B2D5E), onPrimaryContainer = Brand.AccentLight,
    secondary = Brand.AccentLight, onSecondary = Color(0xFF0B1433),
    background = Brand.Ink, onBackground = Brand.Text, surface = Brand.Ink, onSurface = Brand.Text,
    surfaceVariant = Color(0xFF18233A), onSurfaceVariant = Brand.Muted,
    surfaceContainerLow = Brand.Node, surfaceContainer = Brand.Node, surfaceContainerHigh = Color(0xFF18233A),
    outline = Color(0xFF24314B), outlineVariant = Color(0xFF24314B), error = Brand.Error, onError = Color(0xFF2B0606),
)

private val LightBrand = lightColorScheme(
    primary = Color(0xFF2F5BEA), onPrimary = Color.White, primaryContainer = Color(0xFFDCE4FF), onPrimaryContainer = Color(0xFF0B1433),
    secondary = Color(0xFF2A4FC9), onSecondary = Color.White,
    background = Color(0xFFF7F9FD), onBackground = Color(0xFF0B1222), surface = Color(0xFFF7F9FD), onSurface = Color(0xFF0B1222),
    surfaceVariant = Color(0xFFE4E9F2), onSurfaceVariant = Color(0xFF4D5A73),
    surfaceContainerLow = Color.White, surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFEDF1F8),
    outline = Color(0xFFC9D2E3), outlineVariant = Color(0xFFDDE3EE), error = Color(0xFFD93A3A), onError = Color.White,
)

private val Manrope = FontFamily(
    listOf(400, 500, 600, 700, 800).map { w ->
        Font(R.font.manrope, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

/** For numbers and codes: latency, keys, addresses, fingerprints. */
val JetBrainsMono = FontFamily(
    listOf(500, 700, 800).map { w ->
        Font(R.font.jetbrains_mono, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

@Composable
fun KeyRelayTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialExpressiveTheme(
            colorScheme = if (dark) DarkBrand else LightBrand,
            motionScheme = MotionScheme.expressive(),
            typography = Typography(fontFamily = Manrope),
            content = content,
        )
    }
}
