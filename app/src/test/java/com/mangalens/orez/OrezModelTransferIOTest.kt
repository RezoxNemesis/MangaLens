package com.mangalens.orez

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class OrezModelTransferIOTest {
    @Test fun ownedResumeWritesExactRemainingBytesAndPublishesCompletion() = runBlocking {
        val directory = Files.createTempDirectory("orez-valid-resume").toFile()
        val part = File(directory, "model.part").apply { writeText("AB") }
        val published = mutableListOf<Long>()
        try {
            OrezModelTransferIO.withWriter(part, {}) {
                ByteArrayInputStream("CD".toByteArray()).use {
                    OrezModelTransferIO.copy(part, it, 2, 4, {}, { 0 }, published::add)
                }
            }
            assertEquals("ABCD", part.readText())
            assertEquals(listOf(4L), published)
        } finally { directory.deleteRecursively() }
    }

    @Test fun ownerFenceRejectsLateReadEvenBeforeCoroutineCancellationArrives() = runBlocking {
        val directory = Files.createTempDirectory("orez-owner-fence").toFile()
        val part = File(directory, "model.part").apply { writeText("AB") }
        val persisted = AtomicReference(OrezModelTransferIdentity("old", true))
        val owner = OrezModelTransferOwner<() -> Unit>(Any(), persisted::get, { next, mutation -> persisted.set(next); mutation() }, {})
        val held = HeldInput("C")
        val old = launch(Dispatchers.IO) {
            OrezModelTransferIO.withWriter(part, { owner.checkpoint("old") }) {
                held.use { OrezModelTransferIO.copy(part, it, 2, 100, { owner.checkpoint("old") }, { 0 }, {}) }
            }
        }
        try {
            assertTrue(held.entered.await(3, TimeUnit.SECONDS))
            owner.pause()
            // Do not cancel the coroutine: durable identity alone must reject the late byte.
            held.release.countDown()
            old.join()
            assertEquals("AB", part.readText())
            assertTrue(held.closed)
        } finally {
            held.release.countDown()
            old.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    @Test fun cancellationDisconnectsConnectionButRetainsWriterUntilReadReturns() = runBlocking {
        val directory = Files.createTempDirectory("orez-cancel-disconnect").toFile()
        val part = File(directory, "model.part").apply { writeText("AB") }
        val held = HeldInput("C")
        val disconnected = CountDownLatch(1)
        val nextWriter = CountDownLatch(1)
        val old = launch(Dispatchers.IO) {
            OrezModelTransferIO.withWriter(part, {}) {
                OrezModelTransferIO.withConnection({ disconnected.countDown() }) {
                    held.use { OrezModelTransferIO.copy(part, it, 2, 100, {}, { 0 }, {}) }
                }
            }
        }
        var next: kotlinx.coroutines.Job? = null
        try {
            assertTrue(held.entered.await(3, TimeUnit.SECONDS))
            old.cancel()
            assertTrue("cancel must attempt disconnect without waiting for read", disconnected.await(3, TimeUnit.SECONDS))
            next = launch(Dispatchers.IO) { OrezModelTransferIO.withWriter(part, {}) { nextWriter.countDown() } }
            assertFalse(nextWriter.await(200, TimeUnit.MILLISECONDS))
            held.release.countDown()
            joinAll(old, next)
            assertTrue(held.closed)
            assertEquals("AB", part.readText())
        } finally {
            held.release.countDown()
            old.cancelAndJoin()
            next?.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    @Test fun cancelledQueuedResumeNeverOpensOrResetsThePartial() = runBlocking {
        val directory = Files.createTempDirectory("orez-queued-cancel").toFile()
        val part = File(directory, "model.part").apply { writeText("AB") }
        val held = HeldInput("C")
        val old = launch(Dispatchers.IO) {
            OrezModelTransferIO.withWriter(part, {}) {
                held.use { OrezModelTransferIO.copy(part, it, 2, 100, {}, { 0 }, {}) }
            }
        }
        var next: kotlinx.coroutines.Job? = null
        try {
            assertTrue(held.entered.await(3, TimeUnit.SECONDS))
            next = launch(Dispatchers.IO) {
                OrezModelTransferIO.withWriter(part, {}) { part.writeText("unexpected truncation") }
            }
            next.cancelAndJoin()
            old.cancel()
            held.release.countDown()
            old.join()
            assertEquals("AB", part.readText())
        } finally {
            held.release.countDown()
            old.cancelAndJoin()
            next?.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    @Test fun cancellationAfterHeldReadPreservesResumeBytesAndNewProgress() = runBlocking {
        val directory = Files.createTempDirectory("orez-held-read").toFile()
        val part = File(directory, "model.part").apply { writeText("AB") }
        val held = HeldInput("C")
        val clock = AtomicLong(0)
        var progress = "old"
        val old = launch(Dispatchers.IO) {
            held.use {
                OrezModelTransferIO.withWriter(part, {}) {
                    OrezModelTransferIO.copy(part, it, 2, 100, {}, clock::get) { progress = "old" }
                }
            }
        }
        try {
            assertTrue("old reader did not block", held.entered.await(3, TimeUnit.SECONDS))
            old.cancel()
            progress = "resumed"
            clock.set(2_000)
            held.release.countDown()
            old.join()
            assertEquals("a cancelled read must not append its late byte", "AB", part.readText())
            assertEquals("a cancelled read must not publish older progress", "resumed", progress)
        } finally {
            held.release.countDown()
            old.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    @Test fun resumeCannotOpenPartialUntilCancelledReadersInputAndOutputAreClosed() = runBlocking {
        val directory = Files.createTempDirectory("orez-writer-overlap").toFile()
        val part = File(directory, "model.part").apply { writeText("AB") }
        val held = HeldInput("C")
        val resumedOpened = CountDownLatch(1)
        val old = launch(Dispatchers.IO) {
            OrezModelTransferIO.withWriter(part, {}) {
                held.use { OrezModelTransferIO.copy(part, it, part.length(), 100, {}, { 0 }, {}) }
            }
        }
        var resumed: kotlinx.coroutines.Job? = null
        try {
            assertTrue(held.entered.await(3, TimeUnit.SECONDS))
            old.cancel()
            resumed = launch(Dispatchers.IO) {
                OrezModelTransferIO.withWriter(part, {}) {
                    resumedOpened.countDown()
                    ByteArrayInputStream("D".toByteArray()).use {
                        OrezModelTransferIO.copy(part, it, part.length(), 100, {}, { 0 }, {})
                    }
                }
            }
            assertFalse("resume opened the same partial while old native read was pending", resumedOpened.await(200, TimeUnit.MILLISECONDS))
            held.release.countDown()
            joinAll(old, resumed)
            assertTrue("input must close before writer ownership passes", held.closed)
            assertEquals("AB D must remain one resumable stream", "ABD", part.readText())
        } finally {
            held.release.countDown()
            old.cancelAndJoin()
            resumed?.cancelAndJoin()
            directory.deleteRecursively()
        }
    }

    private class HeldInput(private val bytes: String) : InputStream() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile var closed = false
        private var emitted = false
        override fun read(): Int = error("bulk reads only")
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (emitted) return -1
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "test did not release held input" }
            emitted = true
            val data = bytes.toByteArray()
            data.copyInto(buffer, offset)
            return data.size
        }
        override fun close() { closed = true }
    }
}
