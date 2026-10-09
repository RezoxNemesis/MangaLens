package com.mangalens.oreznative

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class NativeMemoryReleaseCoordinatorTest {
    @Test fun acceptedExecutorHoldsActualCloseWhileCapturedOrezOwnersAreCancelledImmediately() {
        val coordinator = NativeMemoryReleaseCoordinator<Any>()
        val owner = Any()
        val cancelled = AtomicInteger()
        val closed = AtomicInteger()
        var accepted: (() -> Unit)? = null
        coordinator.request(listOf(owner), { cancelled.incrementAndGet() }, { closed.incrementAndGet() }) { accepted = it }
        assertEquals(1, cancelled.get())
        assertNotNull("The previous native thread bypassed the application resource scheduler", accepted)
        assertEquals(0, closed.get())
        accepted!!()
        assertEquals(1, closed.get())
    }

    @Test fun laterDistinctOwnerSnapshotCannotDiscardAnEarlierPendingPhysicalClose() {
        val coordinator = NativeMemoryReleaseCoordinator<Any>()
        val first = Any()
        val second = Any()
        val closes = ArrayList<Any>()
        val callbacks = ArrayList<() -> Unit>()
        coordinator.request(listOf(first), {}, { closes += it }) { callbacks += it }
        // Faithful trigger: the module map no longer presents A when trim B is accepted.
        coordinator.request(listOf(second), {}, { closes += it }) { callbacks += it }
        assertEquals(2, callbacks.size)
        callbacks[callbacks.lastIndex].invoke()
        callbacks[0].invoke()
        assertEquals(listOf(first, second), closes)
    }

    @Test fun defaultModuleThreadDoesNotBlockTheCallerDuringActualPhysicalRelease() {
        val coordinator = NativeMemoryReleaseCoordinator<Any>()
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val caller = Thread.currentThread()
        var releaseThread: Thread? = null
        try {
            coordinator.request(listOf(Any()), {}, {
                releaseThread = Thread.currentThread()
                started.countDown()
                check(finish.await(2, TimeUnit.SECONDS))
                closed.countDown()
            })
            assertTrue(started.await(1, TimeUnit.SECONDS))
            assertNotSame(caller, releaseThread)
            assertEquals(1, closed.count)
        } finally { finish.countDown(); assertTrue(closed.await(1, TimeUnit.SECONDS)) }
    }

    @Test fun failedClosePreservesItsCauseAndDoesNotAbandonAnotherCapturedOwner() {
        val error = IllegalStateException("native release failed")
        var reported: Throwable? = null
        val coordinator = NativeMemoryReleaseCoordinator<Int> { reported = it }
        val closed = ArrayList<Int>()
        var release: (() -> Unit)? = null
        coordinator.request(listOf(1, 2), {}, {
            if (it == 1) throw error
            closed += it
        }) { release = it }
        release!!()
        assertEquals(listOf(2), closed)
        assertSame(error, reported)
        assertSame(error, coordinator.lastFailure)
    }

    @Test fun rejectedExecutorKeepsTheCapturedOwnerForALaterScheduledClose() {
        val coordinator = NativeMemoryReleaseCoordinator<Any>()
        val owner = Any()
        val error = IllegalStateException("executor unavailable")
        val closes = AtomicInteger()
        coordinator.request(listOf(owner), {}, { closes.incrementAndGet() }) { throw error }
        assertSame(error, coordinator.lastFailure)
        assertEquals(0, closes.get())
        var release: (() -> Unit)? = null
        coordinator.request(emptyList(), {}, { fail("No new owner should be captured") }) { release = it }
        release!!()
        assertEquals(1, closes.get())
    }

    @Test fun failedPhysicalCloseCanRetryWithoutClosingItsSuccessfulPeerAgain() {
        val coordinator = NativeMemoryReleaseCoordinator<Int>()
        var attempts = 0
        val returned = ArrayList<Int>()
        var release: (() -> Unit)? = null
        coordinator.request(listOf(1, 2), {}, {
            if (it == 1 && attempts++ == 0) error("close not completed")
            returned += it
        }) { release = it }
        release!!()
        assertEquals(listOf(2), returned)
        release!!()
        assertEquals(listOf(2, 1), returned)
        release!!()
        assertEquals(listOf(2, 1), returned)
    }
}
