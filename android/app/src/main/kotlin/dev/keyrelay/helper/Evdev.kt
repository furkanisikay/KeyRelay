package dev.keyrelay.helper

import java.nio.ByteBuffer

/** Thin JNI bridge to the evdev ioctls. Negative return values are -errno. */
object Evdev {
    const val INPUT_EVENT_SIZE = 24

    fun load(path: String) = System.load(path)

    @JvmStatic external fun open(path: String): Int
    @JvmStatic external fun close(fd: Int)
    @JvmStatic external fun grab(fd: Int, on: Boolean): Int
    @JvmStatic external fun evBits(fd: Int): Int
    @JvmStatic external fun keyBits(fd: Int): ByteArray?
    @JvmStatic external fun name(fd: Int): String?
    @JvmStatic external fun phys(fd: Int): String?
    @JvmStatic external fun uniq(fd: Int): String?
    @JvmStatic external fun read(fd: Int, buffer: ByteBuffer): Int

    /** A uinput device with the key, rel and msc bits of [source]; returns its fd. Closing the fd destroys it. */
    @JvmStatic external fun createMouse(source: Int, name: String): Int
    @JvmStatic external fun emit(fd: Int, type: Int, code: Int, value: Int): Int
}
