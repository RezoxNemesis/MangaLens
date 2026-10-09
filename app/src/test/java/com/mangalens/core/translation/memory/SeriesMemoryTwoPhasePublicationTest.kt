package com.mangalens.core.translation.memory

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

class SeriesMemoryTwoPhasePublicationTest {
    @Test fun heldSyncedJournalPreparationLetsTheReaderRetireAndRejectsItsOldEdit() = runBlocking {
        val root = Files.createTempDirectory("memory-two-phase").toFile()
        val guard = Any(); var current = true
        val held = AtomicBoolean(false)
        val preparing = CountDownLatch(1); val release = CountDownLatch(1); val retired = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val source = File(root, "chapters/source.jpg").apply { parentFile!!.mkdirs(); writeText("source original") }
            val receipt = MemoryPublicationReceipt(MemorySourceProof("chapter", 0, source.path,
                SeriesMemoryStore.sha256(source), 100, 200, MemoryRegionBounds(1, 2, 90, 40)),
                "task", "G1", "hi", "config", "Hello.", "नमस्ते।")
            val writer = MemoryJournalWriter { file, bytes ->
                val temporary = File(file.parentFile, file.name + ".test-pending")
                try {
                    FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
                    if (held.getAndSet(false)) { preparing.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                    object : PreparedMemoryJournal {
                        override fun commit() { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                        override fun close() { temporary.delete() }
                    }
                } catch (failure: Throwable) { temporary.delete(); throw failure }
            }
            val store = SeriesMemoryStore(root, writer, MemoryPublicationFence { _, commit ->
                synchronized(guard) { check(current) { "Reader retired" }; commit() }
            })
            store.indexBubble(receipt, receipt)
            held.set(true)
            val edit = scope.async { runCatching { store.correct(receipt, receipt, 0, MemoryCorrectionEdit(translated = "नमस्ते, मित्र।")) } }
            var retirement: Deferred<Unit>? = null
            try {
                assertTrue(preparing.await(5, TimeUnit.SECONDS))
                retirement = scope.async { synchronized(guard) { current = false; retired.countDown() } }
                assertTrue("A held fsync must not hold the Reader's navigation authority", retired.await(1, TimeUnit.SECONDS))
                release.countDown()
                assertTrue("Retired G1 edit reached the journal", edit.await().isFailure)
                assertNull(store.inspectChapter("chapter").bubbles.single().correction)
            } finally { release.countDown(); edit.cancel(); edit.join(); retirement?.cancel(); retirement?.join() }
        } finally { release.countDown(); scope.cancel(); root.deleteRecursively() }
    }
}
