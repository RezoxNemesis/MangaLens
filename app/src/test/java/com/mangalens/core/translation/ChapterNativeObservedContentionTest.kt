package com.mangalens.core.translation

import com.mangalens.core.compute.NativeComputeAdmission
import com.mangalens.core.compute.checkNativeComputePrecondition
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Actual frozen native journal/source fixture and feature precondition; no JNI or fabricated authority. */
class ChapterNativeObservedContentionTest {
    @Test fun heldCurrentCallbackCannotMakeSameInodeLengthAndTimestampMutationLookUnqueued() = held(changed = true)
    @Test fun unchangedContendedEntryHashesOnceThenIdleEntryStillUsesVerifiedSourceIdentity() = held(changed = false)

    @Test fun aLaterGrantedLiveLeaseDuringAnIdlePredicateStillRequiresActualSourceRejection() = held(changed = true, latePeer = true)

    private fun held(changed: Boolean, latePeer: Boolean = false) = runBlocking {
        ChapterNativeEntryGuardTest.Fixture().use { f ->
            val captured = f.start()
            val hashes = AtomicInteger()
            val guard = ChapterNativeEntryGuard(f.store, captured, captured.pages.single(), { true }) {
                hashes.incrementAndGet(); ChapterTranslationStore.sha256(it)
            }
            val admission = NativeComputeAdmission(pollMs = 5)
            val holder = if (latePeer) null else admission.acquire(NativeComputeAdmission.Priority.LIVE) { true }!!
            val inCurrent = CountDownLatch(1); val releaseCurrent = CountDownLatch(1)
            val first = AtomicBoolean(true); val effect = AtomicBoolean()
            val before = Files.readAttributes(f.source.toPath(), BasicFileAttributes::class.java)
            val waiting = async(Dispatchers.IO) { runCatching { withContext(guard.precondition) {
                val lease = admission.acquire(NativeComputeAdmission.Priority.BACKGROUND) {
                    if (first.compareAndSet(true, false)) {
                        inCurrent.countDown(); check(releaseCurrent.await(2, TimeUnit.SECONDS))
                    }
                    true
                }!!
                try { checkNativeComputePrecondition(lease.waited); effect.set(true) }
                finally { lease.close() }
            } } }
            try {
                assertTrue(inCurrent.await(2, TimeUnit.SECONDS))
                assertEquals("Registration and held callbacks must not hash on queue polls", 0, hashes.get())
                fun mutateSourceIfRequested() { if (changed) {
                    f.source.writeText("replaced source bytes")
                    Files.setLastModifiedTime(f.source.toPath(), before.lastModifiedTime())
                    val after = Files.readAttributes(f.source.toPath(), BasicFileAttributes::class.java)
                    assertEquals(before.fileKey(), after.fileKey()); assertEquals(before.size(), after.size())
                    assertEquals(before.lastModifiedTime(), after.lastModifiedTime())
                } }
                if (latePeer) {
                    val live = admission.acquire(NativeComputeAdmission.Priority.LIVE) { true }!!
                    try { mutateSourceIfRequested() } finally { live.close() }
                } else { mutateSourceIfRequested(); holder!!.close() }
                releaseCurrent.countDown()
                val outcome = withTimeout(2_000) { waiting.await() }
                if (changed) {
                    assertTrue("Stale queued work did not reject its captured authority", outcome.isFailure)
                    assertFalse("A source mutation before native entry must have no effect", effect.get())
                    assertTrue(outcome.exceptionOrNull()?.message.orEmpty().contains("Source page changed"))
                } else {
                    assertTrue(outcome.isSuccess); assertTrue(effect.get())
                }
                assertEquals("Observed contention requires exactly one final feature-owned SHA", 1, hashes.get())
                if (!changed) {
                    effect.set(false)
                    withContext(guard.precondition) {
                        val lease = admission.acquire(NativeComputeAdmission.Priority.BACKGROUND) { true }!!
                        try { assertFalse(lease.waited); checkNativeComputePrecondition(lease.waited); effect.set(true) }
                        finally { lease.close() }
                    }
                    assertTrue(effect.get())
                    assertEquals("The unchanged unqueued call adds zero hashes", 1, hashes.get())
                }
            } finally { holder?.close(); releaseCurrent.countDown(); waiting.cancelAndJoin() }
        }
    }
}
