package dev.keyrelay.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Pushes [read]'s value with [push], one push at a time, in request order. Requests that arrive while a push runs
 * collapse into one more push that reads the value again, so an older value can never land after a newer one.
 * A failed read or push goes to [onError] and the next request is served: one disk error must not stop all pushes.
 */
class LatestPusher<T>(
    scope: CoroutineScope,
    private val read: suspend () -> T,
    private val onError: (Exception) -> Unit = {},
    private val push: suspend (T) -> Unit,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (r in requests) {
                try {
                    push(read())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(e)
                }
            }
        }
    }

    fun request() {
        requests.trySend(Unit)
    }
}
