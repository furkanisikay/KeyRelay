package dev.keyrelay.core

/**
 * While the keyboard is relayed the mouse is grabbed too: its extra buttons go to the PC as KEY frames, everything
 * else is written to a uinput mouse so the Android pointer keeps working. Pure; allocates nothing.
 */
object MouseRouting {
    /** Name of the helper's uinput mouse; never picked as a mouse to grab (it would grab itself). */
    const val VIRTUAL_NAME = "KeyRelay virtual mouse"

    const val NOTHING = 0
    /** To the state machine: it keeps every button's held state and sends only the PC buttons. */
    const val TO_MACHINE = 1
    const val TO_VIRTUAL = 2

    private const val EV_KEY = 1
    private const val EV_REL = 2
    const val BTN_LEFT = 0x110
    private const val BTN_MIDDLE = 0x112
    const val BTN_TASK = 0x117
    private const val REL_HWHEEL = 6
    private const val REL_WHEEL = 8
    private const val REL_WHEEL_HI_RES = 11
    private const val REL_HWHEEL_HI_RES = 12

    /** Middle, side, extra, forward, back and task buttons: sent to the PC. */
    fun toPc(code: Int) = code in BTN_MIDDLE..BTN_TASK

    /** Every button the state machine tracks: left and right stay on the tablet, the rest go to the PC ([toPc]). */
    fun isButton(code: Int) = code in BTN_LEFT..BTN_TASK

    fun isMouse(node: InputNode) =
        node.name != VIRTUAL_NAME && node.hasEv(EV_REL) && node.hasKey(BTN_LEFT) && (BTN_MIDDLE..BTN_TASK).any(node::hasKey)

    /**
     * A bit set of [TO_MACHINE] and [TO_VIRTUAL]. Buttons go to the state machine even when not grabbed, as keyboard
     * keys do, so the grab never flips while one is held. The rest only while grabbed; otherwise Android reads the
     * mouse itself.
     */
    fun route(type: Int, code: Int, grabbed: Boolean): Int = when {
        type != EV_KEY || !isButton(code) -> if (grabbed) TO_VIRTUAL else NOTHING
        toPc(code) -> TO_MACHINE
        grabbed -> TO_MACHINE or TO_VIRTUAL
        else -> TO_MACHINE
    }

    /** The value to write to the virtual mouse: a wheel's sign flipped when the user chose that direction. */
    fun virtualValue(type: Int, code: Int, value: Int, invertHorizontal: Boolean, invertVertical: Boolean): Int = when {
        type != EV_REL -> value
        invertHorizontal && (code == REL_HWHEEL || code == REL_HWHEEL_HI_RES) -> -value
        invertVertical && (code == REL_WHEEL || code == REL_WHEEL_HI_RES) -> -value
        else -> value
    }
}
