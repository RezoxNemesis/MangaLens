package com.mangalens.ui.downloads

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OwnedSavedVideoProbeTest {
    @Test fun timeoutDoesNotJoinBlockedReadAndRepeatedClicksStartNoMoreProducers() = runBlocking {
        val probe = OwnedSavedVideoProbe()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val returned = CountDownLatch(1)
        val closed = AtomicInteger(); val providerCancelled = AtomicInteger(); val reads = AtomicInteger(); var publications = 0
        val before = System.nanoTime()
        try {
            try {
                withTimeout(150L) {
                    probe.run(cancelProvider = { providerCancelled.incrementAndGet() }) { owner ->
                        reads.incrementAndGet(); owner.own(AutoCloseable { closed.incrementAndGet() }); entered.countDown()
                        try { release.await(); "late output" } finally { returned.countDown() }
                    }
                    publications++
                }
                fail("Blocked document provider passed its caller deadline")
            } catch (_: TimeoutCancellationException) { }
            assertEquals(0L, entered.count)
            assertTrue("Caller joined an uncooperative provider", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 1_000L)
            await { closed.get() == 1 && providerCancelled.get() == 1 }
            repeat(100) { assertBusy(probe) }
            assertEquals(1, reads.get()); assertEquals(0, publications)
        } finally { release.countDown(); assertTrue(returned.await(1, TimeUnit.SECONDS)) }
        awaitAvailable(probe)
        assertEquals(1, closed.get()); assertEquals(0, publications)
    }

    @Test fun descriptorDeliveredAfterCancellationClosesBeforeAnyReadOrPublication() = runBlocking {
        val probe = OwnedSavedVideoProbe()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val returned = CountDownLatch(1)
        val closed = AtomicInteger(); val reads = AtomicInteger(); var publications = 0
        try {
            try {
                withTimeout(150L) {
                    probe.run { owner ->
                        entered.countDown()
                        try {
                            release.await(); owner.own(AutoCloseable { closed.incrementAndGet() }); reads.incrementAndGet(); "late output"
                        } finally { returned.countDown() }
                    }
                    publications++
                }
                fail("Delayed descriptor passed canceled ownership")
            } catch (_: TimeoutCancellationException) { }
            assertEquals(0L, entered.count); assertEquals(0, closed.get()); assertEquals(0, reads.get())
            assertBusy(probe)
        } finally { release.countDown(); assertTrue(returned.await(1, TimeUnit.SECONDS)) }
        awaitAvailable(probe)
        assertEquals(1, closed.get()); assertEquals(0, reads.get()); assertEquals(0, publications)
    }

    @Test fun successfulPlainEvidenceReturnsOnlyAfterItsResourceCloses() = runBlocking {
        val probe = OwnedSavedVideoProbe()
        val closed = AtomicInteger(); val providerCancelled = AtomicInteger()
        val answer = probe.run(cancelProvider = { providerCancelled.incrementAndGet() }) { owner ->
            owner.own(AutoCloseable { closed.incrementAndGet() }); "saved evidence"
        }
        assertEquals("saved evidence", answer); assertEquals(1, closed.get()); assertEquals(0, providerCancelled.get())
        assertEquals("next", probe.run { "next" })
    }

    @Test fun retiringOneCoordinatorCannotCloseAnotherOwnersHandle() = runBlocking {
        val oldProbe = OwnedSavedVideoProbe(); val peerProbe = OwnedSavedVideoProbe()
        val oldEntered = CountDownLatch(1); val oldRelease = CountDownLatch(1); val oldReturned = CountDownLatch(1)
        val peerEntered = CountDownLatch(1); val peerRelease = CountDownLatch(1); val peerReturned = CountDownLatch(1)
        val oldClosed = AtomicInteger(); val peerClosed = AtomicInteger()
        val old = async {
            oldProbe.run { owner -> owner.own(AutoCloseable { oldClosed.incrementAndGet() }); oldEntered.countDown()
                try { oldRelease.await(); "old" } finally { oldReturned.countDown() } }
        }
        val peer = async {
            peerProbe.run { owner -> owner.own(AutoCloseable { peerClosed.incrementAndGet() }); peerEntered.countDown()
                try { peerRelease.await(); "peer" } finally { peerReturned.countDown() } }
        }
        try {
            await { oldEntered.count == 0L && peerEntered.count == 0L }
            old.cancelAndJoin(); await { oldClosed.get() == 1 }
            assertEquals(0, peerClosed.get()); assertBusy(oldProbe)
            peerRelease.countDown()
            assertEquals("peer", withTimeout(1_000L) { peer.await() }); assertEquals(1, peerClosed.get())
        } finally {
            old.cancel(); peer.cancel(); oldRelease.countDown(); peerRelease.countDown()
            assertTrue(oldReturned.await(1, TimeUnit.SECONDS)); assertTrue(peerReturned.await(1, TimeUnit.SECONDS))
        }
        awaitAvailable(oldProbe)
    }

    @Test fun blockingCancelAndCloseRunAwayFromCallerAndBothRetainCapacityUntilReturn() = runBlocking {
        val probe = OwnedSavedVideoProbe()
        val readEntered = CountDownLatch(1); val readRelease = CountDownLatch(1); val readReturned = CountDownLatch(1)
        val closeEntered = CountDownLatch(1); val closeRelease = CountDownLatch(1); val closeReturned = CountDownLatch(1)
        val cancelEntered = CountDownLatch(1); val cancelRelease = CountDownLatch(1); val cancelReturned = CountDownLatch(1)
        val callbackThreads = CopyOnWriteArrayList<Thread>(); val caller = Thread.currentThread()
        val job = async {
            probe.run(cancelProvider = {
                callbackThreads += Thread.currentThread(); cancelEntered.countDown()
                try { cancelRelease.await() } finally { cancelReturned.countDown() }
            }) { owner ->
                owner.own(AutoCloseable {
                    callbackThreads += Thread.currentThread(); closeEntered.countDown()
                    try { closeRelease.await() } finally { closeReturned.countDown() }
                })
                readEntered.countDown()
                try { readRelease.await(); "late" } finally { readReturned.countDown() }
            }
        }
        try {
            await { readEntered.count == 0L }
            val before = System.nanoTime(); job.cancelAndJoin()
            assertTrue("Cancellation invoked a blocking provider on its caller", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 1_000L)
            await { closeEntered.count == 0L && cancelEntered.count == 0L }
            assertEquals(2, callbackThreads.size); assertTrue(callbackThreads.all { it !== caller })
            assertBusy(probe)
            readRelease.countDown(); assertTrue(readReturned.await(1, TimeUnit.SECONDS)); assertBusy(probe)
            cancelRelease.countDown(); assertTrue(cancelReturned.await(1, TimeUnit.SECONDS)); assertBusy(probe)
            closeRelease.countDown(); assertTrue(closeReturned.await(1, TimeUnit.SECONDS))
            awaitAvailable(probe)
        } finally {
            job.cancel(); readRelease.countDown(); closeRelease.countDown(); cancelRelease.countDown()
            assertTrue(readReturned.await(1, TimeUnit.SECONDS)); assertTrue(closeReturned.await(1, TimeUnit.SECONDS))
            assertTrue(cancelReturned.await(1, TimeUnit.SECONDS))
        }
    }

    @Test fun failedCloseIsReportedWithoutProviderTextAndCannotDropOwnershipOrStartAnotherRead() = runBlocking {
        val receipts = CopyOnWriteArrayList<SavedVideoCleanupFailure>()
        val probe = OwnedSavedVideoProbe(reportCleanupFailure = { receipts += it })
        val attempts = AtomicInteger()
        try {
            probe.run { owner -> owner.own(AutoCloseable {
                attempts.incrementAndGet(); throw IOException("content://private/provider-message")
            }); "unusable result" }
            fail("A failed descriptor close was treated as safe release")
        } catch (failure: SavedVideoProbeCleanupException) {
            assertFalse(requireNotNull(failure.message).contains("content://"))
        }
        assertEquals(1, attempts.get())
        assertEquals(listOf(SavedVideoCleanupFailure(SavedVideoCleanupFailure.Phase.HANDLE_CLOSE, "IOException")), receipts)
        repeat(100) { assertBusy(probe, cleanupFailed = true) }
        assertEquals(1, attempts.get())
    }

    @Test fun failedProviderCancellationIsObservedAndRetainsTheOneSlotAfterTheReadReturns() = runBlocking {
        val receipts = CopyOnWriteArrayList<SavedVideoCleanupFailure>()
        val probe = OwnedSavedVideoProbe(reportCleanupFailure = { receipts += it })
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val returned = CountDownLatch(1)
        val calls = AtomicInteger()
        val job = async {
            probe.run(cancelProvider = { calls.incrementAndGet(); throw IOException("private cancellation message") }) {
                entered.countDown(); try { release.await(); "late" } finally { returned.countDown() }
            }
        }
        try {
            await { entered.count == 0L }; job.cancelAndJoin(); await { receipts.isNotEmpty() }
        } finally { job.cancel(); release.countDown(); assertTrue(returned.await(1, TimeUnit.SECONDS)) }
        assertEquals(1, calls.get())
        assertEquals(listOf(SavedVideoCleanupFailure(SavedVideoCleanupFailure.Phase.PROVIDER_CANCEL, "IOException")), receipts)
        assertBusy(probe, cleanupFailed = true)
    }

    @Test fun freshValidationWaitsForActualCloseAndCanRejectAChangedRow() = runBlocking {
        val probe = OwnedSavedVideoProbe()
        val closeEntered = CountDownLatch(1); val closeRelease = CountDownLatch(1); val closeReturned = CountDownLatch(1)
        val rowRevision = AtomicInteger(1); val validations = AtomicInteger(); var publications = 0
        val job = async {
            try {
                probe.run { owner ->
                    val captured = rowRevision.get()
                    owner.own(AutoCloseable {
                        closeEntered.countDown()
                        try { closeRelease.await() } finally { closeReturned.countDown() }
                    })
                    owner.closeReadHandle()
                    validations.incrementAndGet()
                    check(captured == rowRevision.get()) { "Saved row changed while its descriptor was closing" }
                    "must not publish"
                }
                publications++
                null
            } catch (changed: IllegalStateException) { changed }
        }
        try {
            await { closeEntered.count == 0L }
            assertEquals(0, validations.get()); assertBusy(probe)
            rowRevision.set(2); closeRelease.countDown()
            assertNotNull(withTimeout(1_000L) { job.await() })
            assertEquals(1, validations.get()); assertEquals(0, publications)
        } finally {
            closeRelease.countDown(); job.cancel()
            assertTrue(closeReturned.await(1, TimeUnit.SECONDS))
        }
        awaitAvailable(probe)
    }

    @Test fun successfulAssetClosesItsActualStreamOnceWithoutClosingTheDescriptorAgain() = runBlocking {
        val probe = OwnedSavedVideoProbe(); val descriptors = AtomicInteger(); val streams = AtomicInteger()
        val result = probe.run { owner ->
            val asset = OwnedSavedVideoAssetHandle(AutoCloseable { descriptors.incrementAndGet() }, {
                inputStream(close = { streams.incrementAndGet() })
            }, owner::checkActive)
            owner.own(asset)
            val nonempty = asset.readFirstByte()
            owner.closeReadHandle()
            nonempty
        }
        assertTrue(result); assertEquals(1, streams.get()); assertEquals(0, descriptors.get())
    }

    @Test fun failedStreamCreationClosesTheDescriptorOnceAndPreservesTheReadFailure() = runBlocking {
        val probe = OwnedSavedVideoProbe(); val descriptors = AtomicInteger()
        try {
            probe.run { owner ->
                val asset = OwnedSavedVideoAssetHandle(AutoCloseable { descriptors.incrementAndGet() }, {
                    throw IOException("stream creation failed")
                }, owner::checkActive)
                owner.own(asset); asset.readFirstByte()
            }
            fail("Stream creation failure became readable evidence")
        } catch (failure: IOException) { assertEquals("stream creation failed", failure.message) }
        assertEquals(1, descriptors.get()); awaitAvailable(probe)
    }

    @Test fun retiringDuringStreamCreationClosesTheLateStreamOnceBeforeItsFirstRead() = runBlocking {
        val probe = OwnedSavedVideoProbe()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val returned = CountDownLatch(1)
        val descriptors = AtomicInteger(); val streams = AtomicInteger(); val reads = AtomicInteger()
        val job = async {
            probe.run { owner ->
                val asset = OwnedSavedVideoAssetHandle(AutoCloseable { descriptors.incrementAndGet() }, {
                    entered.countDown(); release.await()
                    inputStream(read = { reads.incrementAndGet(); 7 }, close = { streams.incrementAndGet() })
                }, owner::checkActive)
                owner.own(asset)
                try { asset.readFirstByte() } finally { returned.countDown() }
            }
        }
        try {
            await { entered.count == 0L }; job.cancelAndJoin(); assertBusy(probe)
            assertEquals(0, descriptors.get()); assertEquals(0, streams.get()); assertEquals(0, reads.get())
        } finally { job.cancel(); release.countDown(); assertTrue(returned.await(1, TimeUnit.SECONDS)) }
        awaitAvailable(probe)
        assertEquals(1, streams.get()); assertEquals(0, descriptors.get()); assertEquals(0, reads.get())
    }

    @Test fun aStreamCloseFailureIsOwnedReportedAndCannotFallBackToASecondDescriptorClose() = runBlocking {
        val receipts = CopyOnWriteArrayList<SavedVideoCleanupFailure>()
        val probe = OwnedSavedVideoProbe(reportCleanupFailure = { receipts += it })
        val descriptors = AtomicInteger(); val streams = AtomicInteger()
        try {
            probe.run { owner ->
                val asset = OwnedSavedVideoAssetHandle(AutoCloseable { descriptors.incrementAndGet() }, {
                    inputStream(close = { streams.incrementAndGet(); throw IOException("private stream-close text") })
                }, owner::checkActive)
                owner.own(asset); asset.readFirstByte(); owner.closeReadHandle(); "must not publish"
            }
            fail("Stream close failure escaped owned cleanup")
        } catch (_: SavedVideoProbeCleanupException) { }
        assertEquals(1, streams.get()); assertEquals(0, descriptors.get())
        assertEquals(listOf(SavedVideoCleanupFailure(SavedVideoCleanupFailure.Phase.HANDLE_CLOSE, "IOException")), receipts)
        assertBusy(probe, cleanupFailed = true)
    }

    @Test fun cancellingAnAssetReadRetainsCapacityUntilTheActualReadAndStreamCloseBothReturn() = runBlocking {
        val probe = OwnedSavedVideoProbe()
        val readEntered = CountDownLatch(1); val readRelease = CountDownLatch(1); val readReturned = CountDownLatch(1)
        val closeEntered = CountDownLatch(1); val closeRelease = CountDownLatch(1); val closeReturned = CountDownLatch(1)
        val descriptors = AtomicInteger(); val streams = AtomicInteger()
        val job = async {
            probe.run { owner ->
                val asset = OwnedSavedVideoAssetHandle(AutoCloseable { descriptors.incrementAndGet() }, {
                    inputStream(read = { readEntered.countDown(); readRelease.await(); 7 }, close = {
                        streams.incrementAndGet(); closeEntered.countDown()
                        try { closeRelease.await() } finally { closeReturned.countDown() }
                    })
                }, owner::checkActive)
                owner.own(asset)
                try { asset.readFirstByte(); owner.checkActive() } finally { readReturned.countDown() }
            }
        }
        try {
            await { readEntered.count == 0L }; job.cancelAndJoin(); await { closeEntered.count == 0L }
            assertEquals(0, descriptors.get()); assertBusy(probe)
            readRelease.countDown(); assertTrue(readReturned.await(1, TimeUnit.SECONDS)); assertBusy(probe)
            closeRelease.countDown(); assertTrue(closeReturned.await(1, TimeUnit.SECONDS))
            awaitAvailable(probe); assertEquals(1, streams.get()); assertEquals(0, descriptors.get())
        } finally {
            job.cancel(); readRelease.countDown(); closeRelease.countDown()
            assertTrue(readReturned.await(1, TimeUnit.SECONDS)); assertTrue(closeReturned.await(1, TimeUnit.SECONDS))
        }
    }

    private fun inputStream(read: () -> Int = { 7 }, close: () -> Unit): InputStream = object : InputStream() {
        override fun read(): Int = read.invoke()
        override fun close() = close.invoke()
    }

    private suspend fun assertBusy(probe: OwnedSavedVideoProbe, cleanupFailed: Boolean = false) {
        var entered = false
        try { probe.run { entered = true; "must not run" }; fail("A retained provider read admitted another producer") }
        catch (busy: SavedVideoProbeBusyException) {
            assertEquals(cleanupFailed, requireNotNull(busy.message).contains("Restart the app"))
        }
        assertFalse(entered)
    }

    private suspend fun awaitAvailable(probe: OwnedSavedVideoProbe) = withTimeout(1_000L) {
        while (true) {
            try { assertEquals("settled", probe.run { "settled" }); break }
            catch (_: SavedVideoProbeBusyException) { delay(5L) }
        }
    }

    private suspend fun await(condition: () -> Boolean) = withTimeout(1_000L) {
        while (!condition()) delay(5L)
    }
}
