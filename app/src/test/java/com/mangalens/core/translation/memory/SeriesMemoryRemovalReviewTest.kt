package com.mangalens.core.translation.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SeriesMemoryRemovalReviewTest {
    @Test fun retainedOldSourceHistoryCannotBlockAGlossaryTermFromItsNewVerifiedPage() = runBlocking {
        val root = Files.createTempDirectory("memory-source-refresh-review").toFile()
        try {
            val source = File(root, "chapters/source.png").apply {
                parentFile!!.mkdirs(); writeText("old original source bytes")
            }
            val old = MemoryPublicationReceipt(
                MemorySourceProof("chapter", 1, source.absolutePath, SeriesMemoryStore.sha256(source),
                    720, 1200, MemoryRegionBounds(20, 20, 400, 160)),
                "task", "G1", "hi", "exact-config-pin", "Jin, wait.", seriesId = "series")
            var current = old
            val store = SeriesMemoryStore(root, MemoryPublicationFence { expected, commit ->
                check(expected == current); commit()
            })
            store.createSeries("Owned series", "series")
            store.associateChapter("chapter", "series", 1)
            store.indexBubble(old, old)
            // Source replacement keeps inspectable G1 history while a real G2 is indexed.
            source.writeText("new authentic original source bytes")
            val replacement = old.copy(source = old.source.copy(sourceSha256 = SeriesMemoryStore.sha256(source)), generation = "G2")
            current = replacement
            store.indexBubble(replacement, replacement)
            assertEquals(2, store.inspectChapter("chapter").bubbles.size)
            assertEquals(1, store.search("Jin").hits.size)
            val saved = runCatching {
                store.upsertTerm("series", SeriesGlossaryTerm("jin", "Jin", "जिन", "hi",
                    origin = MemoryLocation("chapter", 1), originSourceSha256 = replacement.source.sourceSha256))
            }
            assertTrue("Stale retained source history must not make the newly verified page ambiguous", saved.isSuccess)
            assertEquals(replacement.source.sourceSha256, store.profile("series")!!.glossary.single().originSourceSha256)
            store.associateChapter("later", "series", 2)
            assertEquals(mapOf("Jin" to "जिन"), store.relevant(MemoryRetrievalRequest("later", 1, "Jin, wait.", "hi", old.configurationIdentity)).glossary)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun editorCapturedBeforeCorrectionRemovalCannotReviveTheRemovedText() = runBlocking {
        val root = Files.createTempDirectory("memory-removal-review").toFile()
        try {
            val source = File(root, "chapters/source.png").apply {
                parentFile!!.mkdirs(); writeText("same original source bytes")
            }
            val receipt = MemoryPublicationReceipt(
                MemorySourceProof("chapter", 1, source.absolutePath, SeriesMemoryStore.sha256(source),
                    720, 1200, MemoryRegionBounds(20, 20, 400, 160)),
                "task", "G1", "hi", "exact-config-pin", "Jin, wait.", "जिन, रुको।")
            val fence = MemoryPublicationFence { expected, commit ->
                check(expected == receipt); commit()
            }
            val store = SeriesMemoryStore(root, fence)
            store.indexBubble(receipt, receipt)
            // Editor A captures revision0 before B saves then removes a correction.
            val editorARevision = store.inspectChapter("chapter").bubbles.single().correction?.revision ?: 0
            val accepted = store.correct(receipt, receipt, 0, MemoryCorrectionEdit(translated = "accepted edit"))
            store.removeCorrection(receipt, receipt, accepted.revision)
            val cold = SeriesMemoryStore(root, fence)
            val journal = File(root, "reader_memory/chapters/chapter.json")
            val afterRemoval = journal.readBytes()
            val late = runCatching {
                cold.correct(receipt, receipt, editorARevision, MemoryCorrectionEdit(translated = "removed stale edit"))
            }
            assertTrue("A held revision0 editor must be rejected after explicit correction removal", late.isFailure)
            assertArrayEquals("Rejected stale editor must preserve the accepted removal", afterRemoval, journal.readBytes())
            assertTrue(cold.search("removed stale edit").hits.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }
}
