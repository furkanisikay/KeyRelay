package dev.keyrelay.core

import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Messages of the tablet-service TLS channel (docs/protocol.md "Mesajlar"). */
sealed interface Frame {
    class Pair(val version: Int, val code: ByteArray, val deviceName: String) : Frame
    class Paired(val deviceId: ByteArray, val token: ByteArray) : Frame
    class Hello(val version: Int, val deviceId: ByteArray, val token: ByteArray) : Frame
    class Welcome(val pcName: String) : Frame
    /** [reason] is read without a range check: an older app shows an unknown reason like any other reject. */
    class Reject(val reason: Int) : Frame {
        companion object {
            const val VERSION = 1
            const val UNKNOWN_DEVICE = 2
            const val INVALID_CODE = 3
            /** PAIR came through Funnel (from outside); pairing works only from the home network or Tailscale. */
            const val PAIRING_NOT_ALLOWED_HERE = 4
        }
    }
    class Key(val code: Int, val value: Int) : Frame
    class State(val ready: Boolean) : Frame
    data object Sas : Frame
    data object Lock : Frame
    class Ping(val seq: Int) : Frame
    class Pong(val seq: Int) : Frame
}

/** Error classes named like the "error" values of protocol/test-vectors/frames.json. */
enum class FrameError { EMPTY, TOO_LARGE, UNKNOWN_TYPE, TRUNCATED, BAD_PAYLOAD }

class FrameException(val error: FrameError) : IOException(error.name)

object FrameCodec {
    const val PROTOCOL_VERSION = 1
    const val MAX_LENGTH = 512
    const val KEY_FRAME_SIZE = 6
    const val PING_FRAME_SIZE = 7
    const val EMPTY_FRAME_SIZE = 3
    const val ID_SIZE = 16
    const val TOKEN_SIZE = 32
    const val CODE_SIZE = 16

    const val PAIR = 0x01
    const val PAIRED = 0x02
    const val HELLO = 0x03
    const val WELCOME = 0x04
    const val REJECT = 0x05
    const val KEY = 0x10
    const val STATE = 0x11
    const val SAS = 0x12
    const val LOCK = 0x13
    const val PING = 0x20
    const val PONG = 0x21

    fun encode(frame: Frame): ByteArray {
        val (type, payload) = when (frame) {
            is Frame.Pair -> PAIR to byteArrayOf(frame.version.toByte()) + sized(frame.code, CODE_SIZE) + frame.deviceName.toByteArray()
            is Frame.Paired -> PAIRED to sized(frame.deviceId, ID_SIZE) + sized(frame.token, TOKEN_SIZE)
            is Frame.Hello -> HELLO to byteArrayOf(frame.version.toByte()) + sized(frame.deviceId, ID_SIZE) + sized(frame.token, TOKEN_SIZE)
            is Frame.Welcome -> WELCOME to frame.pcName.toByteArray()
            is Frame.Reject -> REJECT to byteArrayOf(frame.reason.toByte())
            is Frame.Key -> KEY to byteArrayOf((frame.code shr 8).toByte(), frame.code.toByte(), frame.value.toByte())
            is Frame.State -> STATE to byteArrayOf(if (frame.ready) 1 else 0)
            Frame.Sas -> SAS to ByteArray(0)
            Frame.Lock -> LOCK to ByteArray(0)
            is Frame.Ping -> PING to int32(frame.seq)
            is Frame.Pong -> PONG to int32(frame.seq)
        }
        val length = payload.size + 1
        require(length <= MAX_LENGTH) { "frame length $length exceeds $MAX_LENGTH" }
        return byteArrayOf((length shr 8).toByte(), length.toByte(), type.toByte()) + payload
    }

    /** KEY frame into out[0..5] without allocating: the per-keystroke path. */
    fun encodeKey(code: Int, value: Int, out: ByteArray) {
        out[0] = 0
        out[1] = 4
        out[2] = KEY.toByte()
        out[3] = (code shr 8).toByte()
        out[4] = code.toByte()
        out[5] = value.toByte()
    }

    fun encodePing(seq: Int, out: ByteArray): Int {
        out[0] = 0
        out[1] = 5
        out[2] = PING.toByte()
        out[3] = (seq ushr 24).toByte()
        out[4] = (seq shr 16).toByte()
        out[5] = (seq shr 8).toByte()
        out[6] = seq.toByte()
        return PING_FRAME_SIZE
    }

    /** Frames without payload (SAS, LOCK). */
    fun encodeEmpty(type: Int, out: ByteArray): Int {
        out[0] = 0
        out[1] = 1
        out[2] = type.toByte()
        return EMPTY_FRAME_SIZE
    }

    /** Reads one frame using [buf] (at least [MAX_LENGTH] bytes); null when the stream ends between frames. */
    fun read(input: InputStream, buf: ByteArray): Frame? {
        val high = input.read()
        if (high < 0) return null
        val low = input.read()
        if (low < 0) throw FrameException(FrameError.TRUNCATED)
        val length = (high shl 8) or low
        if (length == 0) throw FrameException(FrameError.EMPTY)
        if (length > MAX_LENGTH) throw FrameException(FrameError.TOO_LARGE)
        if (readFully(input, buf, length) < length) throw FrameException(FrameError.TRUNCATED)
        return decode(buf[0].toInt() and 0xFF, buf, 1, length - 1)
    }

    private fun decode(type: Int, p: ByteArray, off: Int, n: Int): Frame = when (type) {
        PAIR -> {
            payload(n >= 1 + CODE_SIZE)
            Frame.Pair(u8(p, off), p.copyOfRange(off + 1, off + 1 + CODE_SIZE), utf8(p, off + 1 + CODE_SIZE, n - 1 - CODE_SIZE))
        }
        PAIRED -> {
            payload(n == ID_SIZE + TOKEN_SIZE)
            Frame.Paired(p.copyOfRange(off, off + ID_SIZE), p.copyOfRange(off + ID_SIZE, off + n))
        }
        HELLO -> {
            payload(n == 1 + ID_SIZE + TOKEN_SIZE)
            Frame.Hello(u8(p, off), p.copyOfRange(off + 1, off + 1 + ID_SIZE), p.copyOfRange(off + 1 + ID_SIZE, off + n))
        }
        WELCOME -> Frame.Welcome(utf8(p, off, n))
        REJECT -> { payload(n == 1); Frame.Reject(u8(p, off)) }
        KEY -> { payload(n == 3 && u8(p, off + 2) <= 2); Frame.Key((u8(p, off) shl 8) or u8(p, off + 1), u8(p, off + 2)) }
        STATE -> { payload(n == 1); Frame.State(u8(p, off) and 1 != 0) }
        SAS -> { payload(n == 0); Frame.Sas }
        LOCK -> { payload(n == 0); Frame.Lock }
        PING -> { payload(n == 4); Frame.Ping(int32(p, off)) }
        PONG -> { payload(n == 4); Frame.Pong(int32(p, off)) }
        else -> throw FrameException(FrameError.UNKNOWN_TYPE)
    }

    private fun payload(valid: Boolean) {
        if (!valid) throw FrameException(FrameError.BAD_PAYLOAD)
    }

    private fun sized(bytes: ByteArray, size: Int): ByteArray {
        require(bytes.size == size) { "expected $size bytes, got ${bytes.size}" }
        return bytes
    }

    private fun u8(p: ByteArray, i: Int) = p[i].toInt() and 0xFF

    private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())

    private fun int32(p: ByteArray, off: Int) =
        (u8(p, off) shl 24) or (u8(p, off + 1) shl 16) or (u8(p, off + 2) shl 8) or u8(p, off + 3)

    private fun utf8(p: ByteArray, off: Int, n: Int): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(p, off, n)).toString()
    } catch (e: CharacterCodingException) {
        throw FrameException(FrameError.BAD_PAYLOAD)
    }

    private fun readFully(input: InputStream, buf: ByteArray, length: Int): Int {
        var total = 0
        while (total < length) {
            val n = input.read(buf, total, length - total)
            if (n < 0) break
            total += n
        }
        return total
    }
}
