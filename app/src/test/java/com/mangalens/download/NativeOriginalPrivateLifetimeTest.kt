package com.mangalens.download

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Authored UNRUN: platform stream and actual-return contracts, independent of scheduler state. */
class NativeOriginalPrivateLifetimeTest {
    private open class OwnedProcess : Process() {
        val inputCloses = AtomicInteger(); val outputCloses = AtomicInteger(); val errorCloses = AtomicInteger()
        val stdin = object : ByteArrayOutputStream() { override fun close() { inputCloses.incrementAndGet(); super.close() } }
        val stdout = object : ByteArrayInputStream("real output\n".toByteArray()) { override fun close() { outputCloses.incrementAndGet(); super.close() } }
        val stderr = object : ByteArrayInputStream(ByteArray(0)) { override fun close() { errorCloses.incrementAndGet(); super.close() } }
        override fun getOutputStream() = stdin
        override fun getInputStream() = stdout
        override fun getErrorStream() = stderr
        override fun waitFor() = 0
        override fun exitValue() = 0
        override fun destroy() = Unit
    }

    @Test fun eachStreamHasExactlyOneOwnedCloseAcrossReadAndFinalCleanup() {
        val process = OwnedProcess(); var failed = false
        val resources = OwnedOriginalMediaProcessResources(process) { failed = true }
        resources.closeInput(); assertEquals("real output", resources.reader().readLine())
        resources.closeOutput(); resources.close(); resources.close()
        assertEquals(1, process.inputCloses.get()); assertEquals(1, process.outputCloses.get())
        assertEquals(1, process.errorCloses.get()); assertFalse(failed)
    }

    @Test fun canceledBeforeReadingStillClosesOnlyItsThreeActualStreams() {
        val process = OwnedProcess()
        val resources = OwnedOriginalMediaProcessResources(process) { fail("Close should succeed") }
        resources.close(); resources.close()
        assertEquals(1, process.inputCloses.get()); assertEquals(1, process.outputCloses.get())
        assertEquals(1, process.errorCloses.get())
    }

    @Test fun failedStreamCloseRetainsOwnershipAndDoesNotSkipTheOtherOwnedStreams() {
        val process = object : OwnedProcess() {
            val closes = AtomicInteger()
            override fun getInputStream() = object : ByteArrayInputStream(ByteArray(0)) {
                override fun close() { closes.incrementAndGet(); throw IOException("controlled close failure") }
            }
        }
        var retained = false
        val resources = OwnedOriginalMediaProcessResources(process) { retained = true }
        assertThrows(IOException::class.java) { resources.close() }; resources.close()
        assertTrue(retained); assertEquals(1, process.closes.get())
        assertEquals(1, process.inputCloses.get()); assertEquals(1, process.errorCloses.get())
    }

    @Test(timeout = 5_000) fun cancellationAndDestroyAcknowledgementDoNotReleaseBeforeActualExit() {
        val root = Files.createTempDirectory("owned-process-exit").toFile()
        val owner = DownloadPrivateFileOwner(root, "native-child")
        val lease = DownloadPrivateFileOwners.acquire(owner)
        val started = CountDownLatch(1); val actualExit = CountDownLatch(1); val returned = CountDownLatch(1)
        val process = object : OwnedProcess() {
            override fun waitFor(): Int { started.countDown(); actualExit.await(); return 0 }
        }
        val session = MediaResolutionSession(4_000, owner)
        val producer = Thread {
            try { NativeOriginalMediaRuntime.awaitOwnedProcessExit(process, session) }
            finally { if (!session.privateFilesReleased()) lease.retain(); lease.close(); returned.countDown() }
        }
        try {
            producer.start(); assertTrue(started.await(1, TimeUnit.SECONDS))
            session.cancel(); producer.interrupt()
            assertFalse(returned.await(100, TimeUnit.MILLISECONDS))
            assertTrue(DownloadPrivateFileOwners.hasOwners(owner))
            actualExit.countDown(); assertTrue(returned.await(1, TimeUnit.SECONDS))
            assertFalse(DownloadPrivateFileOwners.hasOwners(owner))
        } finally { actualExit.countDown(); producer.join(1_000); root.deleteRecursively() }
    }

    @Test fun unknownProcessExitCannotBePresentedAsAReleasedFileOwner() {
        val root = Files.createTempDirectory("unknown-process-exit").toFile()
        try {
            val owner = DownloadPrivateFileOwner(root, "native-child")
            val session = MediaResolutionSession(4_000, owner)
            val process = object : OwnedProcess() { override fun waitFor(): Int = error("controlled unknown exit") }
            assertThrows(IllegalStateException::class.java) { NativeOriginalMediaRuntime.awaitOwnedProcessExit(process, session) }
            assertFalse(session.privateFilesReleased())
            val lease = DownloadPrivateFileOwners.acquire(owner); lease.retain(); lease.close()
            assertTrue(DownloadPrivateFileOwners.hasOwners(owner))
        } finally { root.deleteRecursively() }
    }

    @Test fun unprovenProducerCleanupRetainsItsOwnOutputAndTimingFiles() {
        val root = Files.createTempDirectory("unproven-attempt-files").toFile()
        try {
            val base = root.resolve("owned.muxed-12345678-1234-1234-1234-123456789abc")
            val output = root.resolve("${base.name}.mp4").apply { writeText("owned output") }
            val timing = root.resolve("${base.name}.source-video.video.timing").apply { writeText("owned timing") }
            val session = MediaResolutionSession(4_000)
            session.retainPrivateFiles(); session.cancel()
            OriginalMediaRemuxer.cleanupAfterProcessing(base, session, rejected = true)
            OriginalMediaRemuxer.cleanupAfterProcessing(base, session, rejected = false)
            assertEquals("owned output", output.readText()); assertEquals("owned timing", timing.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun provenRejectedProducerCleanupRemovesOnlyItsAttemptAfterActualReturn() {
        val root = Files.createTempDirectory("released-attempt-files").toFile()
        try {
            val base = root.resolve("owned.muxed-12345678-1234-1234-1234-123456789abc")
            val output = root.resolve("${base.name}.mp4").apply { writeText("rejected output") }
            val timing = root.resolve("${base.name}.result.video.timing").apply { writeText("rejected timing") }
            val peer = root.resolve("peer.mp4").apply { writeText("valuable neighbour") }
            OriginalMediaRemuxer.cleanupAfterProcessing(base, MediaResolutionSession(4_000), rejected = true)
            assertFalse(output.exists()); assertFalse(timing.exists()); assertEquals("valuable neighbour", peer.readText())
        } finally { root.deleteRecursively() }
    }
}
