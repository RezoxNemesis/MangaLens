package com.mangalens.core.translation.memory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SeriesMemoryPublicationFenceTest {
    private fun fixture(block: suspend (File, MemoryPublicationReceipt) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("memory-publication-owner").toFile()
        try {
            val source = File(root, "chapters/source.png").apply { parentFile!!.mkdirs(); writeText("source proof") }
            val receipt = MemoryPublicationReceipt(MemorySourceProof("chapter", 1, source.absolutePath,
                SeriesMemoryStore.sha256(source), 900, 1100, MemoryRegionBounds(10, 10, 800, 200)),
                "task", "G1", "hi", "faithful:pin1", "Jin, do not worry.")
            block(root, receipt)
        } finally { root.deleteRecursively() }
    }

    @Test fun readOnlyStoreCannotMutateSourceHistoryWithoutRealOwnerAuthority() = fixture { root, receipt ->
        val store = SeriesMemoryStore(root)
        var rejected = false
        try { store.indexBubble(receipt, receipt) } catch (_: IllegalStateException) { rejected = true }
        assertTrue("Static receipt equality cannot authorize an independently queued source mutation", rejected)
        assertTrue(store.inspectChapter("chapter").bubbles.isEmpty())
    }

    @Test fun queuedEditInvalidatedBeforePublicationCannotPersistOldGeneration() = fixture { root, receipt ->
        val monitor = Any(); var current = receipt
        val queued = CountDownLatch(1); val release = CountDownLatch(1); val holdNext = AtomicBoolean(false)
        val store = SeriesMemoryStore(root, MemoryPublicationFence { expected, commit ->
            if (holdNext.getAndSet(false)) { queued.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            synchronized(monitor) { check(current == expected) { "Generation changed" }; commit() }
        })
        store.indexBubble(receipt, receipt)
        holdNext.set(true)
        val edit = kotlinx.coroutines.CoroutineScope(Dispatchers.Default).async {
            runCatching { store.correct(receipt, receipt, 0, MemoryCorrectionEdit(translated = "जिन, चिंता मत करो।")) }
        }
        try {
            assertTrue("Actual production authority boundary must be reached", queued.await(5, TimeUnit.SECONDS))
            synchronized(monitor) { current = receipt.copy(generation = "G2") }
            release.countDown()
            assertTrue(edit.await().isFailure)
            assertNull(store.inspectChapter("chapter").bubbles.single().correction)
        } finally { release.countDown(); edit.cancel(); edit.join() }
    }

    @Test fun generationHandoffDuringPreparationRejectsOldOwnerWithoutBlockingNavigation() = fixture { root, receipt ->
        val monitor = Any(); var current = receipt
        val writing = CountDownLatch(1); val release = CountDownLatch(1); val invalidating = CountDownLatch(1); val invalidated = CountDownLatch(1)
        val holdNext = AtomicBoolean(false)
        val writer = MemoryJournalWriter { file, bytes ->
            if (holdNext.getAndSet(false)) { writing.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            val temporary = File(file.parentFile, file.name + ".prepared")
            temporary.outputStream().use { it.write(bytes); it.fd.sync() }
            object : PreparedMemoryJournal {
                override fun commit() { java.nio.file.Files.move(temporary.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                override fun close() { temporary.delete() }
            }
        }
        val store = SeriesMemoryStore(root, writer, MemoryPublicationFence { expected, commit ->
            synchronized(monitor) { check(current == expected); commit() }
        })
        store.indexBubble(receipt, receipt); holdNext.set(true)
        val scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Default)
        val edit = scope.async { runCatching { store.correct(receipt, receipt, 0, MemoryCorrectionEdit(translated = "जिन, चिंता मत करो।")) } }
        var handoff: kotlinx.coroutines.Deferred<Unit>? = null
        try {
            assertTrue(writing.await(5, TimeUnit.SECONDS))
            handoff = scope.async { invalidating.countDown(); synchronized(monitor) { current = receipt.copy(generation = "G2"); invalidated.countDown() } }
            assertTrue(invalidating.await(5, TimeUnit.SECONDS))
            assertTrue("New generation must not wait for journal preparation", invalidated.await(1, TimeUnit.SECONDS))
            release.countDown()
            assertTrue(edit.await().isFailure)
            handoff.await(); assertEquals("G2", current.generation)
        } finally { release.countDown(); edit.cancel(); edit.join(); handoff?.cancel(); handoff?.join() }
    }
}
