package dev.keyrelay.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PairedPcTest {
    private fun pc(n: Int, default: Boolean = false, endpoints: List<Endpoint> = listOf(Endpoint("10.0.0.$n", 47111))) =
        PairedPc(ByteArray(16) { n.toByte() }, "PC $n", endpoints, ByteArray(32) { n.toByte() },byteArrayOf(1, 2, 3), 1000L + n, default)

    @Test
    fun encode_thenDecode_roundTrips() {
        val original = listOf(pc(1, true, listOf(Endpoint("host.ts.net", 8443), Endpoint("fd7a::1", 443))), pc(2))
        val back = PairedPcCodec.decode(PairedPcCodec.encode(original))
        assertEquals(original.map { it.id }, back.map { it.id })
        assertEquals("PC 1", back[0].name)
        assertEquals(listOf(Endpoint("host.ts.net", 8443), Endpoint("fd7a::1", 443)), back[0].endpoints)
        assertArrayEquals(byteArrayOf(1, 2, 3), back[0].sealedToken)
        assertEquals(1001L, back[0].lastSeenMs)
        assertEquals(listOf(true, false), back.map { it.isDefault })
    }

    @Test
    fun decode_damagedLine_keepsTheOthers() {
        val text = PairedPcCodec.encode(listOf(pc(1))) + "\nv1 not-hex !!\n" + PairedPcCodec.encode(listOf(pc(2)))
        assertEquals(listOf(pc(1).id, pc(2).id), PairedPcCodec.decode(text).map { it.id })
    }

    @Test
    fun decode_empty_returnsEmpty() = assertTrue(PairedPcCodec.decode("").isEmpty())

    @Test
    fun upsert_sameId_replaces() {
        val list = listOf(pc(1)).upsert(pc(1).copy(lastSeenMs = 5))
        assertEquals(1, list.size)
        assertEquals(5L, list[0].lastSeenMs)
    }

    @Test
    fun upsert_newDefault_clearsOthers() {
        val list = listOf(pc(1, true), pc(2)).upsert(pc(3, true))
        assertEquals(listOf(false, false, true), list.map { it.isDefault })
        assertEquals(pc(3).id, list.defaultPc?.id)
    }

    @Test
    fun upsert_samePcKeyNewDeviceId_replacesOldEntry() {
        val again = PairedPc(ByteArray(16) { 9 }, "PC 1", pc(1).endpoints, pc(1).spkiSha256, byteArrayOf(4), 2000L, true)
        val list = listOf(pc(1, true), pc(2)).upsert(again)
        assertEquals(listOf(pc(2).id, again.id), list.map { it.id })
        assertEquals(again.id, list.defaultPc?.id)
    }

    @Test
    fun without_defaultRemoved_firstRemainingBecomesDefault() {
        val list = listOf(pc(1, true), pc(2), pc(3)).without(pc(1).id)
        assertEquals(listOf(true, false), list.map { it.isDefault })
        assertEquals(pc(2).id, list.defaultPc?.id)
    }

    @Test
    fun makeDefault_leavesExactlyOneDefault() {
        val list = listOf(pc(1, true), pc(2)).makeDefault(pc(2).id)
        assertEquals(listOf(false, true), list.map { it.isDefault })
    }

    @Test
    fun promote_knownEndpoint_movesItFirst_unknownIgnored() {
        val a = Endpoint("a", 1)
        val b = Endpoint("b", 2)
        val list = listOf(pc(1, true, listOf(a, b)))
        assertEquals(listOf(b, a), list.promote(pc(1).id, b)[0].endpoints)
        assertEquals(listOf(a, b), list.promote(pc(1).id, Endpoint("c", 3))[0].endpoints)
    }

    @Test
    fun defaultPc_noneMarked_isFirst() = assertEquals(pc(1).id, listOf(pc(1), pc(2)).defaultPc?.id)
}
