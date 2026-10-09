package com.mangalens.core.translation

import com.mangalens.core.compute.NativeComputeAdmission
import com.mangalens.core.compute.checkNativeComputePrecondition
import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.reader.SavedChapter
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real captured chapter journals and a held native queue; no JNI effect can run on stale authority. */
class ChapterNativeEntryGuardTest {
    @Test fun pauseWhileQueuedCannotEnterOldNativeWork() = heldEntry { f, task, _ -> f.store.pause(task.id, task.generation) }
    @Test fun sameConfigurationReplacedOwnerAndGenerationCannotEnterOldNativeWork() = heldEntry { f, _, _ ->
        f.store.start(f.chapter, f.config, ownerRequestId = "reader:new", forceReprocess = true)
    }
    @Test fun changedTargetCannotEnterAnOldNativeRequest() = heldEntry { f, task, _ ->
        f.store.cancel(task.id, task.generation)
        f.store.start(f.chapter, f.config.copy(targetLanguage = "en"), ownerRequestId = "reader:new")
    }
    @Test fun sourceBytesChangedWithSameLengthAndTimestampCannotEnterQueuedNativeWork() = heldEntry { f, _, _ ->
        val stamp = Files.getLastModifiedTime(f.source.toPath())
        f.source.writeText("replaced source bytes")
        Files.setLastModifiedTime(f.source.toPath(), stamp)
    }
    @Test fun actualOwnerControlChangeWithoutReplacingTheNativeJournalStillBlocksEntry() = heldEntry { _, _, owner -> owner.set(false) }
    @Test fun explicitChapterRemovalWhileQueuedCannotEnterOrResurrectOldWork() = heldEntry { f, _, _ ->
        f.store.finishChapterRemoval(f.store.beginChapterRemoval(f.chapter.id))
    }

    @Test fun sourceHashRunsOnlyAtFinalWaitedEntryAndFreshGenerationStillRuns() = runBlocking {
        Fixture().use { f ->
            val task = f.start()
            val hashes = AtomicInteger()
            val guard = ChapterNativeEntryGuard(f.store, task, task.pages.single(), { true }) { file ->
                hashes.incrementAndGet(); ChapterTranslationStore.sha256(file)
            }
            val admission = NativeComputeAdmission(pollMs = 5)
            val holder = admission.acquire(NativeComputeAdmission.Priority.LIVE) { true }!!
            val queued = CountDownLatch(1)
            val entered = AtomicBoolean()
            val waiting = async(Dispatchers.IO) { withContext(guard.precondition) {
                val lease = admission.acquire(NativeComputeAdmission.Priority.BACKGROUND) { queued.countDown(); true }!!
                try { checkNativeComputePrecondition(lease.waited); entered.set(true) } finally { lease.close() }
            } }
            try {
                assertTrue(queued.await(2, TimeUnit.SECONDS))
                assertEquals("Queue polls must not hash source files", 0, hashes.get())
                holder.close()
                withTimeout(2_000) { waiting.await() }
                assertTrue(entered.get())
                assertEquals("One final waited entry needs one bounded source hash", 1, hashes.get())
                entered.set(false)
                withContext(guard.precondition) {
                    val lease = admission.acquire(NativeComputeAdmission.Priority.BACKGROUND) { true }!!
                    try { checkNativeComputePrecondition(lease.waited); entered.set(true) } finally { lease.close() }
                }
                assertTrue(entered.get())
                assertEquals("Unchanged unqueued entry reuses the verified atomic source identity", 1, hashes.get())
            } finally { holder.close(); waiting.cancelAndJoin() }
        }
    }

    @Test fun authorityReplacementDuringHeldFinalHashCannotEnterNativeWork() = runBlocking {
        Fixture().use { f ->
            val task = f.start()
            val hashStarted = CountDownLatch(1); val releaseHash = CountDownLatch(1)
            val guard = ChapterNativeEntryGuard(f.store, task, task.pages.single(), { true }) { file ->
                hashStarted.countDown(); check(releaseHash.await(2, TimeUnit.SECONDS)); ChapterTranslationStore.sha256(file)
            }
            val effect = AtomicBoolean()
            val waiting = async(Dispatchers.IO) { runCatching { withContext(guard.precondition) {
                checkNativeComputePrecondition(waited = true); effect.set(true)
            } } }
            try {
                assertTrue(hashStarted.await(2, TimeUnit.SECONDS))
                f.store.start(f.chapter, f.config, ownerRequestId = "reader:new", forceReprocess = true)
                releaseHash.countDown()
                assertTrue(withTimeout(2_000) { waiting.await() }.isFailure)
                assertFalse(effect.get())
            } finally { releaseHash.countDown(); waiting.cancelAndJoin() }
        }
    }

    private fun heldEntry(mutate: (Fixture, ChapterTranslationTask, AtomicBoolean) -> Unit) = runBlocking {
        Fixture().use { f ->
            val task = f.start()
            val owner = AtomicBoolean(true)
            val guard = ChapterNativeEntryGuard(f.store, task, task.pages.single(), { owner.get() })
            val admission = NativeComputeAdmission(pollMs = 5)
            val holder = admission.acquire(NativeComputeAdmission.Priority.LIVE) { true }!!
            val queued = CountDownLatch(1); val entered = AtomicBoolean()
            val waiting = async(Dispatchers.IO) { runCatching { withContext(guard.precondition) {
                val lease = admission.acquire(NativeComputeAdmission.Priority.BACKGROUND) { queued.countDown(); true }!!
                try { checkNativeComputePrecondition(lease.waited); entered.set(true) } finally { lease.close() }
            } } }
            try {
                assertTrue(queued.await(2, TimeUnit.SECONDS))
                mutate(f, task, owner)
                holder.close()
                assertTrue("Stale queued work did not reject its captured authority", withTimeout(2_000) { waiting.await() }.isFailure)
                assertFalse("Stale captured work entered a native effect", entered.get())
                val fresh = f.prepare(f.store.start(f.chapter, f.config, ownerRequestId = "reader:fresh", forceReprocess = true))
                if (f.store.isCurrent(fresh.id, fresh.generation)) {
                    val freshGuard = ChapterNativeEntryGuard(f.store, fresh, fresh.pages.single(), { true })
                    withContext(freshGuard.precondition) { checkNativeComputePrecondition(waited = true) }
                }
            } finally { holder.close(); waiting.cancelAndJoin() }
        }
    }

    internal class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("chapter-native-entry").toFile()
        private val sources = File(root, "chapters").apply { mkdirs() }
        val source = File(sources, "page.img").apply { writeText("original source bytes") }
        val chapter = SavedChapter("a".repeat(32), "Native queued fixture", "local:fixture", listOf(ChapterPage(0, "local:zero", source.path)))
        val config = ChapterTranslationConfig("hi")
        private val io = object : ChapterJournalIo {
            override fun read(file: File) = file.readBytes()
            override fun write(file: File, bytes: ByteArray) {
                val pending = File(file.parentFile, file.name + ".test-pending")
                pending.outputStream().use { it.write(bytes); it.fd.sync() }
                Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        val store = ChapterTranslationStore(File(root, "chapter_translations"), sources, io) { true }
        fun start() = prepare(store.start(chapter, config, ownerRequestId = "reader:captured"))
        fun prepare(task: ChapterTranslationTask): ChapterTranslationTask {
            store.markRunning(task.id, task.generation)
            store.beginPage(task.id, task.generation, 0)
            return store.get(task.id)!!
        }
        override fun close() { root.deleteRecursively() }
    }
}
