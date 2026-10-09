package com.mangalens.ui.video

import com.mangalens.core.compute.NativeComputeAdmission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class LiveSpeechWindowSnapshotTest {
    private val ready = LiveSpeechControlSnapshot(false, true, true, 1L, 1L, "en")

    @Test fun modelLoadingDisabledAndClosedControlsRefuseNewWindows() {
        val controls = AtomicReference(ready.copy(ready = false))
        val clockReads = AtomicInteger()
        val snapshots = LiveSpeechWindowSnapshots(Any(), { clockReads.incrementAndGet().toLong() }, controls::get)
        assertNull(snapshots.capture())
        controls.set(ready.copy(enabled = false)); assertNull(snapshots.capture())
        controls.set(ready.copy(closed = true)); assertNull(snapshots.capture())
        assertEquals("Refused audio must not start a live request", 0, clockReads.get())
        controls.set(ready)
        assertEquals(LiveSpeechWindowSnapshot(1, 1, "en", 1), snapshots.capture())
    }

    @Test fun capturedLanguageNeverAdoptsLaterControlOrDetectionSelections() {
        val controls = AtomicReference(ready)
        val snapshots = LiveSpeechWindowSnapshots(Any(), { 17L }, controls::get)
        val captured = snapshots.capture()!!
        controls.set(ready.copy(sourceLanguage = "hi"))
        assertEquals("en", captured.inferenceLanguage("ja"))
        assertFalse("A changed source-language control must retire its queued request", snapshots.isCurrent(captured))
        controls.set(ready.copy(sourceLanguage = "auto"))
        val automatic = snapshots.capture()!!
        assertEquals("auto", automatic.inferenceLanguage(null))
        assertEquals("hi", automatic.inferenceLanguage("hi"))
    }

    @Test fun expiredPcmChannelWindowNeverEntersNativeEvenWithFreeAdmission() = runBlocking {
        val clock = AtomicLong()
        val snapshots = LiveSpeechWindowSnapshots(Any(), clock::get) { ready }
        val captured = snapshots.capture()!!
        clock.set(20_000L) // Real channel delay before its consumer starts.
        val entries = AtomicInteger()
        val outcome = withContext(NativeLiveSpeechEnqueuedAt(captured.enqueuedAtMs)) {
            NativeLiveSpeechBudget(20_000L, clock::get, 5).run(Mutex(), { snapshots.isCurrent(captured) },
                NativeComputeAdmission(pollMs = 5)) { immediate(entries) }
        }
        assertEquals(NativeLiveSpeechOutcome.TimedOut, outcome)
        assertEquals("Expired queued PCM must not create a native invocation", 0, entries.get())
    }

    @Test fun pcmChannelAndEngineQueueShareTheOriginalTwentySecondDeadline() = runBlocking {
        val clock = AtomicLong()
        val snapshots = LiveSpeechWindowSnapshots(Any(), clock::get) { ready }
        val captured = snapshots.capture()!!
        clock.set(19_950L)
        val mutex = Mutex(locked = true)
        val consumerWaiting = CountDownLatch(1)
        val entries = AtomicInteger()
        val work = async(Dispatchers.IO + NativeLiveSpeechEnqueuedAt(captured.enqueuedAtMs)) {
            NativeLiveSpeechBudget(20_000L, { consumerWaiting.countDown(); clock.get() }, 5)
                .run(mutex, { snapshots.isCurrent(captured) }, NativeComputeAdmission(pollMs = 5)) { immediate(entries) }
        }
        try {
            assertTrue(consumerWaiting.await(2, TimeUnit.SECONDS))
            clock.set(20_000L)
            assertEquals(NativeLiveSpeechOutcome.TimedOut, withTimeout(1000) { work.await() })
            assertEquals(0, entries.get())
        } finally { mutex.unlock(); work.cancelAndJoin() }
    }

    @Test fun replacementModelRetiresOnlyItsOldQueuedWindowAndAdmitsNewEpoch() = runBlocking {
        val controls = AtomicReference(ready)
        val clock = AtomicLong()
        val snapshots = LiveSpeechWindowSnapshots(Any(), clock::get, controls::get)
        val captured = snapshots.capture()!!
        val mutex = Mutex(locked = true)
        val consumerWaiting = CountDownLatch(1)
        val entries = AtomicInteger()
        val work = async(Dispatchers.IO + NativeLiveSpeechEnqueuedAt(captured.enqueuedAtMs)) {
            NativeLiveSpeechBudget(20_000L, { consumerWaiting.countDown(); clock.get() }, 5)
                .run(mutex, { snapshots.isCurrent(captured) }, NativeComputeAdmission(pollMs = 5)) { immediate(entries) }
        }
        try {
            assertTrue(consumerWaiting.await(2, TimeUnit.SECONDS))
            // An allocator may recycle a handle address; model epoch must still change.
            controls.set(ready.copy(modelEpoch = 2L))
            mutex.unlock()
            assertEquals(NativeLiveSpeechOutcome.Invalidated, withTimeout(1000) { work.await() })
            assertEquals(0, entries.get())
            val replacement = snapshots.capture()!!
            assertEquals(NativeLiveSpeechOutcome.Completed("real invocation"),
                NativeLiveSpeechBudget(20_000L, clock::get, 5).run(mutex, { snapshots.isCurrent(replacement) },
                    NativeComputeAdmission(pollMs = 5)) { immediate(entries) })
            assertEquals(1, entries.get())
        } finally { if (mutex.isLocked) mutex.unlock(); work.cancelAndJoin() }
    }

    private fun immediate(entries: AtomicInteger) = object : NativeLiveSpeechInvocation<String> {
        override fun infer(): String { entries.incrementAndGet(); return "real invocation" }
        override fun cancel() { fail("A queued refusal must never cancel a different active invocation") }
        override fun finish() { }
    }
}
