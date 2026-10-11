package dev.keyrelay.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.unit.IntOffset
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import dev.keyrelay.core.PairingInfo
import dev.keyrelay.settings.AppSettings

sealed interface Route {
    data object Status : Route
    data object Pcs : Route
    data class PcDetail(val id: String) : Route
    data object Settings : Route
    data object Wizard : Route
}

private const val PC_PREFIX = "pc:"

// Saved as text so a rotation or a process restart keeps the open screen.
private val backStackSaver = listSaver<SnapshotStateList<Route>, String>(
    save = { stack -> stack.map { if (it is Route.PcDetail) PC_PREFIX + it.id else it.toString() } },
    restore = { saved ->
        saved.map { s ->
            listOf(Route.Status, Route.Pcs, Route.Settings, Route.Wizard).firstOrNull { it.toString() == s } ?: Route.PcDetail(s.removePrefix(PC_PREFIX))
        }.toMutableStateList()
    },
)

/** One step back, the same for the in-app arrows and the system back; the last screen stays (back then leaves the app). */
internal fun MutableList<Route>.back(): Boolean {
    if (size <= 1) return false
    removeAt(lastIndex)
    return true
}

@Composable
fun KeyRelayApp(settings: AppSettings, pendingLink: PairingInfo?, onLinkHandled: () -> Unit) {
    // Pairings finished from a keyrelay:// link; the wizard's PC step moves on when this grows.
    var linkPairs by rememberSaveable { mutableIntStateOf(0) }
    val backStack = rememberSaveable(saver = backStackSaver) { mutableStateListOf<Route>(if (settings.onboardingDone) Route.Status else Route.Wizard) }
    val spatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val effects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    // NavDisplay's own back handling did not get the system back on the device (SM-X910: back on PCs left the app),
    // while the wizard's activity BackHandler did; this uses that path. Composed first, so screen handlers still win.
    BackHandler(enabled = backStack.size > 1) { backStack.back() }
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
            NavDisplay(
                backStack = backStack,
                sharedTransitionScope = this,
                transitionSpec = { (slideInHorizontally(spatial) { it / 5 } + fadeIn(effects)) togetherWith fadeOut(effects) },
                popTransitionSpec = { fadeIn(effects) togetherWith (slideOutHorizontally(spatial) { it / 5 } + fadeOut(effects)) },
                entryProvider = entryProvider {
                    entry<Route.Status> { StatusScreen(settings, onOpenPcs = { backStack += Route.Pcs }, onOpenSettings = { backStack += Route.Settings }) }
                    entry<Route.Pcs> { PcsScreen(settings, onOpenPc = { backStack += Route.PcDetail(it) }, onBack = { backStack.back() }) }
                    // Only pops itself: after "Remove" the leaving screen sees its PC gone and calls onBack a second time.
                    entry<Route.PcDetail> { key ->
                        PcDetailScreen(settings, key.id, onBack = { if (backStack.lastOrNull() == key) backStack.back() })
                    }
                    entry<Route.Settings> { SettingsScreen(settings, onRunWizard = { backStack += Route.Wizard }, onBack = { backStack.back() }) }
                    entry<Route.Wizard> {
                        WizardScreen(settings, linkPairs) {
                            backStack.clear()
                            backStack += Route.Status
                        }
                    }
                },
            )
        }
    }
    pendingLink?.let { info ->
        PairConfirmDialog(info) { paired, _ ->
            if (paired) linkPairs++
            onLinkHandled()
        }
    }
}
