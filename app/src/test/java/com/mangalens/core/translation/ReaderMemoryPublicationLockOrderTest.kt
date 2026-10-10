package com.mangalens.core.translation

import com.mangalens.core.translation.memory.MemoryCorrectionEdit
import com.mangalens.core.translation.memory.SeriesMemoryStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Actual journal/monitor regression; uses existing native-memory JVM fixture, not model/image quality. */
class ReaderMemoryPublicationLockOrderTest {
    @Test fun unrelatedNativeJournalFsyncMustNotHoldReaderRetirementBehindAWaitingCorrection() = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val holdProof = AtomicBoolean()
            val proofReached = CountDownLatch(1); val releaseProof = CountDownLatch(1)
            val publisherThread = AtomicReference<Thread>()
            val writer = HeldNativeJournal()
            val store = ChapterTranslationStore(f.journals, f.sources, writer, originalDimensions = {
                if (holdProof.getAndSet(false)) {
                    publisherThread.set(Thread.currentThread()); proofReached.countDown()
                    check(releaseProof.await(5, TimeUnit.SECONDS))
                }
                f.actualSourceDimensions
            }) { it.readText().startsWith("valid surface:") }
            val task = f.completed(nativeStore = store)
            val authority = ReaderMemoryPublicationAuthority()
            val selected = authority.activate(ReaderTranslationPresentation.receipt(task))
            val adapter = NativeMemoryPublicationAdapter(f.root, store, authority)
            val editor = requireNotNull(adapter.openEditor(selected, 0, 0))
            val journal = File(f.root, "reader_memory/chapters/${f.chapter.id}.json")
            val before = journal.readBytes()
            holdProof.set(true)
            val correction = async(Dispatchers.IO) {
                runCatching { adapter.correct(editor, 0, MemoryCorrectionEdit(translated = "नमस्ते, मित्र।")) }
            }
            var unrelated: Deferred<*>? = null
            var retirement: Deferred<*>? = null
            try {
                assertTrue("The correction must capture its actual native page before the unrelated journal write", proofReached.await(5, TimeUnit.SECONDS))
                writer.hold.set(true)
                unrelated = async(Dispatchers.IO) {
                    store.start(f.chapter.copy(id = "b".repeat(32), sourceUrl = "local:unrelated-native-chapter"), f.config,
                        ownerRequestId = "unrelated-native-writer")
                }
                assertTrue("The unrelated native writer must really reach fd.sync while owning the native monitor", writer.prepared.await(5, TimeUnit.SECONDS))
                releaseProof.countDown()
                withTimeout(2_000) {
                    while (publisherThread.get()?.let { thread -> thread.state == Thread.State.BLOCKED &&
                        thread.stackTrace.any { it.className == ChapterTranslationStore::class.java.name && it.methodName.startsWith("commitMemoryPublication") } } != true) delay(5)
                }
                retirement = async(Dispatchers.Default) { authority.retire(); true }
                val retiredBeforeNativeRelease = withTimeoutOrNull(400) { retirement.await() }
                assertEquals("Reader retirement must complete while an unrelated native fsync is still held", true, retiredBeforeNativeRelease)
                writer.release.countDown()
                unrelated.await()
                assertTrue("The now-retired correction must be rejected before its prepared personal journal rename", correction.await().isFailure)
                assertArrayEquals(before, journal.readBytes())
                assertNull(SeriesMemoryStore(f.root).inspectChapter(f.chapter.id).bubbles.single().correction)
            } finally {
                releaseProof.countDown(); writer.release.countDown()
                correction.cancelAndJoin(); unrelated?.cancelAndJoin(); retirement?.cancelAndJoin()
            }
        }
    }

    private class HeldNativeJournal : ChapterJournalIo {
        val hold = AtomicBoolean()
        val prepared = CountDownLatch(1); val release = CountDownLatch(1)
        override fun read(file: File) = file.readBytes()
        override fun write(file: File, bytes: ByteArray) {
            val pending = File(file.parentFile, file.name + ".held-native-pending")
            try {
                FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
                if (hold.getAndSet(false)) { prepared.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { pending.delete() }
        }
    }
}
