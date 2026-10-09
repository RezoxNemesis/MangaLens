package com.mangalens.core.compute

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class NativeCleanupOverflowTest {
    @Test fun acceptedCloseKeepsItsOwnerAcrossAllSixtyFourWaitingFeatures() = runBlocking {
        supervisorScope {
            val admission = NativeComputeAdmission(pollMs = 25, maxWaiting = 64)
            val active = admission.acquire(NativeComputeAdmission.Priority.LIVE) { true }!!
            val queued = CountDownLatch(64)
            val features = List(64) {
                val first = AtomicBoolean(true)
                async(Dispatchers.IO) {
                    val lease = admission.acquire(NativeComputeAdmission.Priority.BACKGROUND) {
                        if (first.getAndSet(false)) queued.countDown()
                        true
                    }!!
                    lease.close()
                }
            }
            val ownsNativeHandle = AtomicBoolean(true)
            val freed = AtomicInteger()
            val cleanupQueued = CountDownLatch(1)
            var cleanup: kotlinx.coroutines.Deferred<Unit>? = null
            try {
                assertTrue("Did not actually fill the feature queue", queued.await(3, TimeUnit.SECONDS))
                cleanup = async(start = CoroutineStart.UNDISPATCHED) {
                    val lease = admission.acquire(NativeComputeAdmission.Priority.BACKGROUND, ResourceWorkKind.CLEANUP) {
                        cleanupQueued.countDown(); true
                    }!!
                    try {
                        assertTrue(ownsNativeHandle.compareAndSet(true, false))
                        freed.incrementAndGet()
                    } finally { lease.close() }
                }
                assertFalse("Accepted native close was dropped when all64 feature slots were occupied", cleanup.isCompleted)
                assertTrue(ownsNativeHandle.get())
                assertEquals(0, freed.get())
                features.first().cancelAndJoin()
                // A newly arriving feature cannot steal capacity reserved for an accepted close.
                try {
                    admission.acquire(NativeComputeAdmission.Priority.LIVE) { true }
                    fail("Future feature stole the close's reserved queue capacity")
                } catch (full: IllegalStateException) {
                    assertEquals("Native compute queue is full. Retry when local work finishes.", full.message)
                }
                assertTrue("Cleanup was not queued after capacity became free", cleanupQueued.await(3, TimeUnit.SECONDS))
                assertTrue("An active peer's lease must retain native ownership", ownsNativeHandle.get())
                assertEquals(0, freed.get())
                active.close()
                withTimeout(3000) { cleanup.await() }
                assertFalse(ownsNativeHandle.get())
                assertEquals("The same owner must be freed exactly once", 1, freed.get())
            } finally {
                active.close(); cleanup?.cancelAndJoin()
                features.forEach { it.cancelAndJoin() }
            }
        }
    }

    @Test fun cancelledCapacityWaitDoesNotBlockLaterFeatureAdmission() = runBlocking {
        supervisorScope {
            val admission = NativeComputeAdmission(pollMs = 25, maxWaiting = 64)
            val active = admission.acquire(NativeComputeAdmission.Priority.LIVE) { true }!!
            val queued = CountDownLatch(64)
            val features = List(64) {
                val first = AtomicBoolean(true)
                async(Dispatchers.IO) {
                    val lease = admission.acquire(NativeComputeAdmission.Priority.BACKGROUND) {
                        if (first.getAndSet(false)) queued.countDown()
                        true
                    }!!
                    lease.close()
                }
            }
            var cleanup: kotlinx.coroutines.Deferred<NativeComputeAdmission.Lease?>? = null
            var replacement: kotlinx.coroutines.Deferred<Unit>? = null
            try {
                assertTrue(queued.await(3, TimeUnit.SECONDS))
                cleanup = async(start = CoroutineStart.UNDISPATCHED) {
                    admission.acquire(NativeComputeAdmission.Priority.BACKGROUND, ResourceWorkKind.CLEANUP) { true }
                }
                assertFalse("Cleanup capacity wait failed instead of retaining its caller", cleanup.isCompleted)
                cleanup.cancelAndJoin()
                features.first().cancelAndJoin()
                val admitted = CountDownLatch(1)
                replacement = async(start = CoroutineStart.UNDISPATCHED) {
                    val lease = admission.acquire(NativeComputeAdmission.Priority.LIVE) {
                        admitted.countDown(); true
                    }!!
                    lease.close()
                }
                assertTrue("Cancelled cleanup retained a capacity reservation", admitted.await(3, TimeUnit.SECONDS))
                active.close()
                withTimeout(3000) { replacement.await() }
            } finally {
                active.close(); cleanup?.cancelAndJoin(); replacement?.cancelAndJoin()
                features.forEach { it.cancelAndJoin() }
            }
        }
    }
}
