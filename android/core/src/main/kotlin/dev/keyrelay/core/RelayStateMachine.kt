package dev.keyrelay.core

/**
 * Where the keyboard goes (spec "Yonlendirme durum makinesi"). Not thread-safe: the helper drives it under one lock.
 * [onKey] allocates nothing.
 */
class RelayStateMachine(private val out: Output) {
    interface Output {
        fun sendKey(code: Int, value: Int)
        fun sendSas()
        fun sendLock()
        fun setGrab(on: Boolean)
        fun onToggled(enabled: Boolean, override: Boolean)
    }

    var toggleKey = KEY_GRAVE
    var enabled = true
        private set
    var override = false
        private set
    var grabbed = false
        private set

    private var connected = false
    private var pcReady = false
    private var targetForeground = false
    private val held = BooleanArray(KEY_CNT)
    private var heldCount = 0
    // A consumed trigger key stays consumed until its release, so its repeat and up never leak to either side.
    private val swallowed = BooleanArray(KEY_CNT)

    fun setEnabled(on: Boolean) {
        if (enabled != on) {
            enabled = on
            out.onToggled(enabled, override)
        }
        applyGrab()
    }

    fun setConnected(on: Boolean) {
        connected = on
        applyGrab()
    }

    fun setPcReady(on: Boolean) {
        pcReady = on
        applyGrab()
    }

    /** [targetEvent]: the event came from a target process (foreground change or death); such events clear [override]. */
    fun onForeground(targetForeground: Boolean, targetEvent: Boolean) {
        this.targetForeground = targetForeground
        if (targetEvent && override) {
            override = false
            out.onToggled(enabled, override)
        }
        applyGrab()
    }

    fun onKey(code: Int, value: Int) {
        if (code < 0 || code >= KEY_CNT || value < 0 || value > 2) return
        if (value == 1 && !held[code]) { held[code] = true; heldCount++ }
        if (value == 0 && held[code]) { held[code] = false; heldCount-- }
        when {
            swallowed[code] -> if (value == 0) swallowed[code] = false
            value == 1 && code == toggleKey && ctrl() && alt() -> { swallowed[code] = true; toggle() }
            !grabbed -> Unit
            // Left and right only hold the grab; the helper writes them to its virtual mouse.
            MouseRouting.isButton(code) && !MouseRouting.toPc(code) -> Unit
            value == 1 && code == KEY_DELETE && ctrl() && alt() -> { swallowed[code] = true; out.sendSas() }
            value == 1 && code == KEY_L && meta() -> { swallowed[code] = true; out.sendLock() }
            else -> out.sendKey(code, value)
        }
        applyGrab()
    }

    /**
     * A node vanished (Bluetooth out of range): its key-ups may never arrive, so release what it held. [mouse]: a mouse
     * node releases only mouse buttons, a keyboard node only keys.
     */
    fun releaseHeld(mouse: Boolean) {
        for (code in held.indices) if (held[code] && MouseRouting.isButton(code) == mouse) onKey(code, 0)
    }

    private fun toggle() {
        if (targetForeground) {
            enabled = !enabled
        } else {
            override = !override
            if (override) enabled = true
        }
        out.onToggled(enabled, override)
    }

    private fun want() = enabled && connected && pcReady && (targetForeground || override)

    // Only with no key held: a side that saw a key-down but not its key-up would keep that key stuck.
    private fun applyGrab() {
        if (heldCount == 0 && want() != grabbed) {
            grabbed = !grabbed
            out.setGrab(grabbed)
        }
    }

    private fun ctrl() = held[KEY_LEFTCTRL] || held[KEY_RIGHTCTRL]
    private fun alt() = held[KEY_LEFTALT] || held[KEY_RIGHTALT]
    private fun meta() = held[KEY_LEFTMETA] || held[KEY_RIGHTMETA]

    companion object {
        const val KEY_CNT = 0x300
        const val KEY_LEFTCTRL = 29
        const val KEY_RIGHTCTRL = 97
        const val KEY_LEFTALT = 56
        const val KEY_RIGHTALT = 100
        const val KEY_LEFTMETA = 125
        const val KEY_RIGHTMETA = 126
        const val KEY_SPACE = 57
        const val KEY_GRAVE = 41
        const val KEY_INSERT = 110
        const val KEY_DELETE = 111
        const val KEY_L = 38

        /** Keys the user can pick for Ctrl+Alt+key in Settings. */
        val TOGGLE_KEYS = intArrayOf(KEY_SPACE, KEY_GRAVE, KEY_INSERT)
    }
}
