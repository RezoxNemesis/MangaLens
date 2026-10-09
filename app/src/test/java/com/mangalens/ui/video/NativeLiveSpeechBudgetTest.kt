package com.mangalens.ui.video

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import com.mangalens.core.compute.NativeComputeAdmission
import com.mangalens.core.compute.NativeComputePrecondition
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class NativeLiveSpeechBudgetTest {
    @Test fun sourceChangedDuringSharedQueueIsRejectedBeforeNativeAdmission() = heldTypedPrecondition("source")
    @Test fun modelChangedDuringSharedQueueIsRejectedBeforeNativeAdmission() = heldTypedPrecondition("model")
    @Test fun generationChangedDuringSharedQueueIsRejectedBeforeNativeAdmission() = heldTypedPrecondition("generation")

    private fun heldTypedPrecondition(field: String) = runBlocking {
        val admission = NativeComputeAdmission(pollMs = 5)
        val holder = admission.acquire(NativeComputeAdmission.Priority.BACKGROUND) { true }!!
        val captured = NativeScope("source-sha", "model-sha", "generation1")
        val current = java.util.concurrent.atomic.AtomicReference(captured)
        val queued = CountDownLatch(1)
        val queueChecks = AtomicInteger()
        val entries = AtomicInteger()
        withContext(NativeComputePrecondition { waited ->
            currentCoroutineContext().ensureActive()
            check(waited) { "Held native queue was not accounted for" }
            check(current.get() == captured) { "Captured $field changed" }
        }) {
            supervisorScope {
                val work = async(Dispatchers.IO) { NativeLiveSpeechBudget(1000, { 0 }, 5)
                    .run(Mutex(), { if (queueChecks.incrementAndGet() >= 4) queued.countDown(); true }, admission) { immediate(entries) } }
                try {
                    assertTrue(queued.await(2, TimeUnit.SECONDS))
                    current.set(when (field) {
                        "source" -> captured.copy(source = "replacement-sha")
                        "model" -> captured.copy(model = "replacement-model-sha")
                        else -> captured.copy(generation = "generation2")
                    })
                    holder.close()
                    try { withTimeout(1000) { work.await() }; fail("Changed $field reached native inference after queueing") }
                    catch (failure: IllegalStateException) { assertEquals("Captured $field changed", failure.message) }
                    assertEquals(0, entries.get())
                } finally { holder.close(); work.cancelAndJoin() }
            }
        }
    }
    private data class NativeScope(val source: String, val model: String, val generation: String)

    @Test fun sharedLaneWaitUsesTheLiveWallBudgetWithoutAbortingAnActivePeer() = runBlocking {
        val admission = NativeComputeAdmission(pollMs = 5)
        val peer = HeldCall()
        val firstWork = async(Dispatchers.IO) { NativeLiveSpeechBudget(1000, { 0 }, 5).run(Mutex(), { true }, admission) { peer } }
        val clock = AtomicLong()
        val queued = CountDownLatch(1)
        val queueChecks = AtomicInteger()
        val entries = AtomicInteger()
        var secondWork: kotlinx.coroutines.Deferred<NativeLiveSpeechOutcome<String>>? = null
        try {
            assertTrue(peer.entered.await(2, TimeUnit.SECONDS))
            secondWork = async(Dispatchers.IO) { NativeLiveSpeechBudget(100, clock::get, 5)
                .run(Mutex(), { if (queueChecks.incrementAndGet() >= 4) queued.countDown(); true }, admission) { immediate(entries) } }
            assertTrue(queued.await(2, TimeUnit.SECONDS))
            clock.set(100)
            assertEquals(NativeLiveSpeechOutcome.TimedOut, withTimeout(1000) { secondWork.await() })
            assertEquals(0, entries.get())
            assertFalse("An expired queued request aborted a different active native handle", peer.cancelled.await(100, TimeUnit.MILLISECONDS))
        } finally {
            peer.returnNative.countDown(); peer.finishCleanup.countDown()
            firstWork.cancelAndJoin(); secondWork?.cancelAndJoin()
        }
    }

    @Test fun twoEngineLocksCannotOverlapTheirPhysicalNativeInference() = runBlocking {
        val first = HeldCall()
        val secondEntered = CountDownLatch(1)
        val firstWork = async(Dispatchers.IO) { NativeLiveSpeechBudget(1000, { 0 }, 5).run(Mutex(), { true }) { first } }
        var secondWork: kotlinx.coroutines.Deferred<NativeLiveSpeechOutcome<String>>? = null
        try {
            assertTrue(first.entered.await(2, TimeUnit.SECONDS))
            secondWork = async(Dispatchers.IO) {
                NativeLiveSpeechBudget(1000, { 0 }, 5).run(Mutex(), { true }) {
                    object : NativeLiveSpeechInvocation<String> {
                        override fun infer(): String { secondEntered.countDown(); return "second" }
                        override fun cancel() { }
                        override fun finish() { }
                    }
                }
            }
            assertFalse("Two engine locks admitted concurrent physical native work", secondEntered.await(150, TimeUnit.MILLISECONDS))
            first.returnNative.countDown()
            assertTrue(first.cleanupEntered.await(2, TimeUnit.SECONDS))
            assertFalse("The shared compute lane released before actual native cleanup", secondEntered.await(100, TimeUnit.MILLISECONDS))
            first.finishCleanup.countDown()
            assertEquals(NativeLiveSpeechOutcome.Completed("stale"), firstWork.await())
            assertEquals(NativeLiveSpeechOutcome.Completed("second"), withTimeout(1000) { secondWork.await() })
        } finally {
            first.returnNative.countDown(); first.finishCleanup.countDown()
            firstWork.cancelAndJoin(); secondWork?.cancelAndJoin()
        }
    }

    @Test fun oldCompletedWindowCannotAppendAfterSeekClear() = heldPublicationAfterReplacement(generated = false)

    @Test fun oldCompletedWindowCannotAppendIntoNewGeneratedTrack() = heldPublicationAfterReplacement(generated = true)

    @Test fun oldTimeoutCannotChangeNewGenerationLatencyOrStatus() = heldPublicationAfterReplacement(generated = false, timeout = true)

    @Test fun oldErrorCannotOverwriteNewGeneratedTrackStatus() = heldPublicationAfterReplacement(generated = true, error = true)

    private fun heldPublicationAfterReplacement(generated: Boolean, timeout: Boolean = false, error: Boolean = false) = runBlocking {
        val guard = Any()
        val generation = AtomicLong(1)
        var state = PublicationState(listOf("old visible cue"), "old source", 17, 2, 8, false)
        val mappedOldOutcome = CountDownLatch(1)
        val releaseOldOutcome = CountDownLatch(1)
        val old = async(Dispatchers.IO) {
            // Faithful engine trigger: G1 already passed the first check before mapping its outcome.
            check(generation.get() == 1L)
            mappedOldOutcome.countDown()
            check(releaseOldOutcome.await(2, TimeUnit.SECONDS))
            publishLiveSpeechWindow(guard, { generation.get() == 1L }) {
                state = when {
                    error -> state.copy(status = "old inference error")
                    timeout -> state.copy(status = "old timeout", inferenceMs = 20000, processedWindows = state.processedWindows + 1, chunkSeconds = 3)
                    else -> state.copy(cues = state.cues + "stale G1 cue", status = "old completed", inferenceMs = 100, processedWindows = state.processedWindows + 1)
                }
            }
        }
        try {
            assertTrue(mappedOldOutcome.await(2, TimeUnit.SECONDS))
            val replacement = PublicationState(if (generated) listOf("new generated cue") else emptyList(),
                "new source", 31, 7, 6, generated)
            synchronized(guard) { generation.incrementAndGet(); state = replacement }
            releaseOldOutcome.countDown()
            assertFalse("stale G1 was allowed to publish into G2", withTimeout(1000) { old.await() })
            assertEquals(replacement, state)
        } finally { releaseOldOutcome.countDown(); old.cancelAndJoin() }
    }

    @Test fun generationMutationCannotInterleaveWithAcceptedPublication() = runBlocking {
        val guard = Any()
        val generation = AtomicLong(1)
        var status = "old"
        val publicationEntered = CountDownLatch(1)
        val finishPublication = CountDownLatch(1)
        val mutationAttempted = CountDownLatch(1)
        val mutationEntered = CountDownLatch(1)
        val publish = async(Dispatchers.IO) {
            publishLiveSpeechWindow(guard, { generation.get() == 1L }) {
                publicationEntered.countDown()
                check(finishPublication.await(2, TimeUnit.SECONDS))
                status = "G1 published"
            }
        }
        var mutation: kotlinx.coroutines.Deferred<Unit>? = null
        try {
            assertTrue(publicationEntered.await(2, TimeUnit.SECONDS))
            mutation = async(Dispatchers.IO) {
                mutationAttempted.countDown()
                synchronized(guard) { mutationEntered.countDown(); generation.incrementAndGet(); status = "G2 cleared" }
            }
            assertTrue(mutationAttempted.await(2, TimeUnit.SECONDS))
            assertFalse("generation changed inside an accepted publication", mutationEntered.await(100, TimeUnit.MILLISECONDS))
            finishPublication.countDown()
            assertTrue(withTimeout(1000) { publish.await() })
            withTimeout(1000) { mutation.await() }
            assertEquals("G2 cleared", status)
        } finally { finishPublication.countDown(); publish.cancelAndJoin(); mutation?.cancelAndJoin() }
    }

    private data class PublicationState(val cues: List<String>, val status: String, val inferenceMs: Long,
        val processedWindows: Int, val chunkSeconds: Int, val generated: Boolean)

    @Test fun delayedOldAbortCannotCancelReplacedHandle() = heldAbortAfterReplacement(41L, 42L)

    @Test fun delayedOldAbortCannotCancelNewCallOnSameHandle() = heldAbortAfterReplacement(41L, 41L)

    private fun heldAbortAfterReplacement(oldHandle: Long, newHandle: Long) = runBlocking {
        val handleGuard = Any()
        val currentHandle = AtomicLong(oldHandle)
        val cancelledHandles = ConcurrentLinkedQueue<Long>()
        val handles = NativeLiveSpeechHandles(handleGuard, currentHandle::get) { cancelledHandles.add(it); Unit }
        val old = handles.begin(oldHandle)
        val callbackHasOldGeneration = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val callback = async(Dispatchers.IO) {
            // The old watchdog has already passed its generation check before waiting on the guard.
            callbackHasOldGeneration.countDown()
            check(releaseCallback.await(2, TimeUnit.SECONDS))
            handles.cancel(old)
        }
        try {
            assertTrue(callbackHasOldGeneration.await(2, TimeUnit.SECONDS))
            val replacement = synchronized(handleGuard) {
                handles.finish(old) // Native returned; retire it before handing off the engine mutex.
                currentHandle.set(newHandle)
                handles.begin(newHandle)
            }
            releaseCallback.countDown()
            withTimeout(1000) { callback.await() }
            assertTrue("an older callback cancelled the replacement invocation", cancelledHandles.isEmpty())
            handles.cancel(replacement)
            assertEquals(listOf(newHandle), cancelledHandles.toList())
            handles.finish(replacement)
        } finally { releaseCallback.countDown(); callback.cancelAndJoin() }
    }

    @Test fun expiredWindowQueuedBehindModelLoadReturnsWithoutAnyNativeEntry() = runBlocking {
        val mutex = Mutex(locked = true)
        val clock = AtomicLong(0)
        val started = CountDownLatch(1)
        val entries = AtomicInteger()
        val work = async(Dispatchers.IO) {
            NativeLiveSpeechBudget(100, { val now = clock.get(); started.countDown(); now }, 5).run(mutex, { true }) {
                immediate(entries)
            }
        }
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            clock.set(100)
            assertEquals("expiry must stop a queued window before load finishes", NativeLiveSpeechOutcome.TimedOut,
                withTimeout(500) { work.await() })
            assertEquals(0, entries.get())
        } finally { mutex.unlock(); work.cancelAndJoin() }
    }

    @Test fun callerCancellationWhileQueuedDoesNotEnterNativeAndLeavesGateUsable() = runBlocking {
        val mutex = Mutex(locked = true)
        val started = CountDownLatch(1)
        val entries = AtomicInteger()
        val work = async(Dispatchers.IO) {
            NativeLiveSpeechBudget(1000, { started.countDown(); 0 }, 5).run(mutex, { true }) { immediate(entries) }
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        work.cancelAndJoin()
        assertEquals(0, entries.get())
        assertTrue(mutex.isLocked)
        mutex.unlock()
        assertEquals(NativeLiveSpeechOutcome.Completed("ok"),
            NativeLiveSpeechBudget(1000, { 0 }, 5).run(mutex, { true }) { immediate(entries) })
        assertEquals(1, entries.get())
    }

    @Test fun expiryDuringAdmissionFinishesTheTokenWithoutEnteringNative() = runBlocking {
        val clock = AtomicLong(0)
        val entries = AtomicInteger()
        val cleaned = AtomicBoolean(false)
        val result = NativeLiveSpeechBudget(100, clock::get, 5).run(Mutex(), { true }) {
            clock.set(100) // Admission/dispatch consumed the remaining window budget.
            object : NativeLiveSpeechInvocation<String> {
                override fun infer(): String { entries.incrementAndGet(); return "stale" }
                override fun cancel() { }
                override fun finish() { cleaned.set(true) }
            }
        }
        assertEquals(NativeLiveSpeechOutcome.TimedOut, result)
        assertEquals(0, entries.get())
        assertTrue(cleaned.get())
    }

    @Test fun resetOnNativeEntryCannotLoseTheBudgetAbort() = runBlocking {
        val clock = AtomicLong(0)
        val call = ResetEntryCall()
        val work = async(Dispatchers.IO) { NativeLiveSpeechBudget(100, clock::get, 5).run(Mutex(), { true }) { call } }
        try {
            assertTrue(call.beforeReset.await(2, TimeUnit.SECONDS))
            clock.set(100)
            assertTrue("budget monitor did not cancel pre-entry", call.firstCancel.await(2, TimeUnit.SECONDS))
            call.enterNative.countDown()
            assertTrue("a repeated cancel must cover JNI clearing its abort bit", call.cancelAfterReset.await(500, TimeUnit.MILLISECONDS))
            assertEquals(NativeLiveSpeechOutcome.TimedOut, withTimeout(1000) { work.await() })
            assertTrue(call.cancels.get() >= 2)
        } finally { call.enterNative.countDown(); call.returnNative.countDown(); work.cancelAndJoin() }
    }

    @Test fun callerCancellationMonitorSurvivesHeldNativeReturnAndCleanup() = runBlocking {
        val mutex = Mutex()
        val call = HeldCall()
        val freeGate = CountDownLatch(1)
        val work = async(Dispatchers.IO) { NativeLiveSpeechBudget(1000, { 0 }, 5).run(mutex, { true }) { call } }
        var free: kotlinx.coroutines.Job? = null
        try {
            assertTrue(call.entered.await(2, TimeUnit.SECONDS))
            work.cancel()
            assertTrue("caller cancellation must reach native while caller is stopped", call.cancelled.await(500, TimeUnit.MILLISECONDS))
            assertFalse("cancelled caller returned before native did", work.isCompleted)
            free = launch(Dispatchers.IO) { mutex.lock(); try { freeGate.countDown() } finally { mutex.unlock() } }
            assertFalse("free/reload gate released before native return", freeGate.await(100, TimeUnit.MILLISECONDS))
            call.returnNative.countDown()
            assertTrue(call.cleanupEntered.await(2, TimeUnit.SECONDS))
            assertFalse("free/reload gate released before invocation cleanup", freeGate.await(100, TimeUnit.MILLISECONDS))
            call.finishCleanup.countDown()
            work.join(); free.join()
            assertTrue(call.finished.get())
            assertTrue(work.isCancelled)
        } finally {
            call.returnNative.countDown(); call.finishCleanup.countDown()
            work.cancelAndJoin(); free?.cancelAndJoin()
        }
    }

    @Test fun invalidatedGenerationWhileQueuedNeverEntersNative() = runBlocking {
        val mutex = Mutex(locked = true)
        val valid = AtomicBoolean(true)
        val started = CountDownLatch(1)
        val entries = AtomicInteger()
        val work = async(Dispatchers.IO) {
            NativeLiveSpeechBudget(1000, { started.countDown(); 0 }, 5).run(mutex, valid::get) { immediate(entries) }
        }
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS))
            valid.set(false)
            assertEquals(NativeLiveSpeechOutcome.Invalidated, withTimeout(500) { work.await() })
            assertEquals(0, entries.get())
        } finally { mutex.unlock(); work.cancelAndJoin() }
    }

    @Test fun invalidatedActiveGenerationAbortsButFreshRequestStillWorks() = runBlocking {
        val mutex = Mutex()
        val valid = AtomicBoolean(true)
        val call = HeldCall()
        val work = async(Dispatchers.IO) { NativeLiveSpeechBudget(1000, { 0 }, 5).run(mutex, valid::get) { call } }
        try {
            assertTrue(call.entered.await(2, TimeUnit.SECONDS))
            valid.set(false)
            assertTrue(call.cancelled.await(500, TimeUnit.MILLISECONDS))
            call.returnNative.countDown(); call.finishCleanup.countDown()
            assertEquals(NativeLiveSpeechOutcome.Invalidated, work.await())
            val entries = AtomicInteger()
            assertEquals(NativeLiveSpeechOutcome.Completed("ok"),
                NativeLiveSpeechBudget(1000, { 0 }, 5).run(mutex, { true }) { immediate(entries) })
            assertEquals(1, entries.get())
        } finally {
            call.returnNative.countDown(); call.finishCleanup.countDown(); work.cancelAndJoin()
        }
    }

    private fun immediate(entries: AtomicInteger) = object : NativeLiveSpeechInvocation<String> {
        override fun infer(): String { entries.incrementAndGet(); return "ok" }
        override fun cancel() { }
        override fun finish() { }
    }

    private class ResetEntryCall : NativeLiveSpeechInvocation<String> {
        val beforeReset = CountDownLatch(1)
        val enterNative = CountDownLatch(1)
        val firstCancel = CountDownLatch(1)
        val cancelAfterReset = CountDownLatch(1)
        val returnNative = CountDownLatch(1)
        val cancels = AtomicInteger()
        private val reset = AtomicBoolean(false)
        override fun infer(): String {
            beforeReset.countDown()
            check(enterNative.await(3, TimeUnit.SECONDS))
            reset.set(true) // JNI resets cancellation on entry; the first abort was lost.
            check(returnNative.await(2, TimeUnit.SECONDS)) { "no post-entry native abort" }
            return "stale"
        }
        override fun cancel() {
            cancels.incrementAndGet()
            firstCancel.countDown()
            if (reset.get()) { cancelAfterReset.countDown(); returnNative.countDown() }
        }
        override fun finish() { }
    }

    private class HeldCall : NativeLiveSpeechInvocation<String> {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val returnNative = CountDownLatch(1)
        val cleanupEntered = CountDownLatch(1)
        val finishCleanup = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        override fun infer(): String {
            entered.countDown()
            check(returnNative.await(3, TimeUnit.SECONDS))
            return "stale"
        }
        override fun cancel() { cancelled.countDown() }
        override fun finish() {
            cleanupEntered.countDown()
            check(finishCleanup.await(3, TimeUnit.SECONDS))
            finished.set(true)
        }
    }
}
