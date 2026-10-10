package com.mangalens.ui.video

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class BrowserCaptionAuthorityTest {
    private val key = BrowserCaptionAuthorityKey("a".repeat(32), "b".repeat(64), "c".repeat(64))
    private val binding = BrowserCaptionTaskBinding(key, "d".repeat(32), "e".repeat(32))

    @Test fun acceptedLiveOperationAllowsOnlyItsExactTaskAndCapturedSourceConfig() {
        val authority = BrowserCaptionAuthority()
        val operation = authority.begin(key)
        assertTrue(authority.bind(operation, binding.taskId, binding.generation))
        assertTrue(authority.permits(binding))
        assertFalse(authority.permits(binding.copy(generation = "f".repeat(32))))
        assertFalse(authority.permits(binding.copy(taskId = "f".repeat(32))))
        assertFalse(authority.permits(binding.copy(key = key.copy(sourceResolutionId = "f".repeat(32)))))
        assertFalse(authority.permits(binding.copy(key = key.copy(sourceFingerprint = "f".repeat(64)))))
        assertFalse(authority.permits(binding.copy(key = key.copy(configFingerprint = "f".repeat(64)))))
        assertEquals("published", authority.commit(binding) { "published" })
    }

    @Test fun coldProcessCannotAuthorizeAnOtherwiseValidSavedBinding() {
        val previous = BrowserCaptionAuthority()
        val operation = previous.begin(key)
        assertTrue(previous.bind(operation, binding.taskId, binding.generation))
        val cold = BrowserCaptionAuthority()
        assertFalse(cold.permits(binding))
        assertThrows(BrowserCaptionAuthorityRetired::class.java) { cold.commit(binding) { fail("Cold journal was published") } }
        assertFalse(cold.bind(operation, binding.taskId, binding.generation))
    }

    @Test fun revokedOperationCannotBindAResultReturnedByLateScheduling() {
        val authority = BrowserCaptionAuthority()
        val operation = authority.begin(key)
        authority.revoke(operation)
        assertFalse(authority.bind(operation, binding.taskId, binding.generation))
        assertFalse(authority.permits(binding))
    }

    @Test fun sameScopeOldNonceCannotBindCommitOrRevokeTheNewOperation() {
        val authority = BrowserCaptionAuthority()
        val old = authority.begin(key)
        assertTrue(authority.bind(old, binding.taskId, binding.generation))
        val newer = authority.begin(key)
        val replacement = binding.copy(generation = "f".repeat(32))
        assertTrue(authority.bind(newer, replacement.taskId, replacement.generation))
        assertNull(authority.revoke(old))
        assertFalse(authority.bind(old, binding.taskId, binding.generation))
        assertFalse(authority.permits(binding))
        assertThrows(BrowserCaptionAuthorityRetired::class.java) { authority.commit(binding) { fail("Old operation committed") } }
        assertTrue(authority.permits(replacement))
        assertEquals(7, authority.commit(replacement) { 7 })
    }

    @Test fun aBoundOperationCannotSilentlyRebaseToAnotherGeneration() {
        val authority = BrowserCaptionAuthority()
        val operation = authority.begin(key)
        assertTrue(authority.bind(operation, binding.taskId, binding.generation))
        assertFalse(authority.bind(operation, binding.taskId, "f".repeat(32)))
        assertTrue(authority.permits(binding))
    }

    @Test fun aNewSameScopeOperationCannotReviveTheLastTaskGeneration() {
        val authority = BrowserCaptionAuthority()
        val old = authority.begin(key)
        assertTrue(authority.bind(old, binding.taskId, binding.generation))
        authority.revoke(old)
        val newer = authority.begin(key)
        assertFalse(authority.bind(newer, binding.taskId, binding.generation))
        assertFalse(authority.permits(binding))
        assertTrue(authority.bind(newer, binding.taskId, "f".repeat(32)))
    }

    @Test fun retiringBeforeFinalPublicationRejectsPreparedBytesWithoutWaitingForPreparation() {
        // Byte preparation models an IO stage, not publication. Real Store stage tests are separate.
        val authority = BrowserCaptionAuthority()
        val operation = authority.begin(key)
        assertTrue(authority.bind(operation, binding.taskId, binding.generation))
        val bytesPrepared = CountDownLatch(1)
        val releaseFinalCommit = CountDownLatch(1)
        val workerDone = CountDownLatch(1)
        val published = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                val prepared = "real off-Main prepared bytes".toByteArray()
                bytesPrepared.countDown()
                check(releaseFinalCommit.await(5, TimeUnit.SECONDS))
                authority.commit(binding) { check(prepared.isNotEmpty()); published.set(true) }
            } catch (caught: Throwable) { failure.set(caught) }
            finally { workerDone.countDown() }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(bytesPrepared.await(5, TimeUnit.SECONDS))
            assertEquals(binding, authority.revoke(operation))
            assertFalse(authority.permits(binding))
            assertFalse("Retirement published prepared bytes", published.get())
        } finally { releaseFinalCommit.countDown() }
        assertTrue(workerDone.await(5, TimeUnit.SECONDS))
        worker.join(100)
        assertTrue(failure.get() is BrowserCaptionAuthorityRetired)
        assertFalse(published.get())
    }

    @Test fun malformedSourceAndTaskBindingsCannotBecomeAuthority() {
        val authority = BrowserCaptionAuthority()
        assertThrows(IllegalArgumentException::class.java) { authority.begin(key.copy(sourceResolutionId = "page-url")) }
        assertThrows(IllegalArgumentException::class.java) { authority.begin(key.copy(sourceFingerprint = "unknown")) }
        assertThrows(IllegalArgumentException::class.java) { authority.begin(key.copy(configFingerprint = "unknown")) }
        val operation = authority.begin(key)
        assertThrows(IllegalArgumentException::class.java) { authority.bind(operation, "task", binding.generation) }
        assertThrows(IllegalArgumentException::class.java) { authority.bind(operation, binding.taskId, "generation") }
    }
}
