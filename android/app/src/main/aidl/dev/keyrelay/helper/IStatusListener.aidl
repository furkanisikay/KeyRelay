package dev.keyrelay.helper;

import dev.keyrelay.helper.HelperStatus;

oneway interface IStatusListener {
    void onStatus(in HelperStatus status);
    /** "A key went to the PC", at most 4 per second; carries no key, state or time on purpose. */
    void onKeyPulse();
    /** Same, with the evdev key code; sent only while the user chose to show keys (RelayConfig.showKeys). */
    void onKey(int code);
}
