package com.mangalens.ui.ai.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Authored UNRUN; actual Android provider acceptance remains separate. */
class OwnedSpeechOutputCloseTest {
    @Test fun failureOfStopStillAttemptsActualShutdownOnce() {
        var stops = 0; var shutdowns = 0
        val close = OwnedSpeechOutputClose({ stops++; error("stop") }, { shutdowns++ })
        assertFalse(close.close()); assertFalse(close.close())
        assertEquals(1, stops); assertEquals(1, shutdowns)
    }
    @Test fun actualShutdownFailureKeepsTheDetachedCapacityOwner() {
        val slot = OwnedVoiceSlot(); val owner = requireNotNull(slot.acquire())
        slot.retire(owner.token)
        val close = OwnedSpeechOutputClose({}, { error("shutdown") })
        assertFalse(slot.releaseAfterCleanup(owner, close.close()))
        assertNull(slot.acquire()); assertFalse(owner.requesterAttached)
    }
    @Test fun stuckActualShutdownKeepsCapacityUntilActualReturn() {
        val slot = OwnedVoiceSlot(); val owner = requireNotNull(slot.acquire())
        val entered = CountDownLatch(1); val returned = CountDownLatch(1)
        val close = OwnedSpeechOutputClose({}, { entered.countDown(); returned.await() })
        val worker = Thread { slot.releaseAfterCleanup(owner, close.close()) }.apply { start() }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        slot.retire(owner.token); assertNull(slot.acquire())
        returned.countDown(); worker.join(2_000); assertFalse(worker.isAlive)
        assertNotNull(slot.acquire())
    }
}
