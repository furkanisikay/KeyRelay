package dev.keyrelay

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.keyrelay.core.PairingInfo
import dev.keyrelay.core.PairingUri
import dev.keyrelay.service.RelayService
import dev.keyrelay.settings.SettingsStore
import dev.keyrelay.ui.KeyRelayApp
import dev.keyrelay.ui.theme.KeyRelayTheme

class MainActivity : ComponentActivity() {
    /** The keyrelay:// link waiting for the user's confirmation, kept as text so it survives recreation. */
    private var pendingUri by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity or a launch from recents carries the old intent; that link was already answered.
        val fromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        pendingUri = when {
            savedInstanceState != null -> savedInstanceState.getString(PENDING_LINK)
            fromHistory -> null
            else -> linkOf(intent)
        }
        if (checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) RelayService.start(applicationContext)
        setContent {
            KeyRelayTheme {
                // The root Surface provides onBackground as content color; without it uncolored text and icons are black.
                Surface(color = MaterialTheme.colorScheme.background) {
                    val settings by remember { SettingsStore.flow(applicationContext) }.collectAsStateWithLifecycle(null)
                    val link = remember(pendingUri) { pendingUri?.let(::parseLink) }
                    settings?.let { KeyRelayApp(it, link) { pendingUri = null } }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // An open confirmation must not silently switch to another PC under the user's finger.
        if (pendingUri == null) pendingUri = linkOf(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(PENDING_LINK, pendingUri)
    }

    private fun parseLink(uri: String): PairingInfo? = runCatching { PairingUri.parse(uri) }.getOrNull()

    private fun linkOf(intent: Intent): String? = intent.dataString?.takeIf { parseLink(it) != null }

    private companion object {
        const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
        const val PENDING_LINK = "pending_link"
    }
}
