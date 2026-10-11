package dev.keyrelay.core

import java.io.ByteArrayOutputStream
import java.io.IOException

/** Output and exit status of one adb shell command; a non-zero status is a result, not an error. */
class ShellResult(val output: String, val exitCode: Int)

/** Reads an adb shell command's output up to the end marker the command was told to echo. */
object ShellOutput {
    const val END_MARKER = "__KR_END__"

    /** Upper bound for one adb step (spec ADB_COMMAND_TIMEOUT); the mDNS window is twice this. */
    const val COMMAND_TIMEOUT_MS = 10_000L

    private const val BUFFER_SIZE = 8192

    fun wrap(command: String) = "$command; echo $END_MARKER \$?"

    /**
     * Calls [read] (bytes read into the buffer, or -1 at end of stream) until the marker and the exit status after it
     * have arrived. Throws [IOException] if the stream ends first or the status is missing.
     */
    fun readUntilMarker(read: (ByteArray) -> Int): ShellResult {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(BUFFER_SIZE)
        while (true) {
            val n = read(buf)
            if (n > 0) out.write(buf, 0, n)
            // Search everything read so far: marker and exit status can arrive in separate reads.
            val text = out.toString(Charsets.UTF_8.name())
            val marker = text.indexOf(END_MARKER)
            if (marker >= 0) {
                val lineEnd = text.indexOf('\n', marker)
                if (lineEnd >= 0 || n < 0) {
                    val status = text.substring(marker + END_MARKER.length, if (lineEnd >= 0) lineEnd else text.length).trim()
                    val code = status.toIntOrNull() ?: throw IOException("no exit status after end marker")
                    return ShellResult(text.substring(0, marker).trimEnd('\r', '\n'), code)
                }
            }
            if (n < 0) throw IOException("command output ended without end marker")
        }
    }
}
