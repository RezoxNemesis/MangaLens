package com.mangalens.ui.video

import com.mangalens.core.compute.NativeComputeAdmission
import com.mangalens.core.compute.NativeComputePrecondition
import com.mangalens.core.compute.checkNativeComputePrecondition
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SubtitleNativeAdmissionLifetimeTest {
    @Test fun cancelledProviderCallerCannotStartQueuedNativeRefinementAfterItsResourceBarrierWasDetached() = runBlocking {
        val admission = NativeComputeAdmission(pollMs = 5)
        val holder = admission.acquire(NativeComputeAdmission.Priority.LIVE) { true }!!
        val released = CompletableDeferred<Unit>()
        val queued = CountDownLatch(1)
        val entries = AtomicInteger()
        val provider = SubtitleProviderLifetime { released.complete(Unit) }
        val work = async(Dispatchers.IO) {
            val owner = currentCoroutineContext()
            withContext(NativeComputePrecondition { owner.ensureActive() }) {
                provider.run {
                    admission.withLease(NativeComputeAdmission.Priority.BACKGROUND, { queued.countDown(); true }) {
                        checkNativeComputePrecondition(waited = true)
                        entries.incrementAndGet()
                    }
                }
            }
        }
        try {
            assertTrue(queued.await(2, TimeUnit.SECONDS))
            work.cancelAndJoin()
            provider.close()
            assertFalse("A queued provider must retain resources until its control frame has returned", released.isCompleted)
            holder.close()
            withTimeout(1000) { released.await() }
            assertEquals("Detached provider cleanup lost the captured worker admission fence", 0, entries.get())
        } finally { holder.close(); work.cancelAndJoin(); provider.close() }
    }
}
