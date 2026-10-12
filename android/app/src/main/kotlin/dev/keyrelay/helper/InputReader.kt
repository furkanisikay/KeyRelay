package dev.keyrelay.helper

import android.os.FileObserver
import dev.keyrelay.core.KeyboardGrouping
import dev.keyrelay.core.MouseRouting
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/** Called on a reader thread for every keyboard EV_KEY event and mouse button; a fun interface so the ints are not boxed. */
fun interface KeySink {
    fun onKey(code: Int, value: Int)
}

/**
 * Reads and grabs every keyboard node, and every mouse node when a virtual mouse could be made ([MouseRouting]).
 * Plugged-in or re-connected keyboards are found through inotify on /dev/input;
 * a node that disappears ends its reader with an error (no polling).
 */
class InputReader(
    private val sink: KeySink,
    private val onKeyboards: (List<String>) -> Unit,
    private val onNodeLost: (mouse: Boolean) -> Unit = {},
) {
    @Volatile var invertHorizontalScroll = false
    @Volatile var invertVerticalScroll = false
    private val open = HashMap<String, Int>()
    @Volatile private var grabbed = false
    private var virtualFd = NOT_CREATED
    // Lock order: this, then virtualLock. Readers take only virtualLock, so they never block on a grab change.
    private val virtualLock = Any()
    // Bit (code - BTN_LEFT) of each button the virtual mouse holds down.
    private var virtualHeld = 0

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
        if (!on) releaseVirtual()
    }

    fun rescan() {
        val nodes = DeviceScanner.scan()
        val keyboards = KeyboardGrouping.group(nodes)
        synchronized(this) {
            for (node in keyboards.flatMap { it.nodes }) {
                if (node.path in open) continue
                val fd = openNode(node.path)
                if (fd >= 0) startReader(node.path, fd, mouse = false)
            }
            for (node in nodes.filter(MouseRouting::isMouse)) {
                if (node.path in open || virtualFd == UNAVAILABLE) continue
                val fd = openNode(node.path)
                if (fd < 0) continue
                if (virtualFd == NOT_CREATED) {
                    val created = Evdev.createMouse(fd, MouseRouting.VIRTUAL_NAME)
                    if (created < 0) {
                        println("${System.currentTimeMillis()} no virtual mouse ($created), mouse buttons stay on the tablet")
                        virtualFd = UNAVAILABLE
                        Evdev.close(fd)
                        continue
                    }
                    virtualFd = created
                }
                startReader(node.path, fd, mouse = true)
            }
        }
        onKeyboards(keyboards.map { it.name })
    }

    private fun openNode(path: String): Int {
        val fd = Evdev.open(path)
        if (fd < 0) println("open $path failed: $fd")
        return fd
    }

    private fun startReader(path: String, fd: Int, mouse: Boolean) {
        open[path] = fd
        if (grabbed) {
            val result = Evdev.grab(fd, true)
            if (result != 0) println("grab $path on=true -> $result")
        }
        thread(isDaemon = true, name = "read $path") { readLoop(path, fd, mouse) }
    }

    private fun toVirtual(type: Int, code: Int, value: Int) {
        synchronized(virtualLock) {
            // Checked again under the lock: after releaseVirtual nothing more may press a button.
            if (!grabbed) return
            Evdev.emit(virtualFd, type, code, value)
            if (type == EV_KEY && code in MouseRouting.BTN_LEFT..MouseRouting.BTN_TASK) {
                val bit = 1 shl (code - MouseRouting.BTN_LEFT)
                virtualHeld = if (value != 0) virtualHeld or bit else virtualHeld and bit.inv()
            }
        }
    }

    // A button left down on the virtual mouse would keep Android dragging.
    private fun releaseVirtual() {
        synchronized(virtualLock) {
            if (virtualHeld == 0) return
            for (i in 0..MouseRouting.BTN_TASK - MouseRouting.BTN_LEFT) {
                if (virtualHeld and (1 shl i) != 0) Evdev.emit(virtualFd, EV_KEY, MouseRouting.BTN_LEFT + i, 0)
            }
            Evdev.emit(virtualFd, EV_SYN, SYN_REPORT, 0)
            virtualHeld = 0
        }
    }

    // Only the reader closes its fd: closing it from another thread would not wake a blocked read.
    private fun readLoop(path: String, fd: Int, mouse: Boolean) {
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
                    val type = buf.getShort(off + TYPE_OFFSET).toInt()
                    val code = buf.getShort(off + CODE_OFFSET).toInt() and 0xFFFF
                    val value = buf.getInt(off + VALUE_OFFSET)
                    if (mouse) {
                        val route = MouseRouting.route(type, code, grabbed)
                        // State machine first: a release that ends the grab is then written by releaseVirtual, with its SYN.
                        if (route and MouseRouting.TO_MACHINE != 0) sink.onKey(code, value)
                        if (route and MouseRouting.TO_VIRTUAL != 0) {
                            toVirtual(type, code, MouseRouting.virtualValue(type, code, value, invertHorizontalScroll, invertVerticalScroll))
                        }
                    } else if (type == EV_KEY) {
                        sink.onKey(code, value)
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
        if (mouse) releaseVirtual()
        // The kernel's synthetic key-ups for a vanished device may not reach an evdev reader.
        onNodeLost(mouse)
        rescan()
    }

    private companion object {
        const val INPUT_DIR = "/dev/input"
        const val EVENTS_PER_READ = 64
        const val EINTR = 4
        const val EV_SYN = 0
        const val EV_KEY = 1
        const val SYN_REPORT = 0
        const val NOT_CREATED = -1
        const val UNAVAILABLE = -2
        // struct input_event on 64-bit: timeval (16 bytes), u16 type, u16 code, s32 value.
        const val TYPE_OFFSET = 16
        const val CODE_OFFSET = 18
        const val VALUE_OFFSET = 20
    }
}
