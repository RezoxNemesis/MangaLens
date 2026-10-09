package com.mangalens.core.compute

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class NativeComputeAdmissionTest {
    @Test fun theSixtyFifthQueuedInvocationIsRejectedWithoutDroppingAnyAcceptedWaiter() = runBlocking {
        val admission = NativeComputeAdmission(pollMs = 5)
        val holder = admission.acquire(NativeComputeAdmission.Priority.LIVE) { true }!!
        val entered = AtomicBoolean()
        val waiters = (1..64).map {
            async(start = CoroutineStart.UNDISPATCHED) {
                admission.withLease(NativeComputeAdmission.Priority.BACKGROUND, { true }) { entered.set(true) }
            }
        }
        try {
            try {
                admission.withLease(NativeComputeAdmission.Priority.LIVE, { true }) { entered.set(true) }
                fail("The sixty-fifth waiting invocation must be rejected")
            } catch (expected: IllegalStateException) { assertTrue(expected.message.orEmpty().contains("full")) }
            assertFalse(entered.get())
            waiters.forEach { it.cancelAndJoin() }
            holder.close()
            assertNotNull(admission.acquire(NativeComputeAdmission.Priority.LIVE) { true }.also { it?.close() })
        } finally { waiters.forEach { it.cancelAndJoin() }; holder.close() }
    }

    @Test fun delayedBackgroundDoesNotBlockLiveAndEventuallyGetsItsLease() = runBlocking {
        val now = AtomicLong()
        val governor = ResourceGovernor(clockMs = now::get, backgroundRestMs = 20)
        governor.update(ResourceSignals(memory = MemoryPressure.LOW))
        val admission = NativeComputeAdmission(pollMs = 5, governor = governor)
        val holder = admission.acquire(NativeComputeAdmission.Priority.LIVE, ResourceWorkKind.CLEANUP) { true }!!
        val backgroundQueued = CountDownLatch(1)
        val backgroundEntered = CountDownLatch(1)
        val liveEntered = CountDownLatch(1)
        val background = async(Dispatchers.IO) {
            admission.withLease(NativeComputeAdmission.Priority.BACKGROUND, { backgroundQueued.countDown(); true }) {
                backgroundEntered.countDown()
            }
        }
        val live = async(Dispatchers.IO) {
            admission.withLease(NativeComputeAdmission.Priority.LIVE, { true }) { liveEntered.countDown() }
        }
        try {
            assertTrue(backgroundQueued.await(2, TimeUnit.SECONDS))
            holder.close()
            assertTrue(liveEntered.await(2, TimeUnit.SECONDS))
            assertEquals(1, backgroundEntered.count)
            now.set(20)
            withTimeout(2000) { live.await(); background.await() }
            assertEquals(0, backgroundEntered.count)
        } finally { holder.close(); live.cancelAndJoin(); background.cancelAndJoin() }
    }

    @Test fun criticalPressureRejectsComputeWithoutEffectButCleanupStillSerializesUntilNativeReturn() = runBlocking {
        val governor = ResourceGovernor()
        val admission = NativeComputeAdmission(pollMs = 5, governor = governor)
        val active = admission.acquire(NativeComputeAdmission.Priority.LIVE) { true }!!
        governor.update(ResourceSignals(thermal = ThermalPressure.CRITICAL))
        val entered = AtomicBoolean()
        try {
            admission.withLease(NativeComputeAdmission.Priority.INTERACTIVE, { true }) { entered.set(true) }
            fail("Critical device pressure must defer before an effect")
        } catch (_: ResourcePausedException) { }
        assertFalse(entered.get())
        val cleanupQueued = CountDownLatch(1)
        val cleanupEntered = CountDownLatch(1)
        val cleanup = async(Dispatchers.IO) {
            val lease = admission.acquire(NativeComputeAdmission.Priority.BACKGROUND, ResourceWorkKind.CLEANUP) {
                cleanupQueued.countDown(); true
            }!!
            try { cleanupEntered.countDown() } finally { lease.close() }
        }
        try {
            assertTrue(cleanupQueued.await(2, TimeUnit.SECONDS))
            assertFalse(cleanupEntered.await(100, TimeUnit.MILLISECONDS))
            active.close()
            withTimeout(2000) { cleanup.await() }
            assertEquals(0, cleanupEntered.count)
        } finally { active.close(); cleanup.cancelAndJoin() }
    }

    @Test fun oldBackgroundWaiterGetsAProgressTurnBeforeNewInteractiveButNeverBeforeLive() = runBlocking {
        val now = AtomicLong()
        val governor = ResourceGovernor(clockMs = now::get)
        val admission = NativeComputeAdmission(pollMs = 5, governor = governor, foregroundFairnessMs = 100)
        val holder = admission.acquire(NativeComputeAdmission.Priority.LIVE) { true }!!
        val order = ConcurrentLinkedQueue<String>()
        val queued = CountDownLatch(1)
        val background = async(Dispatchers.IO) {
            admission.withLease(NativeComputeAdmission.Priority.BACKGROUND, { queued.countDown(); true }) { order.add("background") }
        }
        assertTrue(queued.await(2, TimeUnit.SECONDS))
        now.set(100)
        val foregroundQueued = CountDownLatch(2)
        val interactive = async(Dispatchers.IO) {
            admission.withLease(NativeComputeAdmission.Priority.INTERACTIVE, { foregroundQueued.countDown(); true }) { order.add("interactive") }
        }
        val live = async(Dispatchers.IO) {
            admission.withLease(NativeComputeAdmission.Priority.LIVE, { foregroundQueued.countDown(); true }) { order.add("live") }
        }
        try {
            assertTrue(foregroundQueued.await(2, TimeUnit.SECONDS))
            holder.close()
            withTimeout(2000) { live.await(); interactive.await(); background.await() }
            assertEquals(listOf("live", "background", "interactive"), order.toList())
        } finally { holder.close(); live.cancelAndJoin(); interactive.cancelAndJoin(); background.cancelAndJoin() }
    }

    @Test fun theConfiguredQueueCannotExceedTheSixtyFourWaiterSafetyLimit() {
        try {
            NativeComputeAdmission(maxWaiting = 65)
            fail("A configuration override must not create an unbounded native waiter queue")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("64"))
        }
    }

    @Test fun waitingLiveRunsBeforeEarlierOfflineAtTheCompletedWindowBoundary() = runBlocking {
        val admission = NativeComputeAdmission(pollMs = 5)
        val holder = admission.acquire(NativeComputeAdmission.Priority.BACKGROUND) { true }!!
        val offlineWaiting = CountDownLatch(1)
        val liveWaiting = CountDownLatch(1)
        val order = ConcurrentLinkedQueue<String>()
        val offline = async(Dispatchers.IO) {
            admission.withLease(NativeComputeAdmission.Priority.BACKGROUND, { offlineWaiting.countDown(); true }) { order.add("offline") }
        }
        var live: kotlinx.coroutines.Deferred<Boolean?>? = null
        try {
            assertTrue(offlineWaiting.await(2, TimeUnit.SECONDS))
            live = async(Dispatchers.IO) {
                admission.withLease(NativeComputeAdmission.Priority.LIVE, { liveWaiting.countDown(); true }) { order.add("live") }
            }
            assertTrue(liveWaiting.await(2, TimeUnit.SECONDS))
            assertTrue("Priority must not evict the active native window", order.isEmpty())
            holder.close()
            withTimeout(1000) { live.await(); offline.await() }
            assertEquals(listOf("live", "offline"), order.toList())
        } finally { holder.close(); offline.cancelAndJoin(); live?.cancelAndJoin() }
    }

    @Test fun cancelledWaiterDoesNoWorkAndDoesNotReleaseTheActiveNativeLease() = runBlocking {
        val admission = NativeComputeAdmission(pollMs = 5)
        val holder = admission.acquire(NativeComputeAdmission.Priority.BACKGROUND) { true }!!
        val queued = CountDownLatch(1)
        val entered = AtomicBoolean()
        val waiting = async(Dispatchers.IO) {
            admission.withLease(NativeComputeAdmission.Priority.LIVE, { queued.countDown(); true }) { entered.set(true) }
        }
        try {
            assertTrue(queued.await(2, TimeUnit.SECONDS))
            waiting.cancelAndJoin()
            assertFalse(entered.get())
            val nextEntered = CountDownLatch(1)
            val next = async(Dispatchers.IO) {
                admission.withLease(NativeComputeAdmission.Priority.LIVE, { true }) { nextEntered.countDown() }
            }
            try {
                assertFalse(nextEntered.await(100, TimeUnit.MILLISECONDS))
                holder.close()
                withTimeout(1000) { next.await() }
                assertEquals(0, nextEntered.count)
            } finally { holder.close(); next.cancelAndJoin() }
        } finally { holder.close(); waiting.cancelAndJoin() }
    }

    @Test fun staleClosedLeaseCannotReleaseTheReplacementInvocation() = runBlocking {
        val admission = NativeComputeAdmission(pollMs = 5)
        val old = admission.acquire(NativeComputeAdmission.Priority.BACKGROUND) { true }!!
        old.close()
        val replacement = admission.acquire(NativeComputeAdmission.Priority.LIVE) { true }!!
        old.close()
        val entered = CountDownLatch(1)
        val peer = async(Dispatchers.IO) { admission.withLease(NativeComputeAdmission.Priority.BACKGROUND, { true }) { entered.countDown() } }
        try {
            assertFalse(entered.await(100, TimeUnit.MILLISECONDS))
            replacement.close()
            withTimeout(1000) { peer.await() }
            assertEquals(0, entered.count)
        } finally { replacement.close(); peer.cancelAndJoin() }
    }
}
