package com.mangalens.core.compute

import kotlinx.coroutines.Dispatchers
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

class NativeComputeAdmissionTest {
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
