package dev.keyrelay.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class LatestPusherTest {
    @Test
    fun request_whilePushRuns_newestValueLandsLast_neverOverlaps() = runTest {
        val scope = TestScope(testScheduler)
        var setting = "showKeys=true"
        val pushed = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        var running = 0
        var maxRunning = 0
        val pusher = LatestPusher(scope, { setting }) { v ->
            running++
            maxRunning = maxOf(maxRunning, running)
            if (pushed.isEmpty()) gate.await()
            pushed += v
            running--
        }
        // A package broadcast read the old value and is still pushing when the user turns key display off.
        pusher.request()
        scope.runCurrent()
        setting = "showKeys=false"
        pusher.request()
        pusher.request()
        gate.complete(Unit)
        scope.runCurrent()
        assertEquals(listOf("showKeys=true", "showKeys=false"), pushed)
        assertEquals(1, maxRunning)
        scope.cancel()
    }

    @Test
    fun request_afterAFailedRead_isStillPushed() = runTest {
        val scope = TestScope(testScheduler)
        var reads = 0
        val errors = mutableListOf<Exception>()
        val pushed = mutableListOf<String>()
        val pusher = LatestPusher(scope, { if (reads++ == 0) throw IOException("disk") else "showKeys=false" }, { errors += it }) { pushed += it }
        pusher.request()
        scope.runCurrent()
        pusher.request()
        scope.runCurrent()
        assertEquals(1, errors.size)
        assertEquals(listOf("showKeys=false"), pushed)
        scope.cancel()
    }
}
