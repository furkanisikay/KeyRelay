package dev.keyrelay.core

/**
 * Key repeat made at the source: Android switches the kernel's repeat off and the helper grabs the keyboard, so no
 * value=2 ever arrives, and the PC must not invent repeats (a late key-up over the network types extra characters).
 * Only the last pressed repeatable key repeats; any other press, its release or [stop] ends it at once.
 * Pure and allocation-free; the helper calls it under its lock and sleeps until [nextMs] (forever while idle).
 */
class KeyRepeat {
    var delayMs = DEFAULT_DELAY_MS
    var intervalMs = DEFAULT_INTERVAL_MS

    /** The repeating key, or [NONE]. */
    var code = NONE
        private set

    /** When the next repeat is due; [Long.MAX_VALUE] while nothing repeats. */
    var nextMs = Long.MAX_VALUE
        private set

    /** A real key event (value 0 or 1) after the state machine handled it; [grabbed] = keys go to the PC. */
    fun onKey(code: Int, value: Int, grabbed: Boolean, nowMs: Long) {
        when (value) {
            1 -> if (grabbed && repeats(code)) {
                this.code = code
                nextMs = nowMs + delayMs
            } else {
                stop()
            }
            0 -> if (code == this.code) stop()
        }
    }

    /** The key to send as a repeat now, or [NONE]. After a late wake-up it does not catch up with a burst. */
    fun due(nowMs: Long): Int {
        if (code == NONE || nowMs < nextMs) return NONE
        nextMs = nowMs + intervalMs
        return code
    }

    fun stop() {
        code = NONE
        nextMs = Long.MAX_VALUE
    }

    companion object {
        const val NONE = -1

        // Used only until the app sends the system values (ViewConfiguration) with the first config.
        const val DEFAULT_DELAY_MS = 500L
        const val DEFAULT_INTERVAL_MS = 50L

        private const val LAST_STANDARD_KEY = 111 // KEY_DELETE; above it media, system and vendor keys

        // Modifiers, the lock keys and SysRq/Print Screen never repeat on a PC keyboard either.
        private val NO_REPEAT = intArrayOf(29, 42, 54, 56, 97, 100, 58, 69, 70, 99)

        /** Letters, digits, punctuation, Enter/Backspace/Tab/Space, F1-F12, keypad, arrows and the edit block. */
        fun repeats(code: Int) = code in 1..LAST_STANDARD_KEY && code !in NO_REPEAT
    }
}
