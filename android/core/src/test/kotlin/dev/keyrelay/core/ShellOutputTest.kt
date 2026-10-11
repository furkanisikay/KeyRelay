package dev.keyrelay.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException

class ShellOutputTest {
    private val marker = ShellOutput.END_MARKER

    private fun reader(vararg chunks: String): (ByteArray) -> Int {
        val queue = ArrayDeque(chunks.map { it.toByteArray() })
        return { buf -> queue.removeFirstOrNull()?.let { it.copyInto(buf); it.size } ?: -1 }
    }

    private fun read(vararg chunks: String) = ShellOutput.readUntilMarker(reader(*chunks))

    @Test
    fun readUntilMarker_markerInOneChunk_returnsOutputAndCode() {
        val r = read("1234\r\n$marker 0\r\n")
        assertEquals("1234", r.output)
        assertEquals(0, r.exitCode)
    }

    @Test
    fun readUntilMarker_markerSplitAcrossChunks_returnsTextBeforeMarker() =
        assertEquals("1234", read("12", "34\n" + marker.take(5), marker.drop(5) + " 0\n").output)

    @Test
    fun readUntilMarker_exitCodeInLaterChunk_waitsForLineEnd() = assertEquals(1, read("x\n$marker", " 1", "\n").exitCode)

    @Test
    fun readUntilMarker_nonZeroCode_returnedNotThrown() {
        val r = read("$marker 1\n")
        assertEquals("", r.output)
        assertEquals(1, r.exitCode)
    }

    @Test
    fun readUntilMarker_streamEndsAfterCodeWithoutNewline_returnsCode() = assertEquals(0, read("$marker 0").exitCode)

    @Test
    fun readUntilMarker_markerWithoutCode_throwsIOException() {
        assertThrows(IOException::class.java) { read("$marker\n") }
    }

    @Test
    fun readUntilMarker_streamEndsWithoutMarker_throwsIOException() {
        assertThrows(IOException::class.java) { read("1234\n") }
    }

    @Test
    fun wrap_appendsMarkerAndExitStatus() = assertEquals("pidof x; echo __KR_END__ \$?", ShellOutput.wrap("pidof x"))
}
