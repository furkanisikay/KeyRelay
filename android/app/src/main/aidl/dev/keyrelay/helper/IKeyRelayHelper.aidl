package dev.keyrelay.helper;

import dev.keyrelay.helper.IStatusListener;
import dev.keyrelay.helper.RelayConfig;

interface IKeyRelayHelper {
    void configure(in RelayConfig config);
    void setEnabled(boolean enabled);
    void setStatusListener(IStatusListener listener);
    int ping();
    /** Whether the app's status screen is on screen; key events are sent only while it is. */
    oneway void setKeyWatch(boolean on);
    /** Whether a screen showing the latency is on screen; round trips are sent only while one is (state changes always). */
    oneway void setRttWatch(boolean on);
}
