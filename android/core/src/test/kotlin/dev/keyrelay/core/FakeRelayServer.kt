package dev.keyrelay.core

import java.io.BufferedInputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

/** Stand-in for KeyRelay.Service in tests and device checks; serves the checked-in test certificate. */
class FakeRelayServer(
    private val behavior: Behavior = Behavior(),
    port: Int = 0,
    bind: InetAddress = InetAddress.getLoopbackAddress(),
) : AutoCloseable {
    class Behavior(
        val reject: Int = 0,
        val answerPings: Boolean = true,
        val readAfterWelcome: Boolean = true,
        val log: Boolean = false,
        /** Waits this long after the TCP accept before the TLS handshake, like Tailscale Funnel reaching the PC. */
        val handshakeDelayMs: Long = 0,
    )

    val received = LinkedBlockingQueue<Frame>()
    val sniNames = LinkedBlockingQueue<List<String>>()
    val connections = AtomicInteger()
    private val server = sslContext().serverSocketFactory.createServerSocket(port, 50, bind) as SSLServerSocket
    val port: Int get() = server.localPort

    init {
        server.enabledProtocols = arrayOf("TLSv1.3")
        thread(isDaemon = true, name = "fake-server") {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() as SSLSocket }.getOrNull() ?: break
                connections.incrementAndGet()
                thread(isDaemon = true) {
                    runCatching { handle(socket) }.onFailure { if (behavior.log) println("connection ended: $it") }
                }
            }
        }
    }

    private fun handle(socket: SSLSocket) {
        socket.use { s ->
            Thread.sleep(behavior.handshakeDelayMs)
            s.startHandshake()
            sniNames.put((s.session as ExtendedSSLSession).requestedServerNames.map { (it as SNIHostName).asciiName })
            val input = BufferedInputStream(s.inputStream)
            val out = s.outputStream
            val buf = ByteArray(FrameCodec.MAX_LENGTH)
            while (true) {
                val frame = FrameCodec.read(input, buf) ?: return
                received.put(frame)
                if (behavior.log) println("${System.currentTimeMillis()} ${describe(frame)}")
                when (frame) {
                    is Frame.Hello -> {
                        if (behavior.reject != 0) {
                            out.write(FrameCodec.encode(Frame.Reject(behavior.reject)))
                            return
                        }
                        out.write(FrameCodec.encode(Frame.Welcome(PC_NAME)))
                        out.write(FrameCodec.encode(Frame.State(true)))
                        if (!behavior.readAfterWelcome) Thread.sleep(Long.MAX_VALUE)
                    }
                    is Frame.Pair -> {
                        val reply = if (behavior.reject != 0) Frame.Reject(behavior.reject) else Frame.Paired(PAIRED_ID, PAIRED_TOKEN)
                        out.write(FrameCodec.encode(reply))
                        return
                    }
                    is Frame.Ping -> if (behavior.answerPings) out.write(FrameCodec.encode(Frame.Pong(frame.seq)))
                    else -> Unit
                }
            }
        }
    }

    override fun close() = server.close()

    companion object {
        const val PC_NAME = "TestPC"
        val FINGERPRINT: ByteArray = Base64.getUrlDecoder().decode("nhDFng6PxrMUestQPWHqag0G99AYI1PSvnW94Q3IWDA")
        val PAIRED_ID = ByteArray(16) { it.toByte() }
        val PAIRED_TOKEN = ByteArray(32) { (32 + it).toByte() }
        private const val PASSWORD = "test"

        private fun pem(type: String): ByteArray {
            val text = FakeRelayServer::class.java.getResource("/test-server.pem")!!.readText()
            return Base64.getMimeDecoder().decode(text.substringAfter("-----BEGIN $type-----").substringBefore("-----END $type-----"))
        }

        fun certificate(): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(pem("CERTIFICATE").inputStream()) as X509Certificate

        fun sslContext(): SSLContext {
            val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pem("PRIVATE KEY")))
            val store = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry("server", key, PASSWORD.toCharArray(), arrayOf(certificate()))
            }
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, PASSWORD.toCharArray()) }
            return SSLContext.getInstance("TLSv1.3").apply { init(keys.keyManagers, null, null) }
        }

        fun describe(f: Frame) = when (f) {
            is Frame.Key -> "KEY code=${f.code} value=${f.value}"
            is Frame.Ping -> "PING ${f.seq}"
            else -> f.javaClass.simpleName.uppercase()
        }
    }
}

/** `gradlew :core:fakeServer -Pport=47111`: plays the PC for device checks until the Windows service exists (P3). */
fun main(args: Array<String>) {
    val port = args.firstOrNull()?.toIntOrNull() ?: 47111
    FakeRelayServer(FakeRelayServer.Behavior(log = true), port, InetAddress.getByName("0.0.0.0"))
    val hosts = NetworkInterface.networkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses().toList() }.filterIsInstance<Inet4Address>().map { it.hostAddress }
    val b64 = Base64.getUrlEncoder().withoutPadding()
    println(
        "keyrelay://pair?v=1&n=FakePC&a=${hosts.joinToString(",")}&p=$port" +
            "&fp=${b64.encodeToString(FakeRelayServer.FINGERPRINT)}&c=${b64.encodeToString(ByteArray(FrameCodec.CODE_SIZE))}",
    )
    Thread.sleep(Long.MAX_VALUE)
}
