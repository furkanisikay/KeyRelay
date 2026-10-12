package dev.keyrelay.core

import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/** What the client needs to reach one paired PC; the three times are user settings (protocol.md "Canlilik"). */
class ConnectionConfig(
    val endpoints: List<Endpoint>,
    val spkiSha256: ByteArray,
    val deviceId: ByteArray,
    val token: ByteArray,
    val pingIntervalMs: Long = DEFAULT_PING_INTERVAL_MS,
    val pongTimeoutMs: Long = DEFAULT_PONG_TIMEOUT_MS,
    val sendTimeoutMs: Long = DEFAULT_SEND_TIMEOUT_MS,
) {
    init {
        require(pongTimeoutMs > pingIntervalMs) { "pongTimeout must exceed pingInterval" }
        require(deviceId.size == FrameCodec.ID_SIZE && token.size == FrameCodec.TOKEN_SIZE) { "bad deviceId or token size" }
    }

    /** Equal enough to keep the running session: address order does not matter. */
    fun sameConnection(other: ConnectionConfig) =
        endpoints.toSet() == other.endpoints.toSet() && spkiSha256.contentEquals(other.spkiSha256) &&
            deviceId.contentEquals(other.deviceId) && token.contentEquals(other.token) &&
            pingIntervalMs == other.pingIntervalMs && pongTimeoutMs == other.pongTimeoutMs && sendTimeoutMs == other.sendTimeoutMs

    companion object {
        const val DEFAULT_PING_INTERVAL_MS = 5_000L
        const val DEFAULT_PONG_TIMEOUT_MS = 10_000L
        const val DEFAULT_SEND_TIMEOUT_MS = 1_000L
    }
}

/**
 * One pinned TLS session to the PC. Three threads: connection (dial, HELLO, reads), writer (the only socket writer)
 * and timer (PING cadence and the per-write send timeout). Sending never blocks the caller.
 */
class RelayClient(private val config: ConnectionConfig, private val listener: Listener) {
    /** Called on the connection thread; callbacks must not throw. */
    interface Listener {
        fun onConnected(pcName: String, endpoint: Endpoint)
        fun onPcReady(ready: Boolean)
        fun onDisconnected(reason: String)
        fun onRejected(reason: Int)
        fun onRtt(ms: Int)
    }

    @Volatile private var running = false
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var rawSocket: Socket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var dropReason: String? = null
    @Volatile var endpointOrder: List<Endpoint> = config.endpoints
        private set

    private val queue = IntRingQueue(QUEUE_CAPACITY)
    private val timerLock = ReentrantLock()
    private val timerWake = timerLock.newCondition()
    private var writeStartedNs = 0L
    private var pingSentNs = 0L
    private var pingSeq = 0
    private var failures = 0
    private val frame = ByteArray(FrameCodec.PING_FRAME_SIZE)
    private var connectThread: Thread? = null

    fun start() {
        check(connectThread == null) { "RelayClient is single-use" }
        running = true
        connectThread = thread(isDaemon = true, name = "relay-connect") { connectLoop() }
        thread(isDaemon = true, name = "relay-writer") { writerLoop() }
        thread(isDaemon = true, name = "relay-timer") { timerLoop() }
    }

    /** Idempotent; also called by the connection thread when it gives up (REJECT, pin mismatch). */
    fun stop() {
        running = false
        queue.close()
        closeSockets()
        timerLock.withLock { timerWake.signalAll() }
        connectThread?.interrupt()
    }

    fun sendKey(code: Int, value: Int) = enqueue(ITEM_KEY or (code shl 8) or value)

    fun sendSas() = enqueue(ITEM_SAS)

    fun sendLock() = enqueue(ITEM_LOCK)

    // Not connected: the event is dropped (spec: unsendable events are discarded, not replayed later).
    private fun enqueue(item: Int) {
        if (output == null) return
        if (!queue.offer(item)) drop("send queue full")
    }

    private fun drop(reason: String) {
        if (dropReason == null) dropReason = reason
        closeSockets()
    }

    // TCP first: SSLSocket.close() waits for the TLS record lock that a write blocked on a full buffer holds;
    // closing the TCP socket fails that write and releases the lock.
    private fun closeSockets() {
        runCatching { rawSocket?.close() }
        runCatching { socket?.close() }
    }

    private fun connectLoop() {
        try {
            while (running) {
                dropReason = null
                try {
                    session()
                    return
                } catch (_: TlsDialer.PinMismatchException) {
                    // Retrying cannot help until the user pairs again; reported like a REJECT.
                    listener.onRejected(REJECT_PIN_MISMATCH)
                    return
                } catch (e: IOException) {
                    if (!running) return
                    listener.onDisconnected(dropReason ?: e.message ?: e.javaClass.simpleName)
                }
                val backoff = minOf(RETRY_BASE_MS shl minOf(failures++, MAX_SHIFT), config.pingIntervalMs)
                try {
                    Thread.sleep(backoff)
                } catch (_: InterruptedException) {
                    return
                }
            }
        } finally {
            stop()
        }
    }

    /** Returns normally only after a REJECT; every other end is an IOException. */
    private fun session() {
        val (s, raw, endpoint) = TlsDialer.dial(endpointOrder, config.spkiSha256)
        rawSocket = raw
        socket = s
        try {
            // Checked after publishing the socket so a stop() racing the dial still closes it and no HELLO goes out.
            if (!running) throw IOException("stopped")
            val out = s.outputStream
            val input = BufferedInputStream(s.inputStream)
            val hello = FrameCodec.encode(Frame.Hello(FrameCodec.PROTOCOL_VERSION, config.deviceId, config.token))
            timedWrite(out, hello, hello.size)
            // The server answers every PING; a silent socket for pongTimeout is a dead connection.
            s.soTimeout = config.pongTimeoutMs.toInt()
            val buf = ByteArray(FrameCodec.MAX_LENGTH)
            when (val first = FrameCodec.read(input, buf) ?: throw EOFException("closed before WELCOME")) {
                is Frame.Welcome -> {
                    failures = 0
                    endpointOrder = listOf(endpoint) + endpointOrder.filter { it != endpoint }
                    queue.clear()
                    output = out
                    timerLock.withLock { timerWake.signalAll() }
                    listener.onConnected(first.pcName, endpoint)
                }
                is Frame.Reject -> {
                    listener.onRejected(first.reason)
                    return
                }
                else -> throw IOException("unexpected frame before WELCOME")
            }
            while (true) {
                when (val f = FrameCodec.read(input, buf) ?: throw EOFException("server closed the connection")) {
                    is Frame.State -> listener.onPcReady(f.ready)
                    is Frame.Pong -> onPong(f.seq)
                    else -> throw IOException("unexpected frame ${f.javaClass.simpleName}")
                }
            }
        } finally {
            output = null
            closeSockets()
            socket = null
            rawSocket = null
        }
    }

    private fun writerLoop() {
        while (true) {
            val item = queue.take()
            if (item == IntRingQueue.CLOSED) return
            val out = output ?: continue
            val length = when (item and KIND_MASK) {
                ITEM_KEY -> { FrameCodec.encodeKey((item shr 8) and 0xFFFF, item and 0xFF, frame); FrameCodec.KEY_FRAME_SIZE }
                ITEM_SAS -> FrameCodec.encodeEmpty(FrameCodec.SAS, frame)
                ITEM_LOCK -> FrameCodec.encodeEmpty(FrameCodec.LOCK, frame)
                else -> FrameCodec.encodePing(item and SEQ_MASK, frame)
            }
            try {
                timedWrite(out, frame, length)
            } catch (e: IOException) {
                // A write that failed on the previous session must not drop the next one.
                if (output === out) drop("write failed: ${e.message}")
            }
        }
    }

    private fun timedWrite(out: OutputStream, bytes: ByteArray, length: Int) {
        timerLock.withLock {
            writeStartedNs = System.nanoTime()
            timerWake.signalAll()
        }
        try {
            out.write(bytes, 0, length)
            out.flush()
        } finally {
            timerLock.withLock { writeStartedNs = 0 }
        }
    }

    private fun timerLoop() {
        val pingNs = config.pingIntervalMs * NS_PER_MS
        val sendNs = config.sendTimeoutMs * NS_PER_MS
        timerLock.withLock {
            var nextPingNs = System.nanoTime() + pingNs
            while (running) {
                val now = System.nanoTime()
                if (writeStartedNs != 0L && now - writeStartedNs >= sendNs) {
                    writeStartedNs = 0
                    drop("send timeout")
                }
                if (output == null) {
                    nextPingNs = now + pingNs
                } else if (now >= nextPingNs) {
                    pingSeq = (pingSeq + 1) and SEQ_MASK
                    pingSentNs = now
                    nextPingNs = now + pingNs
                    enqueue(ITEM_PING or pingSeq)
                }
                val writeDeadline = if (writeStartedNs != 0L) writeStartedNs + sendNs else Long.MAX_VALUE
                val waitNs = minOf(nextPingNs, writeDeadline) - System.nanoTime()
                if (waitNs > 0) timerWake.awaitNanos(waitNs)
            }
        }
    }

    private fun onPong(seq: Int) {
        val rttMs = timerLock.withLock { if (seq == pingSeq) ((System.nanoTime() - pingSentNs) / NS_PER_MS).toInt() else -1 }
        if (rttMs >= 0) listener.onRtt(rttMs)
    }

    companion object {
        const val QUEUE_CAPACITY = 256
        /** Local reject reason outside the protocol's u8 range: the PC answers with a different key. */
        const val REJECT_PIN_MISMATCH = 0x100
        private const val RETRY_BASE_MS = 500L
        private const val MAX_SHIFT = 10
        private const val PAIR_REPLY_TIMEOUT_MS = 10_000
        private const val NS_PER_MS = 1_000_000L
        private const val KIND_MASK = 0xFF shl 24
        private const val SEQ_MASK = 0xFFFFFF
        private const val ITEM_KEY = 1 shl 24
        private const val ITEM_SAS = 2 shl 24
        private const val ITEM_LOCK = 3 shl 24
        private const val ITEM_PING = 4 shl 24

        /** One-shot pairing (QR flow): PAIR, then PAIRED or REJECT. Blocks the calling thread. */
        fun pair(endpoints: List<Endpoint>, spkiSha256: ByteArray, code: ByteArray, deviceName: String): Frame {
            val (s, _, _) = TlsDialer.dial(endpoints, spkiSha256)
            s.use {
                it.soTimeout = PAIR_REPLY_TIMEOUT_MS
                it.outputStream.write(FrameCodec.encode(Frame.Pair(FrameCodec.PROTOCOL_VERSION, code, deviceName)))
                val reply = FrameCodec.read(BufferedInputStream(it.inputStream), ByteArray(FrameCodec.MAX_LENGTH))
                if (reply is Frame.Paired || reply is Frame.Reject) return reply
                throw IOException("unexpected pairing reply")
            }
        }
    }
}
