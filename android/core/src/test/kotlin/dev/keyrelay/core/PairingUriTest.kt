package dev.keyrelay.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.HexFormat

class PairingUriTest {
    private val fp = "nhDFng6PxrMUestQPWHqag0G99AYI1PSvnW94Q3IWDA"
    private val code = "oKGio6SlpqeoqaqrrK2urw"
    private fun link(query: String) = "keyrelay://pair?$query"
    private fun full(a: String = "homepc.tailnet-example.ts.net:8443,100.64.0.10,192.168.1.10", n: String = "homepc") =
        link("v=1&n=$n&a=$a&p=47111&fp=$fp&c=$code")
    private fun rejects(uri: String) = assertThrows(IllegalArgumentException::class.java) { PairingUri.parse(uri) }

    @Test
    fun parse_fullLink_readsAllFields() {
        val info = PairingUri.parse(full())
        assertEquals("homepc", info.pcName)
        assertEquals(
            listOf(Endpoint("homepc.tailnet-example.ts.net", 8443), Endpoint("100.64.0.10", 47111), Endpoint("192.168.1.10", 47111)),
            info.endpoints,
        )
        assertEquals("9e10c59e0e8fc6b3147acb503d61ea6a0d06f7d0182353d2be75bde10dc85830", HexFormat.of().formatHex(info.spkiSha256))
        assertArrayEquals(HexFormat.of().parseHex("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf"), info.code)
    }

    @Test
    fun parse_percentEncodedName_isDecoded() = assertEquals("Ofis PC", PairingUri.parse(full(n = "Ofis%20PC")).pcName)

    @Test
    fun parse_missingName_usesFirstHost() = assertEquals("192.168.1.10", PairingUri.parse(full(a = "192.168.1.10", n = "")).pcName)

    @Test
    fun parse_ipv6WithAndWithoutPort_parsed() {
        val info = PairingUri.parse(full(a = "[fd7a:115c::1]:443,fd7a:115c::2"))
        assertEquals(listOf(Endpoint("fd7a:115c::1", 443), Endpoint("fd7a:115c::2", 47111)), info.endpoints)
    }

    @Test
    fun parse_wrongVersion_rejected() { rejects(link("v=2&a=h&p=1&fp=$fp&c=$code")) }

    @Test
    fun parse_missingOrBadPort_rejected() {
        rejects(link("v=1&a=h&fp=$fp&c=$code"))
        rejects(link("v=1&a=h&p=0&fp=$fp&c=$code"))
        rejects(link("v=1&a=h:70000&p=1&fp=$fp&c=$code"))
    }

    @Test
    fun parse_fingerprintNot32Bytes_rejected() { rejects(link("v=1&a=h&p=1&fp=AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHw&c=$code")) }

    @Test
    fun parse_codeNot16Bytes_rejected() { rejects(link("v=1&a=h&p=1&fp=$fp&c=AQID")) }

    @Test
    fun parse_noAddresses_rejected() { rejects(link("v=1&a=&p=1&fp=$fp&c=$code")) }

    @Test
    fun parse_hostWithSpace_rejected() {
        rejects(link("v=1&a=pc%20x:1&p=1&fp=$fp&c=$code"))
        assertThrows(IllegalArgumentException::class.java) { Endpoint.parse("a b", 1) }
        assertThrows(IllegalArgumentException::class.java) { Endpoint.parse("a\u0007b", 1) }
    }

    @Test
    fun identityCode_windowsVector_matches() = assertEquals("TYIM-LHQO", identityCode(PairingUri.parse(full()).spkiSha256))

    @Test
    fun identityCode_not32Bytes_rejected() {
        assertThrows(IllegalArgumentException::class.java) { identityCode(ByteArray(31)) }
    }

    @Test
    fun parse_otherScheme_rejected() { rejects("https://example.com/pair?v=1") }

    @Test
    fun sni_hostName_isHost_ipLiterals_none() {
        assertEquals("homepc.tailnet-example.ts.net", Endpoint("homepc.tailnet-example.ts.net", 8443).sni)
        assertNull(Endpoint("192.168.1.10", 1).sni)
        assertNull(Endpoint("fd7a:115c::1", 1).sni)
    }

    @Test
    fun toString_thenParse_roundTripsIpv6() {
        val e = Endpoint("fd7a:115c::1", 443)
        assertEquals("[fd7a:115c::1]:443", e.toString())
        assertEquals(e, Endpoint.parse(e.toString(), 0))
    }
}
