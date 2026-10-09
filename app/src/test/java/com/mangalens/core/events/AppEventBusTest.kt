package com.mangalens.core.events

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppEventBusTest {
    private fun identity(task: String = "task-a", source: String = "source-a", owner: String = "owner-a", epoch: Long = 7) =
        TaskEventIdentity(EventIdentity.task(task), EventIdentity.source(source), EventIdentity.owner(owner), epoch)
    private fun hint(identity: TaskEventIdentity = identity(), type: AppEventType = AppEventType.DOWNLOAD_COMPLETE) =
        AppEvent.TaskHint(type, identity)
    private fun filter(identity: TaskEventIdentity = identity()) = TaskEventFilter(identity,
        setOf(AppEventType.DOWNLOAD_COMPLETE, AppEventType.DOWNLOAD_FAILED))

    @Test fun taskSourceOwnerAndGenerationMustAllMatchTheCapturedSubscription() {
        val bus = AppEventBus()
        bus.subscribe(filter()).use { subscription ->
            listOf(identity(task = "task-b"), identity(source = "source-b"), identity(owner = "owner-b"), identity(epoch = 8))
                .forEach { assertEquals(0, bus.publish(hint(it))) }
            assertNull(subscription.poll())
            assertEquals(1, bus.publish(hint()))
            assertEquals(hint(), subscription.poll())
        }
    }

    @Test fun anotherTaskWithTheSameSourceNeverReceivesTheOwnersHint() {
        val bus = AppEventBus()
        bus.subscribe(filter(identity(task = "peer-task"))).use { peer ->
            bus.subscribe(filter()).use { owner ->
                assertEquals(1, bus.publish(hint()))
                assertNull(peer.poll())
                assertEquals(hint(), owner.poll())
            }
        }
    }

    @Test fun sourceContentAndPrivatePathsCannotBePutInIdentityFields() {
        for (invalid in listOf("https://site.test/private?token=value", "/data/data/app/private", "cookie=value", "manga dialogue", "a".repeat(129))) {
            try { EventIdentity.source(invalid); fail("Content must not become an event identity") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(64, EventIdentity.source("orez-opaque-request-step-2").digest.length)
        assertFalse(EventIdentity.source("opaque-secret-id").toString().contains("opaque-secret-id"))
        assertNotEquals(EventIdentity.task("same-id"), EventIdentity.source("same-id"))
    }

    @Test fun aNegativeExecutionGenerationCannotCreateAValidTaskHint() {
        try { identity(epoch = -1); fail("Generation must be nonnegative") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun acceptedKindsAreCapturedAndNotChangedByTheCallersMutableSet() {
        val kinds = mutableSetOf(AppEventType.DOWNLOAD_COMPLETE)
        val bus = AppEventBus()
        bus.subscribe(TaskEventFilter(identity(), kinds)).use { subscription ->
            kinds += AppEventType.DOWNLOAD_FAILED
            assertEquals(0, bus.publish(hint(type = AppEventType.DOWNLOAD_FAILED)))
            assertNull(subscription.poll())
        }
    }

    @Test fun duplicatesAreSuppressedWithoutManufacturingAnAdditionalWakeup() {
        val bus = AppEventBus()
        bus.subscribe(filter()).use { subscription ->
            assertEquals(1, bus.publish(hint()))
            assertEquals(hint(), subscription.poll())
            repeat(100) { assertEquals(0, bus.publish(hint())) }
            assertNull(subscription.poll())
        }
    }

    @Test fun recoveredMemoryCanReportANewCriticalTransitionToTheSameObserver() {
        AppEvents.bus.subscribe(MemoryEventFilter).use { subscription ->
            AppEvents.memoryPressure(MemoryPressureLevel.CRITICAL)
            val first = subscription.poll()
            assertNotNull(first)
            // The governor reports only pressure transitions. NORMAL is observed
            // by its authoritative state, so the next critical notification must
            // carry a fresh bounded occurrence even though its level is unchanged.
            AppEvents.memoryPressure(MemoryPressureLevel.CRITICAL)
            val second = subscription.poll()
            assertNotNull("Recovered pressure must not be suppressed forever", second)
            assertNotEquals(first, second)
        }
    }

    @Test fun stalledSubscriberRetainsOnlyItsConfiguredNewestHints() {
        val bus = AppEventBus(bufferCapacity = 2)
        val memory = bus.subscribe(MemoryEventFilter)
        val moderate = AppEvent.MemoryPressure(MemoryPressureLevel.MODERATE)
        val critical = AppEvent.MemoryPressure(MemoryPressureLevel.CRITICAL)
        bus.publish(moderate); bus.publish(critical)
        assertEquals(moderate, memory.poll())
        assertEquals(critical, memory.poll())
        assertNull(memory.poll())
        memory.close()
        // Distinct task identities cannot flood the exact filter; use all declared
        // native hint kinds for one immutable captured ownership identity.
        bus.subscribe(TaskEventFilter(identity(), setOf(AppEventType.CHAPTER_LOADED,
            AppEventType.OCR_LOW_CONFIDENCE, AppEventType.STREAM_EXPIRED))).use { subscription ->
            bus.publish(hint(type = AppEventType.CHAPTER_LOADED))
            bus.publish(hint(type = AppEventType.OCR_LOW_CONFIDENCE))
            bus.publish(hint(type = AppEventType.STREAM_EXPIRED))
            assertEquals(AppEventType.OCR_LOW_CONFIDENCE, subscription.poll()?.type)
            assertEquals(AppEventType.STREAM_EXPIRED, subscription.poll()?.type)
            assertNull(subscription.poll())
        }
    }

    @Test fun closingDiscardsQueuedHintsAndReleasesTheSubscriberSlot() {
        val bus = AppEventBus(maxSubscriptions = 1)
        val old = bus.subscribe(filter())
        bus.publish(hint())
        old.close(); old.close()
        assertNull(old.poll())
        assertEquals(0, bus.publish(hint()))
        bus.subscribe(filter()).use { next ->
            assertEquals(1, bus.publish(hint()))
            assertEquals(hint(), next.poll())
        }
    }

    @Test fun cancellingTheRealFlowCollectorDisposesItsRegistration() = runTest {
        val bus = AppEventBus(maxSubscriptions = 1)
        val subscription = bus.subscribe(filter())
        val collector = launch { subscription.events.collect { } }
        runCurrent()
        collector.cancelAndJoin()
        bus.subscribe(filter()).use { replacement ->
            assertEquals(1, bus.publish(hint()))
            assertEquals(hint(), replacement.poll())
        }
    }

    @Test fun thereIsNoReplayToALaterSubscriber() {
        val bus = AppEventBus()
        assertEquals(0, bus.publish(hint()))
        bus.subscribe(filter()).use { assertNull(it.poll()) }
    }

    @Test fun explicitModelFilterCannotObserveAnotherModelOrTaskHint() {
        val pin = ModelEventIdentity.sha256("a".repeat(64))
        val bus = AppEventBus()
        bus.subscribe(ModelEventFilter(pin)).use { subscription ->
            assertEquals(0, bus.publish(AppEvent.ModelReady(ModelEventIdentity.sha256("b".repeat(64)))))
            assertEquals(0, bus.publish(hint()))
            assertEquals(1, bus.publish(AppEvent.ModelReady(pin)))
            assertEquals(AppEvent.ModelReady(pin), subscription.poll())
        }
    }

    @Test fun globalResourceHintCannotMasqueradeAsTheExactNativeTaskCompletion() {
        val bus = AppEventBus()
        bus.subscribe(filter()).use { subscription ->
            assertEquals(0, bus.publish(AppEvent.MemoryPressure(MemoryPressureLevel.CRITICAL)))
            assertEquals(0, bus.publish(AppEvent.ModelReady(ModelEventIdentity.sha256("a".repeat(64)))))
            assertNull(subscription.poll())
        }
    }

    @Test fun configurationCannotIncreaseSafetyCapsOrUseZeroCapacity() {
        for (create in listOf<() -> AppEventBus>(
            { AppEventBus(bufferCapacity = 9) }, { AppEventBus(bufferCapacity = 0) },
            { AppEventBus(maxSubscriptions = 33) }, { AppEventBus(maxSubscriptions = 0) },
            { AppEventBus(duplicateCapacity = 65) }, { AppEventBus(duplicateCapacity = 0) }
        )) {
            try { create(); fail("Unsafe capacity must be rejected") }
            catch (_: IllegalArgumentException) { }
        }
        val bus = AppEventBus(maxSubscriptions = 1)
        bus.subscribe(filter()).use {
            try { bus.subscribe(filter()); fail("The subscriber count must stay bounded") }
            catch (_: IllegalStateException) { }
        }
    }
}
