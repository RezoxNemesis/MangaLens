package com.mangalens.core.translation.memory

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SeriesMemoryEditRevisionTest {
    private fun environment(block: suspend (File, MemoryPublicationReceipt, MemoryPublicationFence) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("memory-monotonic-edit").toFile()
        try {
            val source = File(root, "chapters/source.jpg").apply { parentFile!!.mkdirs(); writeText("owned original source bytes") }
            val output = File(root, "chapter_translations/task/output.png").apply { parentFile!!.mkdirs(); writeText("owned generated output bytes") }
            val receipt = MemoryPublicationReceipt(MemorySourceProof("chapter", 1, source.absolutePath, SeriesMemoryStore.sha256(source),
                720, 1200, MemoryRegionBounds(20, 20, 400, 160)), "task", "generation", "hi", "full-captured-style-and-pin", "Jin, wait.",
                "जिन, रुको।", output.absolutePath, SeriesMemoryStore.sha256(output))
            val fence = MemoryPublicationFence { expected, commit ->
                require(expected.source == receipt.source && expected.configurationIdentity == receipt.configurationIdentity); commit()
            }
            block(root, receipt, fence)
        } finally { root.deleteRecursively() }
    }

    private fun journal(root: File) = File(root, "reader_memory/chapters/chapter.json")
    private suspend fun reject(block: suspend () -> Unit) {
        var rejected = false
        try { block() } catch (_: IllegalArgumentException) { rejected = true } catch (_: IllegalStateException) { rejected = true }
        assertTrue("Rejected before journal mutation", rejected)
    }

    @Test fun removalReindexAndFreshEditKeepMonotonicVersionAcrossColdStores() = environment { root, receipt, fence ->
        val store = SeriesMemoryStore(root, fence); store.indexBubble(receipt, receipt)
        store.correct(receipt, receipt, 0, MemoryCorrectionEdit(translated = "accepted old personal text"))
        store.removeCorrection(receipt, receipt, 1)
        val cold = SeriesMemoryStore(root, fence)
        val removed = cold.inspectChapter("chapter").bubbles.single()
        assertNull(removed.correction); assertEquals(2, removed.editRevision)
        assertEquals(receipt.originalTranslation, removed.translatedText)
        assertTrue(cold.search("accepted old personal text").hits.isEmpty())
        val refreshed = receipt.copy(generation = "new-generation")
        cold.indexBubble(refreshed, refreshed)
        val reindexed = SeriesMemoryStore(root, fence).inspectChapter("chapter").bubbles.single()
        assertNull(reindexed.correction); assertEquals(2, reindexed.editRevision)
        val saved = cold.correct(refreshed, refreshed, reindexed.editRevision, MemoryCorrectionEdit(translated = "new accepted text"))
        assertEquals(3, saved.revision); assertEquals(listOf(3), saved.revisions.map { it.revision })
        val reopened = SeriesMemoryStore(root, fence).inspectChapter("chapter").bubbles.single()
        assertEquals(3, reopened.editRevision); assertEquals(saved, reopened.correction)
        assertFalse(journal(root).readText().contains("accepted old personal text"))
    }

    @Test fun explicitRemovalOfEmptyCorrectionStillFencesOldEditorAndSearchCitation() = environment { root, receipt, fence ->
        val store = SeriesMemoryStore(root, fence); store.indexBubble(receipt, receipt)
        store.removeCorrection(receipt, receipt, 0)
        val cold = SeriesMemoryStore(root, fence); val before = journal(root).readBytes()
        val bubble = cold.inspectChapter("chapter").bubbles.single()
        assertNull(bubble.correction); assertEquals(1, bubble.editRevision)
        assertEquals(1, cold.search("Jin").hits.single().revision)
        reject { cold.correct(receipt, receipt, 0, MemoryCorrectionEdit(translated = "stale hidden editor")) }
        assertArrayEquals(before, journal(root).readBytes())
    }

    @Test fun failedRemovalCommitKeepsAcceptedTextAndClock() = environment { root, receipt, fence ->
        val store = SeriesMemoryStore(root, fence); store.indexBubble(receipt, receipt)
        val accepted = store.correct(receipt, receipt, 0, MemoryCorrectionEdit(translated = "accepted correction"))
        val before = journal(root).readBytes()
        val broken = SeriesMemoryStore(root, MemoryJournalWriter { _, _ -> throw IOException("held before atomic removal commit") }, fence)
        try { broken.removeCorrection(receipt, receipt, 1); fail("Expected fault") } catch (_: IOException) { }
        assertArrayEquals(before, journal(root).readBytes())
        val reopened = SeriesMemoryStore(root, fence).inspectChapter("chapter").bubbles.single()
        assertEquals(1, reopened.editRevision); assertEquals(accepted, reopened.correction)
    }

    @Test fun legacyRetainedCorrectionDerivesClockThenMigratesOnAcceptedMutation() = environment { root, receipt, fence ->
        val history = MemoryCorrection(receipt, listOf(MemoryCorrectionRevision(1, MemoryCorrectionEdit(translated = "first"), 1L),
            MemoryCorrectionRevision(2, MemoryCorrectionEdit(translated = "second"), 2L)))
        val json = JSONObject(String(SeriesMemoryCodec.chapter(MemoryChapterJournal("chapter", bubbles = listOf(MemoryIndexedBubble(receipt, history))))))
        json.put("version", 1); json.getJSONArray("bubbles").getJSONObject(0).remove("editRevision")
        journal(root).apply { parentFile!!.mkdirs(); writeText(json.toString()) }
        val store = SeriesMemoryStore(root, fence)
        assertEquals(2, store.inspectChapter("chapter").bubbles.single().editRevision)
        store.correct(receipt, receipt, 2, MemoryCorrectionEdit(translated = "third"))
        val migrated = JSONObject(journal(root).readText())
        assertEquals(3, migrated.getInt("version"))
        assertEquals(3, migrated.getJSONArray("bubbles").getJSONObject(0).getInt("editRevision"))
        assertEquals(3, SeriesMemoryStore(root, fence).inspectChapter("chapter").bubbles.single().editRevision)
    }

    @Test fun legacyBaselineHasClockZeroUntilFirstExplicitControl() = environment { root, receipt, fence ->
        val json = JSONObject(String(SeriesMemoryCodec.chapter(MemoryChapterJournal("chapter", bubbles = listOf(MemoryIndexedBubble(receipt))))))
        json.put("version", 1); json.getJSONArray("bubbles").getJSONObject(0).remove("editRevision")
        journal(root).apply { parentFile!!.mkdirs(); writeText(json.toString()) }
        val store = SeriesMemoryStore(root, fence)
        assertEquals(0, store.inspectChapter("chapter").bubbles.single().editRevision)
        store.removeCorrection(receipt, receipt, 0)
        assertEquals(1, SeriesMemoryStore(root, fence).inspectChapter("chapter").bubbles.single().editRevision)
    }

    @Test fun staleOnlySourceChangedOutputAndWrongPinnedOriginNeverWriteGlossary() = environment { root, receipt, _ ->
        val sourced = receipt.copy(seriesId = "series")
        val store = SeriesMemoryStore(root, MemoryPublicationFence { _, commit -> commit() })
        store.createSeries("Owned series", "series"); store.associateChapter("chapter", "series", 1); store.indexBubble(sourced, sourced)
        val profileFile = File(root, "reader_memory/series/series.json"); val before = profileFile.readBytes()
        File(receipt.source.sourcePath).writeText("replacement source bytes")
        val term = SeriesGlossaryTerm("jin", "Jin", "जिन", "hi", origin = MemoryLocation("chapter", 1))
        reject { store.upsertTerm("series", term) }; assertArrayEquals(before, profileFile.readBytes())
        val fresh = sourced.copy(source = sourced.source.copy(sourceSha256 = SeriesMemoryStore.sha256(File(receipt.source.sourcePath))), generation = "new")
        store.indexBubble(fresh, fresh)
        val output = File(receipt.outputPath!!); val validOutput = output.readBytes(); output.writeText("changed output")
        reject { store.upsertTerm("series", term) }; assertArrayEquals(before, profileFile.readBytes())
        output.writeBytes(validOutput)
        reject { store.upsertTerm("series", term.copy(originSourceSha256 = receipt.source.sourceSha256)) }
        assertArrayEquals(before, profileFile.readBytes()); assertEquals(2, store.inspectChapter("chapter").bubbles.size)
    }

    @Test fun codecRejectsNegativeClockAndDisagreementWithVisibleHistory() = environment { _, receipt, _ ->
        val correction = MemoryCorrection(receipt, listOf(MemoryCorrectionRevision(1, MemoryCorrectionEdit(translated = "accepted"), 1L)))
        val initial = String(SeriesMemoryCodec.chapter(MemoryChapterJournal("chapter", bubbles = listOf(MemoryIndexedBubble(receipt, correction)))))
        for (clock in listOf(-1, 0, 2)) {
            val json = JSONObject(initial).put("version", 2)
            json.getJSONArray("bubbles").getJSONObject(0).put("editRevision", clock)
            reject { SeriesMemoryCodec.readChapter(json.toString().toByteArray()) }
        }
    }

    @Test fun exhaustedClockCannotWrapOrChangeTheCurrentJournal() = environment { root, receipt, fence ->
        val json = JSONObject(String(SeriesMemoryCodec.chapter(MemoryChapterJournal("chapter", bubbles = listOf(MemoryIndexedBubble(receipt)))))).put("version", 2)
        json.getJSONArray("bubbles").getJSONObject(0).put("editRevision", Int.MAX_VALUE)
        journal(root).apply { parentFile!!.mkdirs(); writeText(json.toString()) }
        val store = SeriesMemoryStore(root, fence); val before = journal(root).readBytes()
        assertEquals(Int.MAX_VALUE, store.inspectChapter("chapter").bubbles.single().editRevision)
        reject { store.correct(receipt, receipt, Int.MAX_VALUE, MemoryCorrectionEdit(translated = "wrapped")) }
        reject { store.removeCorrection(receipt, receipt, Int.MAX_VALUE) }
        assertArrayEquals(before, journal(root).readBytes())
    }
}
