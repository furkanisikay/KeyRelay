package dev.keyrelay.core

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** Opens the pinned TLS 1.3 socket: addresses in order, 2 s each, SNI for host names (protocol.md). */
object TlsDialer {
    const val CONNECT_TIMEOUT_MS = 2_000

    /**
     * TLS handshake after the TCP connect. Through Funnel the TCP connect ends at Tailscale's ingress, which only then
     * reaches the PC over the tailnet: the handshake took 1.3 s from home and more over a VPN, so 2 s dropped Funnel.
     * Only an address that accepted TCP waits this long; dead LAN or tailnet addresses still fail after 2 s.
     */
    const val HANDSHAKE_TIMEOUT_MS = 8_000

    /** Thrown when every address answered with a different key: the PC was reinstalled and must be paired again. */
    class PinMismatchException : IOException("server key does not match the paired PC")

    /** Returns the TLS socket, the TCP socket under it (closing that one never blocks) and the endpoint that answered. */
    fun dial(endpoints: List<Endpoint>, spkiSha256: ByteArray): Triple<SSLSocket, Socket, Endpoint> {
        var last = IOException("no endpoints")
        var mismatches = 0
        for (e in endpoints) {
            val trust = PinningTrustManager(spkiSha256)
            try {
                return open(e, trust)
            } catch (x: IOException) {
                last = x
                if (trust.mismatched) mismatches++
            }
        }
        // Only all addresses agreeing means reinstall; one hijacked address must not raise a false re-pair warning.
        throw if (endpoints.isNotEmpty() && mismatches == endpoints.size) PinMismatchException() else last
    }

    fun spkiSha256(cert: X509Certificate): ByteArray = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)

    private fun open(e: Endpoint, trust: PinningTrustManager): Triple<SSLSocket, Socket, Endpoint> {
        val context = SSLContext.getInstance("TLSv1.3").apply { init(null, arrayOf(trust), null) }
        val raw = Socket()
        try {
            raw.connect(InetSocketAddress(resolve(e.host), e.port), CONNECT_TIMEOUT_MS)
            raw.soTimeout = HANDSHAKE_TIMEOUT_MS
            raw.tcpNoDelay = true
            val tls = context.socketFactory.createSocket(raw, e.host, e.port, true) as SSLSocket
            tls.enabledProtocols = arrayOf("TLSv1.3")
            tls.sslParameters = tls.sslParameters.apply { serverNames = e.sni?.let { listOf(SNIHostName(it)) } ?: emptyList() }
            tls.startHandshake()
            return Triple(tls, raw, e)
        } catch (x: Exception) {
            raw.close()
            // An invalid SNI name throws IllegalArgumentException; it must fail this address, not kill the thread.
            throw x as? IOException ?: IOException(x)
        }
    }

    // Name lookups have no timeout of their own; an unanswered DNS query must not stall the address list.
    private fun resolve(host: String): InetAddress = try {
        CompletableFuture.supplyAsync { InetAddress.getByName(host) }.get(CONNECT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
    } catch (x: ExecutionException) {
        throw x.cause as? IOException ?: IOException(x.cause)
    } catch (x: TimeoutException) {
        throw SocketTimeoutException("name lookup timed out: $host")
    }

    /** Accepts exactly the paired PC's key; CA chain and host name are not checked (spec "Protokol"). */
    private class PinningTrustManager(private val pin: ByteArray) : X509TrustManager {
        @Volatile var mismatched = false

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            if (chain.isEmpty() || !MessageDigest.isEqual(spkiSha256(chain[0]), pin)) {
                mismatched = true
                throw CertificateException("server key does not match the paired PC")
            }
        }

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            throw CertificateException("client certificates are not used")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
