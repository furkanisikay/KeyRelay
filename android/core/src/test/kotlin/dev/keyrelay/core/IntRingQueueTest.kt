package dev.keyrelay.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.concurrent.thread

class IntRingQueueTest {
    @Test
    fun offer_beyondCapacity_returnsFalse() {
        val q = IntRingQueue(2)
        assertTrue(q.offer(1))
        assertTrue(q.offer(2))
        assertFalse(q.offer(3))
    }

    @Test
    fun take_afterWrapAround_keepsFifoOrder() {
        val q = IntRingQueue(2)
        q.offer(1); q.offer(2)
        assertEquals(1, q.take())
        q.offer(3)
        assertEquals(2, q.take())
        assertEquals(3, q.take())
    }

    @Test
    fun close_wakesBlockedTake_returnsClosed() {
        val q = IntRingQueue(4)
        var got = 0
        val t = thread { got = q.take() }
        Thread.sleep(100)
        q.close()
        t.join(2_000)
        assertEquals(IntRingQueue.CLOSED, got)
    }

    @Test
    fun clear_dropsQueuedItems() {
        val q = IntRingQueue(4)
        q.offer(1); q.offer(2)
        q.clear()
        q.offer(9)
        assertEquals(9, q.take())
    }
}
