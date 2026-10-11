package dev.keyrelay.helper

import android.os.FileObserver
import dev.keyrelay.core.KeyboardGrouping
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/** Called on a reader thread for every EV_KEY event; a fun interface so the ints are not boxed. */
fun interface KeySink {
    fun onKey(code: Int, value: Int)
}

/**
 * Reads and grabs every keyboard node. Plugged-in or re-connected keyboards are found through inotify on /dev/input;
 * a node that disappears ends its reader with an error (no polling).
 */
class InputReader(
    private val sink: KeySink,
    private val onKeyboards: (List<String>) -> Unit,
    private val onNodeLost: () -> Unit = {},
) {
    private val open = HashMap<String, Int>()
    private var grabbed = false

    // Kept in a field: an unreferenced FileObserver is garbage collected and stops watching.
    // ATTRIB too: ueventd creates the node first and makes it readable a moment later.
    private val observer = object : FileObserver(File(INPUT_DIR), FileObserver.CREATE or FileObserver.ATTRIB) {
        override fun onEvent(event: Int, path: String?) {
            if (path?.startsWith("event") == true) rescan()
        }
    }

    fun start() {
        observer.startWatching()
        rescan()
    }

    @Synchronized
    fun setGrab(on: Boolean) {
        grabbed = on
        for ((path, fd) in open) {
            val result = Evdev.grab(fd, on)
            if (result != 0) println("grab $path on=$on -> $result")
        }
    }

    fun rescan() {
        val keyboards = KeyboardGrouping.group(DeviceScanner.scan())
        synchronized(this) {
            for (node in keyboards.flatMap { it.nodes }) {
                if (node.path in open) continue
                val fd = Evdev.open(node.path)
                if (fd < 0) {
                    println("open ${node.path} failed: $fd")
                    continue
                }
                open[node.path] = fd
                if (grabbed) {
                    val result = Evdev.grab(fd, true)
                    if (result != 0) println("grab ${node.path} on=true -> $result")
                }
                thread(isDaemon = true, name = "read ${node.path}") { readLoop(node.path, fd) }
            }
        }
        onKeyboards(keyboards.map { it.name })
    }

    // Only the reader closes its fd: closing it from another thread would not wake a blocked read.
    private fun readLoop(path: String, fd: Int) {
        val buf = ByteBuffer.allocateDirect(Evdev.INPUT_EVENT_SIZE * EVENTS_PER_READ).order(ByteOrder.nativeOrder())
        try {
            while (true) {
                val n = Evdev.read(fd, buf)
                if (n == -EINTR) continue
                if (n <= 0) {
                    println("${System.currentTimeMillis()} read $path ended: $n")
                    break
                }
                var off = 0
                while (off + Evdev.INPUT_EVENT_SIZE <= n) {
                    if (buf.getShort(off + TYPE_OFFSET).toInt() == EV_KEY) {
                        sink.onKey(buf.getShort(off + CODE_OFFSET).toInt() and 0xFFFF, buf.getInt(off + VALUE_OFFSET))
                    }
                    off += Evdev.INPUT_EVENT_SIZE
                }
            }
        } finally {
            synchronized(this) {
                open.remove(path)
                Evdev.close(fd)
            }
        }
        // The kernel's synthetic key-ups for a vanished device may not reach an evdev reader.
        onNodeLost()
        rescan()
    }

    private companion object {
        const val INPUT_DIR = "/dev/input"
        const val EVENTS_PER_READ = 64
        const val EINTR = 4
        const val EV_KEY = 1
        // struct input_event on 64-bit: timeval (16 bytes), u16 type, u16 code, s32 value.
        const val TYPE_OFFSET = 16
        const val CODE_OFFSET = 18
        const val VALUE_OFFSET = 20
    }
}
