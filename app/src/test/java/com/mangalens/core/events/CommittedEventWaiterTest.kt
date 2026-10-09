package com.mangalens.core.events

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommittedEventWaiterTest {
    private val identity = TaskEventIdentity(EventIdentity.task("task-a"), EventIdentity.source("native-a"),
        EventIdentity.owner("owner-a"), 3)
    private fun filter() = TaskEventFilter(identity, setOf(AppEventType.DOWNLOAD_COMPLETE, AppEventType.DOWNLOAD_FAILED))
    private fun hint() = AppEvent.TaskHint(AppEventType.DOWNLOAD_COMPLETE, identity)

    @Test fun aCompletionHintOnlyRequeriesAndCannotCompleteWithoutCommittedEvidence() = runTest {
        val bus = AppEventBus()
        var durable: String? = null
        var reads = 0
        val job = async { CommittedEventWaiter.awaitCurrent(bus.subscribe(filter()), 2_000, { true }) { reads++; durable } }
        runCurrent()
        assertEquals(1, reads)
        bus.publish(hint()); runCurrent()
        assertEquals(2, reads)
        assertFalse(job.isCompleted)
        durable = "committed-receipt"
        // Duplicate completion cannot wake twice, but the periodic durable read
        // observes its real journal even when every subsequent hint is dropped.
        assertEquals(0, bus.publish(hint()))
        advanceTimeBy(2_000); runCurrent()
        assertEquals("committed-receipt", job.await())
    }

    @Test fun matchingHintWakesTheCapturedTaskBeforeTheFallbackInterval() = runTest {
        val bus = AppEventBus()
        var durable: String? = null
        val job = async { CommittedEventWaiter.awaitCurrent(bus.subscribe(filter()), 2_000, { true }) { durable } }
        runCurrent()
        durable = "native-current-generation"
        bus.publish(hint()); runCurrent()
        assertEquals(0, testScheduler.currentTime)
        assertEquals("native-current-generation", job.await())
    }

    @Test fun aWrongTaskHintDoesNotCauseAReadOrAnEffect() = runTest {
        val bus = AppEventBus()
        var reads = 0
        val job = async { CommittedEventWaiter.awaitCurrent<String>(bus.subscribe(filter()), 2_000, { true }) { reads++; null } }
        runCurrent()
        bus.publish(AppEvent.TaskHint(AppEventType.DOWNLOAD_COMPLETE, identity.copy(task = EventIdentity.task("other-task"))))
        runCurrent()
        assertEquals(1, reads)
        assertFalse(job.isCompleted)
        job.cancelAndJoin()
    }

    @Test fun alreadyCommittedResultIsObservedWithoutAnyEventReplay() = runTest {
        val bus = AppEventBus()
        bus.publish(hint())
        assertEquals("already-committed", CommittedEventWaiter.awaitCurrent(bus.subscribe(filter()), 2_000, { true }) { "already-committed" })
    }

    @Test fun changedJournalEpochDuringReadRejectsTheOldNativeReceipt() = runTest {
        val bus = AppEventBus()
        var current = true
        val result = CommittedEventWaiter.awaitCurrent(bus.subscribe(filter()), 2_000, { current }) {
            current = false
            "stale-generation-receipt"
        }
        assertNull(result)
    }

    @Test fun cancelledWaiterRemovesItsSubscriptionAndNeverRunsAgain() = runTest {
        val bus = AppEventBus(maxSubscriptions = 1)
        var reads = 0
        val job = async { CommittedEventWaiter.awaitCurrent<String>(bus.subscribe(filter()), 2_000, { true }) { reads++; null } }
        runCurrent()
        job.cancelAndJoin()
        bus.publish(hint()); advanceTimeBy(20_000); runCurrent()
        assertEquals(1, reads)
        bus.subscribe(filter()).use { assertEquals(1, bus.publish(hint())) }
    }

    @Test fun lostHintStillReadsTheDurableResultAtTheBoundedFallback() = runTest {
        val bus = AppEventBus()
        var durable: String? = null
        val job = async { CommittedEventWaiter.awaitCurrent(bus.subscribe(filter()), 2_000, { true }) { durable } }
        runCurrent()
        durable = "published-media-row"
        advanceTimeBy(1_999); runCurrent()
        assertFalse(job.isCompleted)
        advanceTimeBy(1); runCurrent()
        assertEquals("published-media-row", job.await())
    }

    @Test fun exhaustedHintCapacityStillObservesCommittedStateWithoutASecondEffect() = runTest {
        val bus = AppEventBus(maxSubscriptions = 1)
        bus.subscribe(filter()).use {
            val unavailable = bus.trySubscribe(filter())
            assertNull(unavailable)
            var durable: String? = null
            var reads = 0
            val job = async { CommittedEventWaiter.awaitCurrent(unavailable, 2_000, { true }) { reads++; durable } }
            runCurrent()
            durable = "committed-without-an-observer-slot"
            assertEquals(1, bus.publish(hint()))
            runCurrent()
            assertEquals(1, reads)
            assertFalse(job.isCompleted)
            advanceTimeBy(2_000); runCurrent()
            assertEquals("committed-without-an-observer-slot", job.await())
            assertEquals(2, reads)
        }
    }

    @Test fun cancellationWithoutAnObserverSlotStopsAllPeriodicReads() = runTest {
        var reads = 0
        val job = async { CommittedEventWaiter.awaitCurrent<String>(null, 2_000, { true }) { reads++; null } }
        runCurrent()
        job.cancelAndJoin()
        advanceTimeBy(20_000); runCurrent()
        assertEquals(1, reads)
    }
}
