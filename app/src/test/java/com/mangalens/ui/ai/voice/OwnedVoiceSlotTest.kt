package com.mangalens.ui.ai.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Authored UNRUN: cancellation does not prove resource release. */
class OwnedVoiceSlotTest {
    @Test fun recreatedRequesterCannotBypassActualOneProducerCapacity() {
        val process = OwnedVoiceSlot()
        val original = requireNotNull(process.acquire())
        process.retire(original.token)
        assertFalse(original.requesterAttached)
        assertTrue(original.retired)
        assertNull(process.acquire())
        assertTrue(process.releaseAfterCleanup(original, proven = true))
        assertNotNull(process.acquire())
    }
    @Test fun failedActualCloseRetainsSlotAndDetachedRequester() {
        val process = OwnedVoiceSlot()
        val owner = requireNotNull(process.acquire())
        process.retire(owner.token)
        assertFalse(process.releaseAfterCleanup(owner, proven = false))
        assertFalse(owner.requesterAttached)
        assertNull(process.acquire())
    }
    @Test fun oldTokenCannotStopOrReleaseNewOwner() {
        val process = OwnedVoiceSlot()
        val old = requireNotNull(process.acquire())
        assertTrue(process.releaseAfterCleanup(old, true))
        val next = requireNotNull(process.acquire())
        process.retire(old.token)
        assertFalse(next.retired)
        assertFalse(process.releaseAfterCleanup(old, true))
        assertNull(process.acquire())
    }
    @Test fun stopKeepsRequestAttachedForTranscriptionButRetirementDetachesFirst() {
        val process = OwnedVoiceSlot()
        val owner = requireNotNull(process.acquire())
        process.requestStop(owner.token)
        assertTrue(owner.stopRequested)
        assertTrue(owner.requesterAttached)
        assertFalse(owner.retired)
        process.retire(owner.token)
        assertFalse(owner.requesterAttached)
        assertTrue(owner.retired)
    }
    @Test fun concurrentClicksAdmitExactlyOneWithoutAQueue() {
        val process = OwnedVoiceSlot()
        val ready = CountDownLatch(8)
        val go = CountDownLatch(1)
        val finished = CountDownLatch(8)
        val admitted = AtomicInteger()
        val threads = List(8) {
            Thread {
                ready.countDown(); go.await()
                if (process.acquire() != null) admitted.incrementAndGet()
                finished.countDown()
            }.apply { start() }
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS)); go.countDown()
        assertTrue(finished.await(2, TimeUnit.SECONDS))
        threads.forEach { it.join() }
        assertEquals(1, admitted.get())
    }
}
