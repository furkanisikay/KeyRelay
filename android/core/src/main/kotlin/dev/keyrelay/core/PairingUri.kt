package dev.keyrelay.core

import java.net.URLDecoder
import java.util.Base64

/** One address from the QR `a` list. */
data class Endpoint(val host: String, val port: Int) {
    /** Host name for TLS SNI (Funnel routes by it); IP literals send none. */
    val sni: String? get() = if (isIpLiteral(host)) null else host

    override fun toString() = if (':' in host) "[$host]:$port" else "$host:$port"

    companion object {
        private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

        fun isIpLiteral(host: String) = ':' in host || IPV4.matches(host)

        /** `host`, `host:port`, `[v6]`, `[v6]:port` or a bare IPv6 literal; [defaultPort] fills a missing port. */
        fun parse(text: String, defaultPort: Int): Endpoint {
            val t = text.trim()
            val (host, port) = when {
                t.startsWith("[") -> {
                    val end = t.indexOf(']')
                    require(end > 1) { "bad IPv6 address: $t" }
                    t.substring(1, end) to t.substring(end + 1).removePrefix(":").ifEmpty { null }
                }
                t.count { it == ':' } == 1 -> t.substringBefore(':') to t.substringAfter(':')
                else -> t to null
            }
            val p = if (port == null) defaultPort else port.toIntOrNull() ?: throw IllegalArgumentException("bad port: $t")
            // Space splits PairedPcCodec lines, comma splits the endpoint list.
            require(host.isNotEmpty() && host.none { it.isWhitespace() || it.isISOControl() || it == ',' }) { "bad host: $t" }
            require(p in 1..65535) { "port out of range: $t" }
            return Endpoint(host, p)
        }
    }
}

private const val BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

/** The PC identity code Windows shows next to the QR: first 40 bits of the SPKI hash in base32, as XXXX-XXXX. */
fun identityCode(spkiSha256: ByteArray): String {
    require(spkiSha256.size == PairingUri.SPKI_SIZE) { "fingerprint must be ${PairingUri.SPKI_SIZE} bytes" }
    val bits = (0 until 5).fold(0L) { acc, i -> (acc shl 8) or (spkiSha256[i].toLong() and 0xFF) }
    val code = (0 until 8).map { BASE32[(bits shr (35 - 5 * it)).toInt() and 31] }.joinToString("")
    return code.take(4) + "-" + code.drop(4)
}

class PairingInfo(val pcName: String, val endpoints: List<Endpoint>, val spkiSha256: ByteArray, val code: ByteArray)

/** The QR content of protocol.md pairing section: keyrelay://pair?v=1&n=&a=&p=&fp=&c= */
object PairingUri {
    const val PREFIX = "keyrelay://pair?"
    internal const val SPKI_SIZE = 32

    fun parse(uri: String): PairingInfo {
        require(uri.startsWith(PREFIX)) { "not a KeyRelay pairing link" }
        val q = uri.removePrefix(PREFIX).split('&').filter { it.isNotEmpty() }
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
        require(q["v"] == "1") { "unsupported pairing version: ${q["v"]}" }
        val port = q["p"]?.toIntOrNull() ?: throw IllegalArgumentException("missing port")
        require(port in 1..65535) { "port out of range: $port" }
        val endpoints = q["a"].orEmpty().split(',').filter { it.isNotBlank() }.map { Endpoint.parse(it, port) }
        require(endpoints.isNotEmpty()) { "no addresses" }
        val fp = base64Url(q["fp"])
        require(fp.size == SPKI_SIZE) { "fingerprint must be $SPKI_SIZE bytes" }
        val code = base64Url(q["c"])
        require(code.size == FrameCodec.CODE_SIZE) { "code must be ${FrameCodec.CODE_SIZE} bytes" }
        return PairingInfo(q["n"].orEmpty().ifBlank { endpoints.first().host }, endpoints, fp, code)
    }

    private fun base64Url(value: String?): ByteArray = Base64.getUrlDecoder().decode(value.orEmpty())
}
