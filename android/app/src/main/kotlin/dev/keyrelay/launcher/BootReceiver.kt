package dev.keyrelay.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.keyrelay.service.RelayService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && AdbSetup.hasSecureSettingsPermission(context)) RelayService.start(context)
    }
}
