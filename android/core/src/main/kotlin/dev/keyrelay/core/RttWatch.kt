package dev.keyrelay.core

/**
 * Decides when the helper tells the app a new round trip: only while a screen showing the latency is visible, so
 * with the screen off a ping costs the app nothing. State changes are sent regardless; they do not pass through here.
 * Thread-safe: the watch flag comes from a binder thread, round trips from the connection thread.
 */
class RttWatch {
    private var watching = false

    /** A pong arrived: true when the app must hear the round trip now. */
    @Synchronized
    fun onRtt(): Boolean = watching

    /** A screen started or stopped watching: true when the latest round trip must be sent at once (watching just began). */
    @Synchronized
    fun setWatching(on: Boolean): Boolean {
        val started = on && !watching
        watching = on
        return started
    }
}
