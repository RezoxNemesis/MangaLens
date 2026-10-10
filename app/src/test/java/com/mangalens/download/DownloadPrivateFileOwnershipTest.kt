package com.mangalens.download

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** These real temporary-file/concurrency controls are authored and UNRUN. */
class DownloadPrivateFileOwnershipTest {
    @Test fun actualOwnerReceiptSurvivesCallerRetirementUntilLeaseReturns() {
        val root = Files.createTempDirectory("download-owner").toFile()
        try {
            val owner = DownloadPrivateFileOwner(root, "same-id")
            val lease = DownloadPrivateFileOwners.acquire(owner)
            assertTrue(DownloadPrivateFileOwners.hasOwners(owner))
            // Neither terminal scheduler status nor cancellation can erase this receipt.
            assertFalse(DownloadPrivateFileOwners.hasOwners(owner.copy(id = "other-id")))
            lease.close(); lease.close()
            assertFalse(DownloadPrivateFileOwners.hasOwners(owner))
        } finally { root.deleteRecursively() }
    }

    @Test fun unsafeCloseRetainsReceiptAcrossARegistryRecreation() {
        val root = Files.createTempDirectory("download-orphan").toFile()
        try {
            val owner = DownloadPrivateFileOwner(root, "owned-id")
            val lease = DownloadPrivateFileOwners.acquire(owner)
            lease.retain(); lease.close()
            assertTrue(DownloadPrivateFileOwners.hasOwners(owner))
            assertTrue(DownloadPrivateFileOwners.ownershipFailure(owner) is DownloadUnprovenFileOwnershipException)
            assertTrue(root.resolve(".owners").listFiles()!!.any { it.name.startsWith("owned-id.owner-") })
        } finally { root.deleteRecursively() }
    }

    @Test fun queuedMemoryReservationProtectsTheIdBeforeAnyPrivateFileIoBegins() {
        val root = Files.createTempDirectory("queued-file-owner").toFile()
        try {
            val owner = DownloadPrivateFileOwner(root, "queued-id")
            val reservation = DownloadPrivateFileOwners.reserve(owner)
            assertFalse(root.resolve(".owners").exists())
            assertTrue(DownloadPrivateFileOwners.hasOwners(owner))
            assertTrue(DownloadPrivateFileOwners.ownershipFailure(owner) is DownloadFilesBusyException)
            reservation.close(); reservation.close()
            assertFalse(DownloadPrivateFileOwners.hasOwners(owner))
        } finally { root.deleteRecursively() }
    }

    @Test(timeout = 5_000) fun perIdMutationSerializesDeletionAndReenqueueButNotOtherIds() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var sameEntered = false; var otherEntered = false
        val deletion = launch { DownloadIdMutationFences.withId("shared") { entered.complete(Unit); release.await() } }
        entered.await()
        val reenqueue = launch { DownloadIdMutationFences.withId("shared") { sameEntered = true } }
        val other = launch { DownloadIdMutationFences.withId("different") { otherEntered = true } }
        other.join(); assertTrue(otherEntered); assertFalse(sameEntered)
        release.complete(Unit); deletion.join(); reenqueue.join(); assertTrue(sameEntered)
    }

    @Test(timeout = 5_000) fun cancelledQueuedMutationCannotReleaseItsPeersFence() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val holder = launch { DownloadIdMutationFences.withId("fenced") { entered.complete(Unit); release.await() } }
        entered.await()
        val cancelled = launch { DownloadIdMutationFences.withId("fenced") { fail("Cancelled waiter entered") } }
        yield(); cancelled.cancelAndJoin()
        var thirdEntered = false
        val third = launch { DownloadIdMutationFences.withId("fenced") { thirdEntered = true } }
        yield(); assertFalse(thirdEntered)
        release.complete(Unit); holder.join(); third.join(); assertTrue(thirdEntered)
    }

    @Test(timeout = 5_000) fun cancelledIndependentResolutionKeepsReceiptUntilRealNativeReturn() = runBlocking {
        val root = Files.createTempDirectory("native-owner").toFile()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val owner = DownloadPrivateFileOwner(root, "native-id")
        try {
            val native = launch(Dispatchers.Default) {
                MediaResolutionRunner.run(3_000, owner) {
                    entered.countDown()
                    while (true) {
                        try { release.await(); break } catch (_: InterruptedException) { }
                    }
                }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            native.cancelAndJoin()
            assertTrue("Canceled caller must not claim the producer returned", DownloadPrivateFileOwners.hasOwners(owner))
            release.countDown()
            withTimeout(2_000) { while (DownloadPrivateFileOwners.hasOwners(owner)) delay(10) }
        } finally { release.countDown(); root.deleteRecursively() }
    }
}
