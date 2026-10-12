package dev.keyrelay.ui

import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import dev.keyrelay.R
import dev.keyrelay.service.HelperConnection
import dev.keyrelay.ui.theme.JetBrainsMono
import dev.keyrelay.ui.theme.LocalStatusColors
import java.util.Locale

/** Lets screens opt into the PC card -> PC detail shared bounds transition. */
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** The mockup's tight letter spacing on screen titles and headlines. */
val TightSpacing = (-0.05).em

/** The mockup's --ease: cubic-bezier(.2, .8, .2, 1). */
internal val MOCKUP_EASE = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

@Composable
fun ScreenTopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        onBack?.let {
            BoxIconButton(it, stringResource(R.string.cd_back)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, null) }
            Spacer(Modifier.width(12.dp))
        }
        Text(
            title, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = TightSpacing,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        actions()
    }
}

/** The mockup's top bar button: a 48 dp glass square whose corners round further while pressed. */
@Composable
fun BoxIconButton(onClick: () -> Unit, description: String, content: @Composable () -> Unit) {
    val c = LocalStatusColors.current
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    Box(
        Modifier.pressScale(interaction, 0.94f).size(48.dp).clip(shape).background(c.glass).border(1.dp, c.line, shape)
            .clickable(interaction, ripple(), role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { content() } }
}

@Composable
fun SectionTitle(text: String, info: String? = null) = Row(
    Modifier.padding(start = 6.dp, top = if (info == null) 24.dp else 12.dp, bottom = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
) {
    Text(
        text.uppercase(Locale.getDefault()),
        fontSize = 12.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.14.em,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    info?.let { InfoButton(text, it) }
}

/** The small (i) next to a technical title: a short plain-language explanation in a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoButton(title: String, text: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    val description = stringResource(R.string.info_cd, title)
    Box(
        Modifier.minimumInteractiveComponentSize().clip(CircleShape).clickable(role = Role.Button) { open = true }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(KrIcons.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    if (open) {
        ModalBottomSheet(onDismissRequest = { open = false }) {
            Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
                Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = TightSpacing)
                Text(text, fontSize = 15.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** The mockup's glass card: a translucent surface over the state backdrop with a hairline edge. */
@Composable
fun KrCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    onClick: (() -> Unit)? = null,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalStatusColors.current
    val interaction = remember { MutableInteractionSource() }
    val click = if (onClick != null) Modifier.pressScale(interaction, 0.985f) else Modifier
    Column(
        modifier.then(click).clip(shape).background(c.glass).border(1.dp, c.line, shape)
            .then(if (onClick != null) Modifier.clickable(interaction, ripple(), onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** While the calling screen is visible the helper sends every round trip; otherwise a ping costs the app nothing. */
@Composable
fun WatchRtt() = LifecycleStartEffect(Unit) {
    HelperConnection.watchRtt(true)
    onStopOrDispose { HelperConnection.watchRtt(false) }
}

@Composable
fun SwitchCard(title: String, hint: String, on: Boolean, info: String? = null, onChange: (Boolean) -> Unit) {
    KrCard(Modifier.fillMaxWidth(), padding = 0.dp) {
        // The whole row is the switch, so TalkBack reads its title with the on/off state.
        Row(
            Modifier.toggleable(on, role = Role.Switch, onValueChange = onChange).padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    info?.let { InfoButton(title, it) }
                }
                Text(hint, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            }
            Switch(checked = on, onCheckedChange = null)
        }
    }
}

/** The small tinted icon square at the top of a tile (the mockup's .kic). */
@Composable
fun TileIcon(icon: ImageVector, tint: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(11.dp)
    Box(
        modifier.size(34.dp).clip(shape).background(tint.copy(alpha = 0.16f)).border(1.dp, tint.copy(alpha = 0.3f), shape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp)) }
}

/** A pill on the hero; [dot] is the status dot (with its glow), left out for plain facts. */
@Composable
fun StatusChip(text: String, dot: Color?, mono: Boolean = false, icon: ImageVector? = null, glow: Boolean = true) {
    val c = LocalStatusColors.current
    Row(
        Modifier.height(34.dp).clip(CircleShape).background(c.glass2).border(1.dp, c.line, CircleShape).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        dot?.let {
            Box(
                Modifier.size(9.dp).drawBehind {
                    if (glow) drawCircle(it, size.minDimension, alpha = 0.25f)
                    drawCircle(it)
                },
            )
            Spacer(Modifier.width(8.dp))
        }
        icon?.let {
            Icon(it, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text, fontSize = 13.sp, fontWeight = if (mono) FontWeight.Medium else FontWeight.Bold,
            fontFamily = if (mono) JetBrainsMono else null, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The "Varsayilan" badge. */
@Composable
fun DefaultBadge() {
    val accent = MaterialTheme.colorScheme.primary
    Text(
        stringResource(R.string.pc_default), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.secondary,
        modifier = Modifier.clip(CircleShape).background(accent.copy(alpha = 0.22f)).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** The PC avatar: state colored gradient when the PC is connected, a dim glass square otherwise. */
@Composable
fun PcAvatar(on: Boolean, size: Dp, modifier: Modifier = Modifier) {
    val aura = auraOf(Look.RELAY)
    val c = LocalStatusColors.current
    val shape = RoundedCornerShape(size / 3)
    Box(
        modifier.size(size).clip(shape).then(
            if (on) Modifier.background(Brush.linearGradient(listOf(aura.a1, aura.a3))) else Modifier.background(c.glass2),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(KrIcons.Pc, null, tint = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size * 0.44f))
    }
}

/** Mixes [a] into [b] the way the mockup's color-mix(in oklab, a x%, b) is used for tinted edges. */
fun mix(a: Color, b: Color, amountOfA: Float) = lerp(b, a, amountOfA)

/** Same key on the PC card and the PC detail container; no-op outside a shared transition. */
@Composable
fun Modifier.sharedPcBounds(id: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalNavAnimatedContentScope.current
    return with(shared) { this@sharedPcBounds.sharedBounds(rememberSharedContentState("pc-$id"), animated) }
}

@Composable
fun appLabel(packageName: String?): String {
    val context = LocalContext.current
    return remember(packageName) {
        packageName?.let { pkg ->
            runCatching { context.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() } }.getOrDefault(pkg)
        }.orEmpty()
    }
}
