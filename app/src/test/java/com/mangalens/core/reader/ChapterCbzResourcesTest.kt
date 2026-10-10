package com.mangalens.core.reader

import com.mangalens.ui.downloads.OwnedSavedVideoProbe
import com.mangalens.ui.downloads.SavedVideoProbeBusyException
import com.mangalens.ui.downloads.SavedVideoProbeCleanupException
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Controlled ownership/real-thread oracles authored UNRUN, not physical-provider evidence. */
class ChapterCbzResourcesTest {
    private fun awaitPrivateReturnWait(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (thread.state != Thread.State.WAITING && thread.isAlive && System.nanoTime() < deadline) Thread.yield()
        assertEquals("Composite must be waiting for actual private producer return", Thread.State.WAITING, thread.state)
    }
    @Test fun failedPrivateCloseRetainsHandleAndNeverRetriesUnknownClose() {
        val resources = ChapterCbzResources(); val closes = AtomicInteger()
        val handle = resources.ownPrivate(AutoCloseable { closes.incrementAndGet(); throw IOException("held close failed") })
        try { resources.closePrivate(handle); fail("unproven close") } catch (_: IOException) {}
        resources.finishPrivateWork()
        repeat(2) { try { resources.close(); fail("retained cleanup failure") } catch (_: IOException) {} }
        assertEquals(1, closes.get()); assertFalse(resources.privateReleaseProven())
    }
    @Test fun successfulPrivateCloseIsSingleAndProvesPrivateRelease() {
        val resources = ChapterCbzResources(); val closes = AtomicInteger()
        val handle = resources.ownPrivate(AutoCloseable { closes.incrementAndGet() })
        resources.closePrivate(handle); resources.closePrivate(handle); resources.finishPrivateWork(); resources.close(); resources.close()
        assertEquals(1, closes.get()); assertTrue(resources.privateReleaseProven())
    }
    @Test fun registeredButUnstartedCompositeCanRetireWithoutDeadlockingLateAdmission() {
        val resources = ChapterCbzResources()
        val done = CountDownLatch(1); val thread = Thread { resources.close(); done.countDown() }.apply { start() }
        assertTrue(done.await(2, TimeUnit.SECONDS)); thread.join(2000)
        try { resources.beginPrivateWork(); fail("retired work started") } catch (_: kotlinx.coroutines.CancellationException) {}
    }
    @Test fun cancellationDoesNotClosePrivateHandleUntilItsProducerReturns() {
        val resources = ChapterCbzResources(); val closes = AtomicInteger(); val closeEntered = CountDownLatch(1)
        val handle = resources.ownPrivate(AutoCloseable { closes.incrementAndGet() })
        val thread = Thread { closeEntered.countDown(); resources.close() }.apply { start() }
        assertTrue(closeEntered.await(2, TimeUnit.SECONDS)); awaitPrivateReturnWait(thread)
        assertFalse(resources.privateReleaseProven()); assertEquals(0, closes.get()); assertTrue(thread.isAlive)
        resources.closePrivate(handle); resources.finishPrivateWork(); thread.join(2000)
        assertFalse(thread.isAlive); assertEquals(1, closes.get())
    }
    @Test fun providerCloseCanUnblockOutputWhilePrivateProducerStillOwnsItsInput() {
        val resources = ChapterCbzResources(); val privateCloses = AtomicInteger(); val providerClosed = CountDownLatch(1)
        val handle = resources.ownPrivate(AutoCloseable { privateCloses.incrementAndGet() })
        resources.ownProvider(AutoCloseable { providerClosed.countDown() })
        val thread = Thread { resources.close() }.apply { start() }
        assertTrue(providerClosed.await(2, TimeUnit.SECONDS)); assertEquals(0, privateCloses.get()); assertTrue(thread.isAlive)
        resources.closePrivate(handle); resources.finishPrivateWork(); thread.join(2000); assertFalse(thread.isAlive)
    }
    @Test fun lateProviderDeliveredAfterRetirementClosesExactlyOnce() {
        val resources = ChapterCbzResources(); resources.beginPrivateWork()
        val closing = CountDownLatch(1); val closes = AtomicInteger()
        val thread = Thread { closing.countDown(); resources.close() }.apply { start() }
        assertTrue(closing.await(2, TimeUnit.SECONDS)); awaitPrivateReturnWait(thread)
        // Actual retirement is already waiting before the delayed provider open returns.
        resources.ownProvider(AutoCloseable { closes.incrementAndGet() }); resources.finishPrivateWork()
        thread.join(2000); assertFalse(thread.isAlive); assertEquals(1, closes.get())
    }
    @Test fun blockedRealCloseMustReturnBeforeCloseCompletionCanPublish() {
        val resources = ChapterCbzResources(); val entered = CountDownLatch(1); val release = CountDownLatch(1); val completed = CountDownLatch(1)
        resources.ownProvider(AutoCloseable { entered.countDown(); check(release.await(2, TimeUnit.SECONDS)) })
        resources.finishPrivateWork()
        val thread = Thread { resources.close(); completed.countDown() }.apply { start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS)); assertFalse(completed.await(50, TimeUnit.MILLISECONDS))
        release.countDown(); assertTrue(completed.await(2, TimeUnit.SECONDS)); thread.join(2000)
    }
    @Test fun actualApplicationProducerRetainsAdmissionAfterPrivateCleanupFailure() = runBlocking {
        val probe = OwnedSavedVideoProbe(); val closes = AtomicInteger()
        try {
            probe.run { owner ->
                val resources = ChapterCbzResources(); owner.own(resources); resources.beginPrivateWork()
                try {
                    val handle = resources.ownPrivate(AutoCloseable { closes.incrementAndGet(); throw IOException("unproven") })
                    resources.closePrivate(handle)
                } finally { resources.finishPrivateWork() }
            }
            fail("Cleanup failure was accepted")
        } catch (_: SavedVideoProbeCleanupException) {}
        var secondEntered = false
        try { probe.run { secondEntered = true }; fail("Failed owner released capacity") } catch (_: SavedVideoProbeBusyException) {}
        assertFalse(secondEntered); assertEquals(1, closes.get())
    }
}
