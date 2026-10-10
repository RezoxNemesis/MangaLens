package com.mangalens.core.translation.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** UNRUN actual journal/incarnation controls for profile values and their lease captured atomically on IO. */
class MemoryPreparedChapterReadTest {
    @Test fun atomicReadReturnsNewProfileRatherThanTheOldSeparatelyDecodedGlossary() = runBlocking {
        Fixture().use { f ->
            val old = requireNotNull(f.store.profile("series-one")); val chapter = f.store.inspectChapter("chapter-one")
            f.store.upsertTerm("series-one", SeriesGlossaryTerm("term-one", "Hello", "नमस्ते", "hi"))
            val read = requireNotNull(f.store.prepareChapterRead(chapter))
            assertTrue(old.glossary.isEmpty()); assertEquals(1, read.profile!!.glossary.size)
            assertEquals("नमस्ते", read.delivery.tryCommit { read.profile!!.glossary.single().preferred })
        }
    }
    @Test fun profileEditAfterAtomicReadRetiresTheValuesBeforeDelivery() = runBlocking {
        Fixture().use { f ->
            val read = requireNotNull(f.store.prepareChapterRead(f.store.inspectChapter("chapter-one")))
            f.store.upsertTerm("series-one", SeriesGlossaryTerm("term-one", "Hello", "नमस्ते", "hi"))
            assertNull(read.delivery.tryCommit { read.profile })
        }
    }
    @Test fun identicalByteAtomicProfileReplacementRetiresItsPreviousReadLease() = runBlocking {
        Fixture().use { f ->
            val read = requireNotNull(f.store.prepareChapterRead(f.store.inspectChapter("chapter-one")))
            val profile = File(f.root, "reader_memory/series/series-one.json")
            val replacement = File(profile.parentFile, "same-profile.pending")
            FileOutputStream(replacement).use { it.write(profile.readBytes()); it.fd.sync() }
            Files.move(replacement.toPath(), profile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            assertNull(read.delivery.tryCommit { read.profile })
        }
    }
    @Test fun relinkBetweenExpectedChapterAndAtomicReadIsRejected() = runBlocking {
        Fixture().use { f ->
            val old = f.store.inspectChapter("chapter-one")
            f.store.unlinkChapter("chapter-one"); f.store.associateChapter("chapter-one", "series-one", 0)
            assertNull(f.store.prepareChapterRead(old))
        }
    }
    @Test fun removedSeriesCannotProvideItsPreviousGlossary() = runBlocking {
        Fixture().use { f ->
            val old = f.store.inspectChapter("chapter-one"); f.store.removeSeries("series-one")
            assertNull(f.store.prepareChapterRead(old))
        }
    }
    @Test fun unlinkedChapterKeepsANullProfileAndLeaseWithoutCreatingAJournal() = runBlocking {
        Fixture(linked = false).use { f ->
            val expected = f.store.inspectChapter("chapter-one")
            val read = requireNotNull(f.store.prepareChapterRead(expected))
            assertEquals(expected, read.chapter); assertNull(read.profile)
            assertEquals("metadata", read.delivery.tryCommit { "metadata" })
            assertFalse(File(f.root, "reader_memory").exists())
        }
    }
    private class Fixture(linked: Boolean = true) : AutoCloseable {
        val root = Files.createTempDirectory("atomic-reader-profile").toFile(); val store = SeriesMemoryStore(root)
        init { if (linked) runBlocking { store.createSeries("Series", "series-one"); store.associateChapter("chapter-one", "series-one", 0) } }
        override fun close() { root.deleteRecursively() }
    }
}
