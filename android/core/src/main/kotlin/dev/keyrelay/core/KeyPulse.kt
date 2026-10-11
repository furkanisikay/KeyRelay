package dev.keyrelay.core

/**
 * Decides what the helper tells the app about a relayed key: nothing unless the app's status screen is watching,
 * at most once per [minGapMs] (4 per second, so binder traffic never runs at typing speed), and the key code only
 * when the user chose to show keys. Presses in between are dropped, never queued. Allocates nothing. Not thread-safe:
 * the helper calls it under its lock.
 */
class KeyPulse(private val minGapMs: Long = MIN_GAP_MS) {
    private var last = Long.MIN_VALUE / 2

    fun allow(nowMs: Long): Boolean {
        if (nowMs - last < minGapMs) return false
        last = nowMs
        return true
    }

    /** The one gate in front of the binder: [NOTHING], [NO_KEY] (a key, which one untold) or the key code. */
    fun next(code: Int, value: Int, nowMs: Long, watching: Boolean, showKeys: Boolean): Int {
        if (value != 1 || !watching || !allow(nowMs)) return NOTHING
        return signal(code, showKeys)
    }

    companion object {
        const val MIN_GAP_MS = 250L

        /** Send nothing. */
        const val NOTHING = -2

        /** The signal for "a key, which one is not told". */
        const val NO_KEY = -1

        /** Whether a key code may leave the helper: only when the user chose to show keys. */
        fun signal(code: Int, showKeys: Boolean) = if (showKeys) code else NO_KEY
    }
}
