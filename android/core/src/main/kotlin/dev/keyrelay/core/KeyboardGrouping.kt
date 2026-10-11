package dev.keyrelay.core

object KeyboardGrouping {
    private const val EV_KEY = 1
    private const val EV_REL = 2
    private const val EV_ABS = 3
    private const val KEY_A = 30
    private const val KEY_SPACE = 57
    private const val BTN_TOUCH = 330

    fun group(nodes: List<InputNode>): List<Keyboard> =
        nodes.filter { it.isTyping() }
            .map { primary -> Keyboard(primary.name, nodes.filter { it === primary || (it.isKeyOnly() && it.sameDevice(primary)) }) }
            .distinctBy { kb -> kb.nodes.map { it.path }.toSet() }

    // A touchpad reports EV_ABS with BTN_TOUCH; other EV_ABS siblings (some media nodes) are still keys.
    private fun InputNode.isKeyOnly() = hasEv(EV_KEY) && !hasEv(EV_REL) && !(hasEv(EV_ABS) && hasKey(BTN_TOUCH))

    private fun InputNode.isTyping() = isKeyOnly() && hasKey(KEY_A) && hasKey(KEY_SPACE)

    // Bluetooth nodes of one device share uniq (its address); phys is the host adapter and is shared by every device.
    private fun InputNode.sameDevice(other: InputNode): Boolean =
        if (uniq.isNotEmpty() || other.uniq.isNotEmpty()) uniq == other.uniq
        else physRoot().isNotEmpty() && physRoot() == other.physRoot()

    private fun InputNode.physRoot() = phys.substringBeforeLast("/input", phys)
}
