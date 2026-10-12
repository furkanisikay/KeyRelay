package dev.keyrelay.helper

import dev.keyrelay.core.InputNode
import java.io.File

object DeviceScanner {
    fun scan(): List<InputNode> =
        File("/dev/input").listFiles { f -> f.name.startsWith("event") }.orEmpty()
            .sortedBy { it.name.removePrefix("event").toIntOrNull() ?: Int.MAX_VALUE }
            .mapNotNull { f ->
                val fd = Evdev.open(f.path)
                if (fd < 0) { println("skip ${f.path}: $fd"); return@mapNotNull null }
                try {
                    InputNode(
                        f.path,
                        Evdev.name(fd).orEmpty(),
                        Evdev.phys(fd).orEmpty(),
                        Evdev.uniq(fd).orEmpty(),
                        Evdev.evBits(fd),
                        Evdev.keyBits(fd) ?: ByteArray(0),
                    )
                } finally {
                    Evdev.close(fd)
                }
            }
}
