package com.mangalens.ui.video

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class VideoResolutionDeadlineTest {
    @Test fun staticResolutionLeavesTimeForRenderedDiscoveryAndPublication() {
        var now = 0L
        val deadline = VideoResolutionDeadline(clockNanos = { now })
        assertEquals(30_000L, deadline.staticBudgetMillis())
        now = TimeUnit.MILLISECONDS.toNanos(30_000L)
        assertEquals(15_000L, deadline.remainingMillis())
        assertEquals(0L, deadline.staticBudgetMillis())
        assertEquals(12_000L, deadline.renderedBudgetMillis())
    }

    @Test fun queueTimeAndPriorRequestCleanupConsumeTheSameMonotonicDeadline() {
        var now = 100L
        val deadline = VideoResolutionDeadline(clockNanos = { now })
        now += TimeUnit.MILLISECONDS.toNanos(10_000L)
        assertEquals(35_000L, deadline.remainingMillis())
        assertEquals(20_000L, deadline.staticBudgetMillis())
        now += TimeUnit.MILLISECONDS.toNanos(33_000L)
        assertEquals(2_000L, deadline.remainingMillis())
        assertEquals(0L, deadline.staticBudgetMillis())
        assertEquals(0L, deadline.renderedBudgetMillis())
    }

    @Test fun exhaustedStaticStageStillExecutesTheRealRenderedFallback() = runBlocking {
        val deadline = VideoResolutionDeadline(450L, 120L, 30L)
        var renderedCalls = 0
        val result = deadline.run {
            resolve(
                static = { delay(500L); "unavailable static source" },
                rendered = { budget ->
                    assertTrue(budget in 1L..120L)
                    renderedCalls++
                    "rendered playable source"
                }
            )
        }
        assertEquals("rendered playable source", result)
        assertEquals(1, renderedCalls)
    }

    @Test fun waitingForAnOldRequestCannotExtendTheNewRequestsTotalDeadline() = runBlocking {
        val previous = Job()
        var staticCalls = 0
        val deadline = VideoResolutionDeadline(150L, 40L, 10L)
        val pending = async {
            runCatching {
                deadline.run(previous) {
                    staticCalls++
                    "must not execute after waiting past the deadline"
                }
            }
        }
        try {
            val failure = withTimeout(500L) { pending.await() }.exceptionOrNull()
            assertTrue("The new request waited past its own deadline", failure is TimeoutCancellationException)
            assertEquals(0, staticCalls)
        } finally { previous.cancel(); pending.cancel() }
    }

    @Test fun cancellingTheRequestCannotStartFallbackOrPublishLateStaticOutput() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val staticRelease = CompletableDeferred<Unit>()
        var renderedCalls = 0
        var publications = 0
        val pending = async {
            VideoResolutionDeadline().run {
                val result = resolve(
                    static = { entered.complete(Unit); staticRelease.await(); "old source" },
                    rendered = { renderedCalls++; "replacement" }
                )
                if (result != null) publications++
            }
        }
        withTimeout(500L) { entered.await() }
        pending.cancel()
        staticRelease.complete(Unit)
        withTimeout(500L) { pending.join() }
        assertEquals(0, renderedCalls)
        assertEquals(0, publications)
    }

    @Test fun elapsedMonotonicTimeRejectsAStaticResultEvenIfTheUiTimerHasNotRun() = runBlocking {
        var now = 0L
        val deadline = VideoResolutionDeadline(clockNanos = { now })
        val failure = runCatching {
            deadline.run {
                resolve(
                    static = { now = TimeUnit.MILLISECONDS.toNanos(46_000L); "late source" },
                    rendered = { fail("An expired request must not start rendered discovery"); null }
                )
            }
        }.exceptionOrNull()
        assertTrue("A late result escaped the monotonic deadline", failure is TimeoutCancellationException)
    }
}
