package com.mangalens.ui.ai.voice

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

/** Authored UNRUN. The same one-close holder is used by the Android AudioRecord adapter. */
class OwnedVoiceAudioTest {
    private class Port : VoiceAudioPort {
        override val initialized = true
        var stops = 0; var releases = 0
        var releaseFails = false
        var stopFails = false
        var readEntered: CountDownLatch? = null
        var returnRead: CountDownLatch? = null
        override fun start() { }
        override fun read(samples: ShortArray, count: Int): Int {
            readEntered?.countDown(); returnRead?.await(); return 0
        }
        override fun stop() { stops++; if (stopFails) error("stop failed") }
        override fun release() { releases++; if (releaseFails) error("release failed") }
    }
    @Test fun heldActualReadRefusesConcurrentReleaseAndCloseRunsOnceAfterReturn() {
        val port = Port().apply { readEntered = CountDownLatch(1); returnRead = CountDownLatch(1) }
        val owner = OwnedVoiceAudio(port); owner.start()
        val thread = Thread { owner.read(ShortArray(4), 4) }.apply { start() }
        assertTrue(port.readEntered!!.await(2, TimeUnit.SECONDS))
        try { owner.releaseAfterRead(); fail("A still-owned read must prevent release") } catch (_: IllegalStateException) { }
        assertEquals(0, port.releases)
        port.returnRead!!.countDown(); thread.join(2_000); assertFalse(thread.isAlive)
        assertTrue(owner.releaseAfterRead()); assertTrue(owner.releaseAfterRead())
        assertEquals(1, port.stops); assertEquals(1, port.releases)
    }
    @Test fun initializationFailureStillReleasesWithoutInvalidStop() {
        val port = Port(); val owner = OwnedVoiceAudio(port)
        assertTrue(owner.releaseAfterRead()); assertEquals(0, port.stops); assertEquals(1, port.releases)
    }
    @Test fun actualReleaseFailureDoesNotRetryOrPretendOwnershipEnded() {
        val port = Port().apply { releaseFails = true }; val owner = OwnedVoiceAudio(port); owner.start()
        assertFalse(owner.releaseAfterRead()); assertFalse(owner.releaseAfterRead())
        assertEquals(1, port.releases); assertEquals(1, port.stops)
    }
    @Test fun stopFailureStillAttemptsTheOneActualReleaseAndRetainsFailure() {
        val port = Port().apply { stopFails = true }; val owner = OwnedVoiceAudio(port); owner.start()
        assertFalse(owner.releaseAfterRead()); assertFalse(owner.releaseAfterRead())
        assertEquals(1, port.stops); assertEquals(1, port.releases)
    }
}
