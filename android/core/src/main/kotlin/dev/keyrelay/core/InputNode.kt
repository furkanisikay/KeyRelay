package dev.keyrelay.core

/** One /dev/input/event* node as reported by its evdev ioctls. */
class InputNode(
    val path: String,
    val name: String,
    val phys: String,
    val uniq: String,
    val evBits: Int,
    val keyBits: ByteArray,
) {
    fun hasEv(type: Int): Boolean = evBits and (1 shl type) != 0

    fun hasKey(code: Int): Boolean =
        code / 8 < keyBits.size && keyBits[code / 8].toInt() and (1 shl (code % 8)) != 0
}

/** A physical keyboard: the node that types letters plus its sibling key-only nodes (media, system keys). */
data class Keyboard(val name: String, val nodes: List<InputNode>)
