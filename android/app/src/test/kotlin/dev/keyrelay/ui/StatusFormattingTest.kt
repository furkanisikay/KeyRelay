package dev.keyrelay.ui

import dev.keyrelay.R
import dev.keyrelay.core.Frame
import dev.keyrelay.core.KeyPulse
import dev.keyrelay.core.RelayStateMachine
import dev.keyrelay.service.PairResult
import dev.keyrelay.service.StatusKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StatusFormattingTest {
    private fun app(pkg: String, label: String) = AppEntry(pkg, label, null)

    @Test
    fun formatRtt_unknown_isDash_known_isMs() {
        assertEquals("—", formatRtt(-1))
        assertEquals("4 ms", formatRtt(4))
    }

    @Test
    fun shortcutLabel_toggleKeys_named() {
        assertEquals("Ctrl Alt Space", shortcutLabel(RelayStateMachine.KEY_SPACE))
        assertEquals("Ctrl Alt `", shortcutLabel(RelayStateMachine.KEY_GRAVE))
        assertEquals("Ctrl Alt Ins", shortcutLabel(RelayStateMachine.KEY_INSERT))
    }

    @Test
    fun keyLabel_hiddenSignal_isBlankCap() = assertEquals("•", keyLabel(KeyPulse.NO_KEY))

    @Test
    fun keyLabel_shownKeys_lettersDigitsAndNames() {
        assertEquals(listOf("Q", "A", "Z", "1", "0", "Tab", "Ctrl", "Win", "F12"), listOf(16, 30, 44, 2, 11, 15, 29, 125, 88).map(::keyLabel))
    }

    @Test
    fun lookOf_everyStatus_hasALook_andLinkMatchesTheLine() {
        assertEquals(Look.RELAY, lookOf(StatusKind.RELAYING))
        assertEquals(Look.ERROR, lookOf(StatusKind.REPAIR))
        assertEquals(Look.WAITING, lookOf(StatusKind.NO_WIFI))
        assertEquals(listOf(true, true, true, false, false), listOf(Look.RELAY, Look.READY, Look.PAUSED, Look.WAITING, Look.ERROR).map { it.linked })
    }

    @Test
    fun lockTimeMs_codeResolvesWithinHalfASecond() {
        assertEquals(80L, lockTimeMs(0))
        assertTrue(lockTimeMs(7) <= 500)
    }

    @Test
    fun pairedIdOf_savedPcButCodeRejected_isNotPaired() {
        // The PC is already saved (re-pairing), the expired code is rejected: the dialog must say "not paired".
        assertEquals(null, pairedIdOf(PairResult.Rejected(3)))
        assertEquals(null, pairedIdOf(PairResult.Unreachable("java.lang.IllegalStateException")))
        assertEquals("ab", pairedIdOf(PairResult.Paired("pc", "ab")))
    }

    @Test
    fun pairFailRes_pairingFromOutside_saysJoinHomeNetwork_otherRejectsStayGeneric() {
        assertEquals(R.string.pair_not_allowed_here, pairFailRes(PairResult.Rejected(Frame.Reject.PAIRING_NOT_ALLOWED_HERE)))
        assertEquals(R.string.pair_rejected, pairFailRes(PairResult.Rejected(Frame.Reject.INVALID_CODE)))
        // A reason from a newer PC this app does not know yet.
        assertEquals(R.string.pair_rejected, pairFailRes(PairResult.Rejected(99)))
        assertEquals(null, pairFailRes(PairResult.Paired("pc", "ab")))
    }

    @Test
    fun wires_connectEveryCellOfTheGrid() {
        assertEquals((0..7).toSet(), WIRES.flatMap { listOf(it.first, it.second) }.toSet())
        assertEquals(10, WIRES.size)
    }

    @Test
    fun order_selectedFirstThenLabelIgnoringCase() {
        val apps = listOf(app("z", "Zoom"), app("a", "anydesk"), app("t", "TeamViewer"), app("b", "Bitwarden"))
        assertEquals(listOf("t", "a", "b", "z"), TargetApps.order(apps, setOf("t")).map { it.packageName })
    }
}
