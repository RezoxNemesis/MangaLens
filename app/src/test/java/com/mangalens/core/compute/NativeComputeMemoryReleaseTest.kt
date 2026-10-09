package com.mangalens.core.compute

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class NativeComputeMemoryReleaseTest {
    @Test fun releaseWaitsForActivePeerAndRetainsLaneUntilActualCloseReturns(): Unit = runBlocking {
        val admission = NativeComputeAdmission(pollMs = 5)
        val peer = admission.acquire(NativeComputeAdmission.Priority.BACKGROUND) { true }!!
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val executor = NativeComputeMemoryRelease(admission, scope)
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val nextEntered = CountDownLatch(1)
        var next: kotlinx.coroutines.Deferred<Unit?>? = null
        try {
            executor.schedule {
                entered.countDown()
                check(finish.await(2, TimeUnit.SECONDS))
                returned.countDown()
            }
            assertFalse("Memory release overlapped an active speech window", entered.await(100, TimeUnit.MILLISECONDS))
            peer.close()
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            next = async(Dispatchers.IO) {
                admission.withLease(NativeComputeAdmission.Priority.LIVE, { true }) { nextEntered.countDown() }
            }
            assertFalse("The compute lane was released before native close returned", nextEntered.await(100, TimeUnit.MILLISECONDS))
            finish.countDown()
            assertTrue(returned.await(1, TimeUnit.SECONDS))
            withTimeout(1000) { next.await() }
        } finally { peer.close(); finish.countDown(); next?.cancelAndJoin(); scope.cancel() }
    }

    @Test fun completeCumulativeCallbacksHaveOneBoundedPendingSlotDuringActualRelease(): Unit = runBlocking {
        val admission = NativeComputeAdmission(pollMs = 5)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val executor = NativeComputeMemoryRelease(admission, scope)
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val lastReturned = CountDownLatch(1)
        val staleCallbacks = AtomicInteger()
        try {
            executor.schedule { entered.countDown(); check(finish.await(2, TimeUnit.SECONDS)) }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            repeat(100) { executor.schedule { staleCallbacks.incrementAndGet() } }
            executor.schedule { lastReturned.countDown() }
            finish.countDown()
            assertTrue(lastReturned.await(1, TimeUnit.SECONDS))
            assertEquals("A complete cumulative snapshot needs only the most recent pending drain", 0, staleCallbacks.get())
        } finally { finish.countDown(); scope.cancel() }
    }

    @Test fun failedCleanupReportsTheSameCauseAndNextReleaseCanStillUseTheLane(): Unit = runBlocking {
        val admission = NativeComputeAdmission(pollMs = 5)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val reported = CountDownLatch(1)
        val error = IllegalStateException("release failed")
        var cause: Throwable? = null
        val executor = NativeComputeMemoryRelease(admission, scope) { cause = it; reported.countDown() }
        val closed = CountDownLatch(1)
        try {
            executor.schedule { throw error }
            assertTrue(reported.await(1, TimeUnit.SECONDS))
            executor.schedule { closed.countDown() }
            assertTrue(closed.await(1, TimeUnit.SECONDS))
            assertSame(error, cause)
        } finally { scope.cancel() }
    }
}
