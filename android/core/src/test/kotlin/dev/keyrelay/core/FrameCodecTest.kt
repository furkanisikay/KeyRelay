package dev.keyrelay.core

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.util.HexFormat

class FrameCodecTest {
    private val hex = HexFormat.of()
    private val vectors = JSONObject(File(System.getProperty("keyrelay.vectors")).readText())

    private fun list(name: String) = vectors.getJSONArray(name).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun tlsValid() = list("valid").filter { "tls" in it.getJSONArray("channels").toList() }
    private fun read(h: String) = FrameCodec.read(ByteArrayInputStream(hex.parseHex(h)), ByteArray(FrameCodec.MAX_LENGTH))
    private fun errorOf(h: String) = assertThrows(FrameException::class.java) { read(h) }.error

    private fun frameOf(v: JSONObject): Frame {
        val f = v.getJSONObject("fields")
        return when (v.getString("type")) {
            "KEY" -> Frame.Key(f.getInt("code"), f.getInt("value"))
            "HELLO" -> Frame.Hello(f.getInt("version"), hex.parseHex(f.getString("deviceId")), hex.parseHex(f.getString("token")))
            "PAIR" -> Frame.Pair(f.getInt("version"), hex.parseHex(f.getString("code")), f.getString("deviceName"))
            "PAIRED" -> Frame.Paired(hex.parseHex(f.getString("deviceId")), hex.parseHex(f.getString("token")))
            "WELCOME" -> Frame.Welcome(f.getString("pcName"))
            "REJECT" -> Frame.Reject(f.getInt("reason"))
            "STATE" -> Frame.State(f.getBoolean("ready"))
            "SAS" -> Frame.Sas
            "LOCK" -> Frame.Lock
            "PING" -> Frame.Ping(f.getInt("seq"))
            "PONG" -> Frame.Pong(f.getInt("seq"))
            else -> error("no tls frame for ${v.getString("type")}")
        }
    }

    @TestFactory
    fun encode_validTlsVectors_matchHex(): List<DynamicTest> = tlsValid().map { v ->
        dynamicTest(v.getString("name")) { assertEquals(v.getString("hex"), hex.formatHex(FrameCodec.encode(frameOf(v)))) }
    }

    @TestFactory
    fun read_validTlsVectors_decodeToSameFrame(): List<DynamicTest> = tlsValid().map { v ->
        dynamicTest(v.getString("name")) {
            val frame = read(v.getString("hex"))!!
            assertEquals(frameOf(v).javaClass, frame.javaClass)
            assertEquals(v.getString("hex"), hex.formatHex(FrameCodec.encode(frame)))
        }
    }

    @TestFactory
    fun read_invalidVectors_failWithListedError(): List<DynamicTest> = list("invalid").map { v ->
        dynamicTest(v.getString("name")) { assertEquals(v.getString("error"), errorOf(v.getString("hex")).name.lowercase()) }
    }

    @Test
    fun read_pipeOnlyReleaseAll_isUnknownOnTlsChannel() = assertEquals(FrameError.UNKNOWN_TYPE, errorOf("000130"))

    @Test
    fun read_emptyStream_returnsNull() = assertNull(read(""))

    @Test
    fun read_streamEndsAfterFirstLengthByte_truncated() = assertEquals(FrameError.TRUNCATED, errorOf("00"))

    @Test
    fun read_welcomeWithInvalidUtf8_badPayload() = assertEquals(FrameError.BAD_PAYLOAD, errorOf("00030480ff"))

    @Test
    fun read_stateWithReservedBits_usesBitZeroOnly() {
        assertEquals(true, (read("00021103") as Frame.State).ready)
        assertEquals(false, (read("00021102") as Frame.State).ready)
    }

    @Test
    fun read_twoFramesBackToBack_readsBothThenNull() {
        val input = ByteArrayInputStream(hex.parseHex("000410001e01" + "00021101"))
        val buf = ByteArray(FrameCodec.MAX_LENGTH)
        assertEquals(30, (FrameCodec.read(input, buf) as Frame.Key).code)
        assertEquals(true, (FrameCodec.read(input, buf) as Frame.State).ready)
        assertNull(FrameCodec.read(input, buf))
    }

    @Test
    fun encodeKey_writesSameBytesAsEncode() {
        val out = ByteArray(FrameCodec.KEY_FRAME_SIZE)
        FrameCodec.encodeKey(125, 0, out)
        assertEquals(hex.formatHex(FrameCodec.encode(Frame.Key(125, 0))), hex.formatHex(out))
    }

    @Test
    fun encodePingAndEmpty_writeSameBytesAsEncode() {
        val out = ByteArray(FrameCodec.PING_FRAME_SIZE)
        assertEquals(7, FrameCodec.encodePing(16909060, out))
        assertEquals("00052001020304", hex.formatHex(out))
        assertEquals(3, FrameCodec.encodeEmpty(FrameCodec.SAS, out))
        assertEquals("000112", hex.formatHex(out, 0, 3))
    }

    @Test
    fun encode_welcomeOver511Bytes_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { FrameCodec.encode(Frame.Welcome("A".repeat(512))) }
    }

    @Test
    fun encode_helloWithShortToken_isRejected() {
        assertThrows(IllegalArgumentException::class.java) { FrameCodec.encode(Frame.Hello(1, ByteArray(16), ByteArray(31))) }
    }
}
