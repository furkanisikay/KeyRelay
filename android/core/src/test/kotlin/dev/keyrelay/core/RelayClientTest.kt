package dev.keyrelay.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class RelayClientTest {
    private class Events : RelayClient.Listener {
        val queue = LinkedBlockingQueue<String>()
        override fun onConnected(pcName: String, endpoint: Endpoint) { queue.put("connected $pcName $endpoint") }
        override fun onPcReady(ready: Boolean) { queue.put("ready $ready") }
        override fun onDisconnected(reason: String) { queue.put("disconnected") }
        override fun onRejected(reason: Int) { queue.put("rejected $reason") }
        override fun onRtt(ms: Int) { queue.put("rtt") }

        fun await(prefix: String, seconds: Long = 5): String {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (true) {
                val left = deadline - System.nanoTime()
                val e = queue.poll(left.coerceAtLeast(0), TimeUnit.NANOSECONDS) ?: error("no '$prefix' within $seconds s")
                if (e.startsWith(prefix)) return e
            }
        }
    }

    private val events = Events()
    private val closeables = mutableListOf<AutoCloseable>()
    private var client: RelayClient? = null

    @AfterEach
    fun tearDown() {
        client?.stop()
        closeables.forEach { it.close() }
    }

    private fun server(behavior: FakeRelayServer.Behavior = FakeRelayServer.Behavior()) = FakeRelayServer(behavior).also { closeables += it }
    private fun local(s: FakeRelayServer) = Endpoint("127.0.0.1", s.port)
    private fun unusedPort() = ServerSocket(0).use { it.localPort }

    private fun start(
        vararg endpoints: Endpoint,
        pin: ByteArray = FakeRelayServer.FINGERPRINT,
        ping: Long = 5_000,
        pong: Long = 10_000,
        send: Long = 1_000,
    ): RelayClient = RelayClient(ConnectionConfig(endpoints.toList(), pin, ByteArray(16), ByteArray(32), ping, pong, send), events)
        .also { client = it; it.start() }

    private inline fun <reified T : Frame> FakeRelayServer.next(): T {
        while (true) {
            val f = received.poll(5, TimeUnit.SECONDS) ?: error("no ${T::class.simpleName} received")
            if (f is T) return f
        }
    }

    @Test
    fun spkiSha256_testCertificate_matchesDotNetFingerprint() =
        assertArrayEquals(FakeRelayServer.FINGERPRINT, TlsDialer.spkiSha256(FakeRelayServer.certificate()))

    @Test
    fun start_pinnedServer_connectsSendsHelloAndReportsReady() {
        val s = server()
        start(local(s))
        assertEquals("connected TestPC 127.0.0.1:${s.port}", events.await("connected"))
        events.await("ready true")
        assertArrayEquals(ByteArray(16), s.next<Frame.Hello>().deviceId)
    }

    @Test
    fun start_wrongPin_reportsRepairAndStopsRetrying() {
        val s = server()
        start(local(s), pin = ByteArray(32))
        assertEquals("rejected ${RelayClient.REJECT_PIN_MISMATCH}", events.await("rejected"))
        Thread.sleep(1_000)
        assertEquals(1, s.connections.get())
        assertTrue(events.queue.none { it.startsWith("connected") || it.startsWith("disconnected") })
    }

    @Test
    fun start_wrongPinOnOneAddressOnly_keepsRetrying() {
        val s = server()
        start(Endpoint("127.0.0.1", unusedPort()), local(s), pin = ByteArray(32))
        assertEquals("disconnected", events.queue.poll(5, TimeUnit.SECONDS))
        Thread.sleep(1_000)
        assertTrue(s.connections.get() >= 2)
    }

    @Test
    fun start_firstEndpointDead_usesSecondAndMovesItFirst() {
        val s = server()
        val dead = Endpoint("127.0.0.1", unusedPort())
        val c = start(dead, local(s))
        events.await("connected")
        assertEquals(local(s), c.endpointOrder.first())
    }

    @Test
    fun sendKeySasLock_connected_serverReceivesThem() {
        val s = server()
        val c = start(local(s))
        events.await("ready true")
        c.sendKey(30, 1)
        c.sendSas()
        c.sendLock()
        val key = s.next<Frame.Key>()
        assertEquals(30 to 1, key.code to key.value)
        s.next<Frame.Sas>()
        s.next<Frame.Lock>()
    }

    @Test
    fun start_serverAnswersPings_staysConnectedAndMeasuresRtt() {
        val s = server()
        start(local(s), ping = 100, pong = 300)
        events.await("connected")
        events.await("rtt")
        Thread.sleep(1_000)
        assertTrue(events.queue.none { it == "disconnected" })
    }

    @Test
    fun start_serverIgnoresPings_disconnectsAfterPongTimeout() {
        val s = server(FakeRelayServer.Behavior(answerPings = false))
        start(local(s), ping = 100, pong = 300)
        events.await("connected")
        events.await("disconnected", seconds = 3)
    }

    @Test
    fun sendKey_serverStopsReading_dropsConnection() {
        val s = server(FakeRelayServer.Behavior(readAfterWelcome = false))
        val c = start(local(s), send = 200)
        events.await("ready true")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (events.queue.none { it == "disconnected" } && System.nanoTime() < deadline) {
            repeat(1_000) { c.sendKey(30, it % 2) }
            Thread.sleep(10)
        }
        assertTrue(events.queue.any { it == "disconnected" })
    }

    @Test
    fun sendTimeout_writerBlockedOnFullBuffer_dropsAndStopReturns() {
        val s = server(FakeRelayServer.Behavior(readAfterWelcome = false))
        val c = start(local(s), send = 50)
        client = null // a regression must fail below, not hang in tearDown
        events.await("ready true")
        // Bursts below the queue capacity, pauses above the send timeout: once the socket buffers are full the timer
        // drops the connection while the writer is blocked holding the TLS record lock that SSLSocket.close() waits for.
        assertTimeoutPreemptively(Duration.ofSeconds(20)) {
            while (events.queue.none { it == "disconnected" }) {
                repeat(200) { c.sendKey(30, it % 2) }
                Thread.sleep(100)
            }
        }
        assertTimeoutPreemptively(Duration.ofSeconds(2)) { c.stop() }
    }

    @Test
    fun start_rejected_reportsReasonAndStopsRetrying() {
        val s = server(FakeRelayServer.Behavior(reject = 2))
        start(local(s))
        events.await("rejected 2")
        Thread.sleep(1_500)
        assertEquals(1, s.connections.get())
    }

    @Test
    fun start_hostName_sendsSni_ipLiteral_sendsNone() {
        val s = server()
        start(Endpoint("localhost", s.port))
        events.await("connected")
        assertEquals(listOf("localhost"), s.sniNames.poll(5, TimeUnit.SECONDS))
        client!!.stop()
        start(local(s))
        events.await("connected")
        assertEquals(emptyList<String>(), s.sniNames.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun stop_connected_doesNotReconnect() {
        val s = server()
        val c = start(local(s))
        events.await("connected")
        c.stop()
        Thread.sleep(1_000)
        assertEquals(1, s.connections.get())
    }

    @Test
    fun stop_whileDialing_sendsNoHello() {
        val s = server()
        val proxy = ServerSocket(0, 50, InetAddress.getLoopbackAddress()).also { closeables += it }
        val accepted = CountDownLatch(1)
        val release = CountDownLatch(1)
        // Holds the TLS handshake inside the proxy until the client has been stopped.
        thread(isDaemon = true) {
            val a = proxy.accept()
            accepted.countDown()
            release.await()
            val b = Socket(InetAddress.getLoopbackAddress(), s.port)
            thread(isDaemon = true) { runCatching { a.inputStream.transferTo(b.outputStream) } }
            runCatching { b.inputStream.transferTo(a.outputStream) }
        }
        val c = start(Endpoint("127.0.0.1", proxy.localPort))
        assertTrue(accepted.await(5, TimeUnit.SECONDS))
        c.stop()
        release.countDown()
        assertNotNull(s.sniNames.poll(5, TimeUnit.SECONDS), "handshake did not complete")
        Thread.sleep(500)
        assertTrue(s.received.none { it is Frame.Hello })
    }

    @Test
    fun pair_accepted_returnsPaired() {
        val s = server()
        val reply = RelayClient.pair(listOf(local(s)), FakeRelayServer.FINGERPRINT, ByteArray(16), "Tab")
        assertArrayEquals(FakeRelayServer.PAIRED_TOKEN, (reply as Frame.Paired).token)
        assertEquals("Tab", s.next<Frame.Pair>().deviceName)
    }

    @Test
    fun pair_rejected_returnsReject() {
        val s = server(FakeRelayServer.Behavior(reject = 3))
        assertEquals(3, (RelayClient.pair(listOf(local(s)), FakeRelayServer.FINGERPRINT, ByteArray(16), "Tab") as Frame.Reject).reason)
    }

    @Test
    fun pair_wrongPin_throws() {
        val s = server()
        assertThrows(IOException::class.java) { RelayClient.pair(listOf(local(s)), ByteArray(32), ByteArray(16), "Tab") }
    }
}
