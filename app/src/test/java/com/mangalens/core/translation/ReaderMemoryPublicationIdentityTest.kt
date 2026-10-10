package com.mangalens.core.translation

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual authority primitive; no Android Main/frame or model quality claim. */
class ReaderMemoryPublicationIdentityTest {
    @Test fun theSameReceiptSelectedAgainCannotReuseTheOldPresentationObjectIdentity() {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val task = f.completed(nativeStore = f.store())
            val authority = ReaderMemoryPublicationAuthority()
            val receipt = ReaderTranslationPresentation.receipt(task)
            val first = authority.activate(receipt)
            assertTrue(authority.isCurrent(first))
            val second = authority.activate(receipt)
            assertFalse(authority.isCurrent(first)); assertTrue(authority.isCurrent(second))
            assertNotEquals(first.epoch, second.epoch)
            authority.retire()
            assertFalse(authority.isCurrent(first)); assertFalse(authority.isCurrent(second))
        }
    }

    @Test fun aCapturedIdentityReadDoesNotWaitForTheRealReaderPublicationMonitor() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val task = f.completed(nativeStore = f.store())
            val authority = ReaderMemoryPublicationAuthority()
            val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
            val reached = CountDownLatch(1); val release = CountDownLatch(1)
            val holder = Thread {
                synchronized(authority) { reached.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            }.apply { name = "reader-authority-held-monitor"; start() }
            try {
                assertTrue(reached.await(5, TimeUnit.SECONDS))
                val current = withTimeoutOrNull(400) { withContext(Dispatchers.Default) { authority.isCurrent(selected) } }
                assertEquals("The captured identity read must remain independent of a held publication monitor", true, current)
            } finally { release.countDown(); holder.join(1_000) }
            assertFalse(holder.isAlive)
        }
    }
}
