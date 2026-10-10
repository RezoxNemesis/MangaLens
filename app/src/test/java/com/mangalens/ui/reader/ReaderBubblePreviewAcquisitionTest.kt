package com.mangalens.ui.reader

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/** Actual coroutine dispatch and real open FD ownership; this makes no Bitmap-quality claim. */
class ReaderBubblePreviewAcquisitionTest {
    private class Preview(file: File) : AutoCloseable {
        val input = FileInputStream(file)
        val closes = AtomicInteger()
        override fun close() { closes.incrementAndGet(); input.close() }
    }

    private class HeldDelivery : CoroutineDispatcher() {
        val waiting = CompletableDeferred<Unit>()
        private val queued = ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued.add(block); waiting.complete(Unit)
        }
        fun drain() { while (true) (queued.poll() ?: break).run() }
    }

    @Test fun successfulIoHandoffKeepsTheRealDescriptorOwnedByTheCaller() = runBlocking {
        val file = File.createTempFile("reader-preview-", ".source").apply { writeText("original") }
        val preview = Preview(file)
        try {
            assertSame(preview, acquireReaderBubblePreviewOnIo { preview })
            assertTrue(preview.input.channel.isOpen)
            assertEquals(0, preview.closes.get())
            preview.close()
            assertFalse(preview.input.channel.isOpen)
            assertEquals(1, preview.closes.get())
        } finally { if (preview.input.channel.isOpen) preview.close(); file.delete() }
    }

    @Test fun cancellingAResultAlreadyQueuedForMainReleasesItsDescriptorExactlyOnce() = runBlocking {
        val file = File.createTempFile("reader-preview-", ".source").apply { writeText("original") }
        val preview = Preview(file)
        val delivery = HeldDelivery()
        val opened = CompletableDeferred<Unit>()
        val releaseIo = CompletableDeferred<Unit>()
        var accepted: Preview? = null
        val job = launch(delivery, start = CoroutineStart.UNDISPATCHED) {
            accepted = acquireReaderBubblePreviewOnIo {
                opened.complete(Unit)
                releaseIo.await()
                preview
            }
        }
        try {
            withTimeout(1000) { opened.await() }
            // launch has returned suspended; an immediate IO result can no longer bypass dispatch.
            releaseIo.complete(Unit)
            withTimeout(1000) { delivery.waiting.await() }
            assertTrue(preview.input.channel.isOpen)
            job.cancel()
            delivery.drain()
            job.join()
            assertNull("Cancelled delivery cannot transfer this FD to a dialog", accepted)
            assertFalse(preview.input.channel.isOpen)
            assertEquals(1, preview.closes.get())
        } finally {
            releaseIo.complete(Unit); job.cancel(); delivery.drain(); job.join()
            if (preview.input.channel.isOpen) preview.close()
            file.delete()
        }
    }

    @Test fun cancellationWhileNonCancellableDecodeFinishesStillClosesTheCreatedResult() = runBlocking {
        val file = File.createTempFile("reader-preview-", ".source").apply { writeText("original") }
        val created = CompletableDeferred<Preview>()
        val release = CompletableDeferred<Unit>()
        val job = launch {
            acquireReaderBubblePreviewOnIo {
                withContext(NonCancellable) {
                    val preview = Preview(file)
                    created.complete(preview); release.await(); preview
                }
            }
        }
        try {
            val preview = withTimeout(1000) { created.await() }
            job.cancel(); yield()
            assertEquals(0, preview.closes.get())
            release.complete(Unit); job.join()
            assertFalse(preview.input.channel.isOpen)
            assertEquals(1, preview.closes.get())
        } finally { release.complete(Unit); job.cancelAndJoin(); file.delete() }
    }

    @Test fun aCallerCancelledBeforeStartingCannotOpenAnotherPreviewDescriptor() = runBlocking {
        val opens = AtomicInteger()
        val job = launch(start = CoroutineStart.LAZY) {
            acquireReaderBubblePreviewOnIo<AutoCloseable> { opens.incrementAndGet(); null }
        }
        job.cancelAndJoin()
        assertEquals(0, opens.get())
    }
}
