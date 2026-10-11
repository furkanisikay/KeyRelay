package dev.keyrelay.core

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Bounded FIFO of ints for the single writer thread; offering allocates nothing. */
class IntRingQueue(capacity: Int) {
    private val items = IntArray(capacity)
    private var head = 0
    private var size = 0
    private var closed = false
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()

    fun offer(v: Int): Boolean = lock.withLock {
        if (closed || size == items.size) return false
        items[(head + size) % items.size] = v
        size++
        notEmpty.signal()
        true
    }

    /** Blocks until an item is available; returns [CLOSED] once [close] was called. */
    fun take(): Int = lock.withLock {
        while (size == 0 && !closed) notEmpty.await()
        if (size == 0) return CLOSED
        val v = items[head]
        head = (head + 1) % items.size
        size--
        v
    }

    fun clear() = lock.withLock {
        head = 0
        size = 0
    }

    fun close() = lock.withLock {
        closed = true
        notEmpty.signalAll()
    }

    companion object {
        const val CLOSED = -1
    }
}
