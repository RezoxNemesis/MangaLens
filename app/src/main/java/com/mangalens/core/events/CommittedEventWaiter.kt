package com.mangalens.core.events

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** A hint merely shortens the next authoritative read; missed hints are harmless. */
object CommittedEventWaiter {
    suspend fun <T : Any> awaitCurrent(subscription: AppEventBus.Subscription?, pollMillis: Long,
        isCurrent: suspend () -> Boolean, read: suspend () -> T?): T? {
        try {
            require(pollMillis in 1..30_000) { "Durable event fallback must be bounded" }
            while (true) {
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) return null
                val committed = read()
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) return null
                if (committed != null) return committed
                if (subscription == null) delay(pollMillis)
                else withTimeoutOrNull(pollMillis) { subscription.receive() }
            }
        } finally { subscription?.close() }
    }
}
