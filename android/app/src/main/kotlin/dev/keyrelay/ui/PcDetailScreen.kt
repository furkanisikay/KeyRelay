package dev.keyrelay.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keyrelay.R
import dev.keyrelay.core.Endpoint
import dev.keyrelay.core.PairedPc
import dev.keyrelay.core.identityCode
import dev.keyrelay.core.makeDefault
import dev.keyrelay.core.without
import dev.keyrelay.service.AppState
import dev.keyrelay.settings.AppSettings
import dev.keyrelay.settings.SettingsStore
import dev.keyrelay.ui.theme.JetBrainsMono
import dev.keyrelay.ui.theme.LocalStatusColors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import java.util.Base64
import java.util.Locale

/** The narrow (phone, portrait, split screen) PC detail route; on wide screens the same pane sits next to the list. */
@Composable
fun PcDetailScreen(settings: AppSettings, id: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val pc = settings.pcs.firstOrNull { it.id == id }
    if (pc == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val relay by AppState.relay.collectAsStateWithLifecycle()
    val rtt by AppState.rtt.collectAsStateWithLifecycle()
    WatchRtt()
    val bin = remember(pc.id) { BinScene() }
    val scope = rememberCoroutineScope()
    var removeFailed by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().auroraBackdrop(animatedAura(if (relay.connected && pc.isDefault) Look.RELAY else Look.PAUSED), 0.55f)
            .verticalScroll(rememberScrollState()).windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        ScreenTopBar(pc.name, onBack)
        Column(Modifier.sharedPcBounds(pc.id)) {
            PcDetailPane(pc, relay.connected && pc.isDefault, rtt, bin, removeFailed, wide = false, onRemove = {
                scope.launch {
                    removeFailed = !removePc(context, pc, bin, fold = null)
                    if (!removeFailed) onBack()
                }
            })
        }
    }
}

/**
 * Removes [pc] after the confirmation: the bin eats the button label, the list card (if any) folds away, then the
 * settings write runs (the animation only presents it; the write never depends on it finishing). Returns false (and
 * plays the bin's failure) when the write fails.
 */
suspend fun removePc(context: Context, pc: PairedPc, bin: BinScene, fold: (suspend () -> Unit)?): Boolean {
    val write = CompletableDeferred<Boolean>()
    try {
        bin.eat()
        fold?.invoke()
    } finally {
        // The user confirmed; the write runs even if the screen is left during the animation (scope cancelled).
        AppState.scope.launch { write.complete(runCatching { SettingsStore.update(context) { it.copy(pcs = it.pcs.without(pc.id)) } }.isSuccess) }
    }
    val ok = write.await()
    if (!ok) {
        bin.reset()
        bin.fail()
    }
    return ok
}

/** The PC detail: big avatar and name, status chips, addresses, identity code, fingerprint and the actions. */
@Composable
fun PcDetailPane(
    pc: PairedPc,
    connected: Boolean,
    rttMs: Int,
    bin: BinScene,
    removeFailed: Boolean,
    wide: Boolean,
    onRemove: () -> Unit,
) {
    val context = LocalContext.current
    val c = LocalStatusColors.current
    var confirmRemove by remember { mutableStateOf(false) }
    // The avatar grows into place and the name slides in each time another PC is shown.
    val enter = remember(pc.id) { Animatable(if (motionOn()) 0f else 1f) }
    LaunchedEffect(pc.id) { enter.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 400f)) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 22.dp)) {
            PcAvatar(
                connected, 96.dp,
                Modifier.graphicsLayer {
                    val s = 0.6f + 0.4f * enter.value
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin(0f, 0f)
                },
            )
            Column(Modifier.padding(start = 22.dp)) {
                Text(
                    pc.name, fontSize = if (wide) 56.sp else 42.sp, lineHeight = if (wide) 54.sp else 42.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.06).em,
                    modifier = Modifier.padding(bottom = 10.dp).graphicsLayer { translationX = -24f * density * (1 - enter.value); alpha = enter.value.coerceIn(0f, 1f) },
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val seen = DateUtils.getRelativeTimeSpanString(pc.lastSeenMs).toString()
                    StatusChip(
                        if (connected) stringResource(R.string.pc_connected_status, formatRtt(rttMs)) else stringResource(R.string.pc_idle_status, seen),
                        if (connected) c.ready else c.faint, glow = connected,
                    )
                    StatusChip(stringResource(R.string.pc_tls), dot = null, icon = KrIcons.Lock)
                    if (pc.isDefault) DefaultBadge()
                }
            }
        }
        val cards: List<@Composable ColumnScope.() -> Unit> = listOf(
            {
                CardHead(KrIcons.Route, stringResource(R.string.pc_addresses), stringResource(R.string.info_addresses))
                pc.endpoints.forEachIndexed { i, e -> AddressRow(i, e) }
            },
            {
                CardHead(KrIcons.Shield, stringResource(R.string.pc_identity), stringResource(R.string.info_identity))
                CodeCells(identityCode(pc.spkiSha256), Modifier.padding(top = 10.dp))
                CardHead(KrIcons.Fingerprint, stringResource(R.string.pc_fingerprint), stringResource(R.string.info_fingerprint), Modifier.padding(top = 16.dp))
                Text(Base64.getUrlEncoder().withoutPadding().encodeToString(pc.spkiSha256), fontFamily = JetBrainsMono, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            },
        )
        BoxWithConstraints {
            if (wide && maxWidth >= 560.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    cards.forEachIndexed { i, card -> InfoCard(Modifier.weight(1f).rise(pc.id, i), card) }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    cards.forEachIndexed { i, card -> InfoCard(Modifier.fillMaxWidth().rise(pc.id, i), card) }
                }
            }
        }
        Row(Modifier.padding(top = 12.dp).rise(pc.id, 2), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val interaction = remember { MutableInteractionSource() }
            Button(
                onClick = { AppState.scope.launch { SettingsStore.update(context) { it.copy(pcs = it.pcs.makeDefault(pc.id)) } } },
                enabled = !pc.isDefault,
                interactionSource = interaction,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f), contentColor = MaterialTheme.colorScheme.onSurface),
                modifier = Modifier.height(52.dp).pressScale(interaction),
            ) { Text(stringResource(R.string.pc_make_default), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp) }
            BinButton(stringResource(R.string.pc_remove), bin, onClick = { confirmRemove = true })
        }
        if (removeFailed) Text(stringResource(R.string.pc_remove_failed), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            icon = { MorphBadge(Look.ERROR, 60.dp) },
            title = { Text(stringResource(R.string.pc_remove_title, pc.name), fontWeight = FontWeight.ExtraBold, letterSpacing = TightSpacing) },
            text = { Text(stringResource(R.string.pc_remove_body)) },
            confirmButton = { TextButton({ confirmRemove = false; onRemove() }) { Text(stringResource(R.string.action_ok), fontWeight = FontWeight.ExtraBold) } },
            dismissButton = { TextButton({ confirmRemove = false }) { Text(stringResource(R.string.action_cancel), fontWeight = FontWeight.ExtraBold) } },
        )
    }
}

@Composable
private fun InfoCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) =
    KrCard(modifier, shape = RoundedCornerShape(26.dp), padding = 18.dp, content = content)

@Composable
private fun CardHead(icon: ImageVector, text: String, info: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        TileIcon(icon, auraOf(Look.RELAY).a1)
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp).weight(1f, fill = false))
        InfoButton(text, info)
    }
}

/** Home or office network (private range, link-local, .local) versus an address reached over the internet. */
internal fun isSameNetwork(host: String): Boolean {
    val h = host.lowercase(Locale.ROOT).removeSurrounding("[", "]")
    if (h.endsWith(".local")) return true
    if (':' in h) return h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")
    val p = h.split('.').map { it.toIntOrNull() ?: return false }
    if (p.size != 4) return false
    return p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) || (p[0] == 169 && p[1] == 254)
}

@Composable
private fun AddressRow(index: Int, endpoint: Endpoint) {
    val address = endpoint.toString()
    val c = LocalStatusColors.current
    val accent = auraOf(Look.RELAY).a1
    // The first address is the one tried first.
    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(8.dp)).background(if (index == 0) accent else c.glass2),
            contentAlignment = Alignment.Center,
        ) {
            Text("${index + 1}", fontFamily = JetBrainsMono, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, color = if (index == 0) Color.White else MaterialTheme.colorScheme.onSurface)
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(
                stringResource(if (isSameNetwork(endpoint.host)) R.string.addr_lan else R.string.addr_internet),
                fontSize = 13.sp, fontWeight = FontWeight.Bold,
            )
            Text(address, fontFamily = JetBrainsMono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
