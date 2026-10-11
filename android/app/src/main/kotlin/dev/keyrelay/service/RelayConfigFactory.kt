package dev.keyrelay.service

import dev.keyrelay.core.Endpoint
import dev.keyrelay.core.defaultPc
import dev.keyrelay.helper.RelayConfig
import dev.keyrelay.settings.AppSettings

object RelayConfigFactory {
    /**
     * [uidOf] resolves packages with the public PackageManager (works on Android 11/12, unlike the hidden call).
     * [openToken] throws when the Keystore key is gone; that PC is then configured without a connection.
     */
    fun build(s: AppSettings, uidOf: (String) -> Int?, openToken: (ByteArray) -> ByteArray): RelayConfig {
        val pc = s.pcs.defaultPc
        val plainToken = pc?.let { runCatching { openToken(it.sealedToken) }.getOrNull() }
        val usable = if (plainToken != null) pc else null
        return RelayConfig().apply {
            endpoints = usable?.endpoints?.map(Endpoint::toString)?.toTypedArray() ?: emptyArray()
            spkiSha256 = usable?.spkiSha256 ?: ByteArray(0)
            deviceId = usable?.deviceId ?: ByteArray(0)
            token = plainToken ?: ByteArray(0)
            targetUids = s.targets.mapNotNull(uidOf).toIntArray()
            toggleKey = s.toggleKey
            pingIntervalMs = s.pingIntervalMs
            pongTimeoutMs = s.pongTimeoutMs
            sendTimeoutMs = s.sendTimeoutMs
            enabled = s.enabled
            showKeys = s.showKeys
        }
    }
}
