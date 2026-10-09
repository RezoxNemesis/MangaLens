package com.mangalens.ui.video

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/** One request owns queueing, static extraction, rendered discovery and publication time. */
internal class VideoResolutionDeadline(
    private val totalMs: Long = 45_000L,
    private val renderedMs: Long = 12_000L,
    private val publicationMs: Long = 3_000L,
    private val clockNanos: () -> Long = System::nanoTime
) {
    private val startedNanos = clockNanos()

    init { require(totalMs > renderedMs + publicationMs && renderedMs > 0L && publicationMs >= 0L) }

    fun remainingMillis(): Long = (totalMs - TimeUnit.NANOSECONDS.toMillis(
        (clockNanos() - startedNanos).coerceAtLeast(0L))).coerceAtLeast(0L)

    fun staticBudgetMillis(): Long = (remainingMillis() - renderedMs - publicationMs).coerceAtLeast(0L)

    fun renderedBudgetMillis(): Long = minOf(renderedMs, (remainingMillis() - publicationMs).coerceAtLeast(0L))

    suspend fun checkActive() {
        currentCoroutineContext().ensureActive()
        // A synchronous Android callback can delay the UI coroutine's timer.
        // Zero-timeout checks the monotonic clock before any late publication.
        if (remainingMillis() == 0L) withTimeout(0L) { Unit }
    }

    suspend fun <T> run(previousRequest: Job? = null, work: suspend VideoResolutionDeadline.() -> T): T {
        return withTimeout(remainingMillis()) {
            previousRequest?.join()
            checkActive()
            work().also { checkActive() }
        }
    }

    suspend fun <T> resolve(static: suspend (Long) -> T?, rendered: suspend (Long) -> T?): T? {
        val staticBudget = staticBudgetMillis()
        val first = if (staticBudget > 0L) withTimeoutOrNull(staticBudget) { static(staticBudget) } else null
        checkActive()
        if (first != null) return first
        val renderedBudget = renderedBudgetMillis()
        return (if (renderedBudget > 0L) withTimeoutOrNull(renderedBudget) { rendered(renderedBudget) } else null)
            .also { checkActive() }
    }
}
