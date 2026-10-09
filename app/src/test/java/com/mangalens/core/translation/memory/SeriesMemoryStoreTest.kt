package com.mangalens.core.translation.memory

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class SeriesMemoryStoreTest {
    private fun environment(block: suspend (File, MemoryPublicationReceipt) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("mangalens-memory-proof").toFile()
        try {
            val source = File(root, "chapters/host001.jpg").apply { parentFile!!.mkdirs(); writeText("actual-original-source-bytes") }
            val output = File(root, "chapter_translations/task-one/page2.png").apply { parentFile!!.mkdirs(); writeText("verified-generated-output-bytes") }
            val proof = MemorySourceProof("chapter-one", 2, source.absolutePath, sha(source), 720, 9170, MemoryRegionBounds(200, 6150, 700, 6500))
            val receipt = MemoryPublicationReceipt(proof, "task-one", "generation-one", "hi", "faithful:verifiedpinA:highAccuracy",
                "Y-YEORUM! SORRY IM S0 ATE?!", "येओरुम! माफ़ करना, मुझे देर हो गई!", output.absolutePath, sha(output), seriesId = "series-one")
            block(root, receipt)
        } finally { root.deleteRecursively() }
    }
    private fun store(root: File) = SeriesMemoryStore(root, MemoryPublicationFence { _, commit -> commit() })
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private suspend fun setup(store: SeriesMemoryStore, receipt: MemoryPublicationReceipt) {
        store.createSeries("Same display title", "series-one")
        store.associateChapter(receipt.source.chapterId, "series-one", 1)
        store.indexBubble(receipt, receipt)
    }
    private suspend fun rejected(block: suspend () -> Unit) {
        var failed = false
        try { block() } catch (_: IllegalArgumentException) { failed = true } catch (_: IllegalStateException) { failed = true }
        assertTrue("Expected a source/scope/revision rejection before durable effects", failed)
    }

    @Test fun realPrivateBytesAndBothCorrectionsSurviveReopenWithOriginalReceipt() = environment { root, receipt ->
        val store = store(root); setup(store, receipt)
        val beforeSource = File(receipt.source.sourcePath).readBytes(); val beforeOutput = File(receipt.outputPath!!).readBytes()
        val saved = store.correct(receipt, receipt, 0, MemoryCorrectionEdit("Y-YEORUM! SORRY I'M SO LATE?!", "येओरुम! माफ़ करना, मुझे देर हो गई!"))
        val reopened = store(root).inspectChapter(receipt.source.chapterId)
        assertEquals(receipt, reopened.bubbles.single().correction!!.original)
        assertEquals(saved, reopened.bubbles.single().correction)
        assertEquals(MemoryChapterAssociation("chapter-one", "series-one", 1), reopened.association)
        assertArrayEquals(beforeSource, File(receipt.source.sourcePath).readBytes())
        assertArrayEquals(beforeOutput, File(receipt.outputPath).readBytes())
        assertEquals(MemorySearchKind.CORRECTED_OCR, store(root).search("LATE").hits.single().kind)
    }

    @Test fun rejectsStaleGenerationTargetStyleAndEditorRevisionWithoutOverwritingAcceptedEdit() = environment { root, receipt ->
        val store = store(root); setup(store, receipt)
        val accepted = store.correct(receipt, receipt, 0, MemoryCorrectionEdit(translated = "मुझे देर हो गई!"))
        rejected { store.correct(receipt, receipt.copy(generation = "new-generation"), 1, MemoryCorrectionEdit(translated = "stale")) }
        rejected { store.correct(receipt, receipt.copy(targetLanguage = "hi-latn"), 1, MemoryCorrectionEdit(translated = "stale")) }
        rejected { store.correct(receipt, receipt.copy(configurationIdentity = "formal:verifiedpinB"), 1, MemoryCorrectionEdit(translated = "stale")) }
        rejected { store.correct(receipt, receipt, 0, MemoryCorrectionEdit(translated = "stale editor")) }
        assertEquals(accepted, store.inspectChapter("chapter-one").bubbles.single().correction)
    }

    @Test fun rejectsSameLengthSourceAndOutputTamperingBeforeSaveAndBeforeSearch() = environment { root, receipt ->
        val store = store(root); setup(store, receipt)
        File(receipt.source.sourcePath).writeText("x".repeat(File(receipt.source.sourcePath).length().toInt()))
        rejected { store.correct(receipt, receipt, 0, MemoryCorrectionEdit(correctedOcr = "Sorry I'm so late!")) }
        assertTrue(store.search("YEORUM").hits.isEmpty())
        File(receipt.source.sourcePath).writeText("actual-original-source-bytes")
        File(receipt.outputPath!!).writeText("x".repeat(File(receipt.outputPath).length().toInt()))
        rejected { store.correct(receipt, receipt, 0, MemoryCorrectionEdit(translated = "bad replacement")) }
        assertTrue(store.search("YEORUM").hits.isEmpty())
    }

    @Test fun rollbackAddsInspectableRevisionAndExplicitRemoveErasesOnlyPersonalCorrection() = environment { root, receipt ->
        val store = store(root); setup(store, receipt)
        val first = store.correct(receipt, receipt, 0, MemoryCorrectionEdit(correctedOcr = "Sorry I'm so late!"))
        store.correct(receipt, receipt, 1, MemoryCorrectionEdit(correctedOcr = "Sorry, I'm late!"))
        val restored = store.rollback(receipt, receipt, 2, 1)
        assertEquals(3, restored.revision); assertEquals(first.edit, restored.edit); assertEquals(3, restored.revisions.size)
        val original = store.rollback(receipt, receipt, 3, 0)
        assertEquals(4, original.revision); assertEquals(MemoryCorrectionEdit(), original.edit)
        store.removeCorrection(receipt, receipt, 4)
        assertNull(store.inspectChapter("chapter-one").bubbles.single().correction)
        assertTrue(File(receipt.source.sourcePath).isFile); assertTrue(File(receipt.outputPath!!).isFile)
    }

    @Test fun failedJournalPublicationPreservesPreviousStateAndReopen() = environment { root, receipt ->
        val store = store(root); setup(store, receipt)
        val saved = store.correct(receipt, receipt, 0, MemoryCorrectionEdit(translated = "accepted correction"))
        val broken = SeriesMemoryStore(root, MemoryJournalWriter { _, _ -> throw IOException("held fault before journal commit") }, MemoryPublicationFence { _, commit -> commit() })
        var failed = false
        try { broken.correct(receipt, receipt, 1, MemoryCorrectionEdit(translated = "unpublished replacement")) } catch (_: IOException) { failed = true }
        assertTrue(failed)
        assertEquals(saved, store(root).inspectChapter("chapter-one").bubbles.single().correction)
    }

    @Test fun explicitSeriesIdentityAndOriginBoundsPreventCrossSeriesAndFutureTermReuse() = environment { root, receipt ->
        val store = store(root); setup(store, receipt)
        store.createSeries("Same display title", "series-two")
        store.upsertTerm("series-one", SeriesGlossaryTerm("yeorum", "Yeorum", "येओरुम", "hi"))
        store.upsertTerm("series-two", SeriesGlossaryTerm("yeorum", "Yeorum", "unrelated spelling", "hi"))
        rejected { store.upsertTerm("series-two", SeriesGlossaryTerm("source-foreign", "Late", "foreign correction", "hi", origin = MemoryLocation("chapter-one", 2))) }
        val later = receipt.copy(source = receipt.source.copy(chapterId = "chapter-two", pageIndex = 1), taskId = "task-two", generation = "generation-two")
        store.associateChapter("chapter-two", "series-one", 2); store.indexBubble(later, later)
        val result = store(root).relevant(MemoryRetrievalRequest("chapter-two", 1, "Yeorum, are you late?", "hi", receipt.configurationIdentity))
        assertEquals(mapOf("Yeorum" to "येओरुम"), result.glossary)
        assertEquals("chapter-one", result.priorDialogue.single().source.chapterId)
        store.removeTerm("series-one", "yeorum")
        assertTrue(store.profile("series-one")!!.glossary.isEmpty())
        assertEquals("unrelated spelling", store.profile("series-two")!!.glossary.single().preferred)
    }

    @Test fun sourceFreshnessAndTombstoneExcludeDeletedDataAndRejectLateIndexWithoutDeletingMedia() = environment { root, receipt ->
        val store = store(root); setup(store, receipt)
        assertEquals(1, store.search("YEORUM").hits.size)
        store.removeChapter("chapter-one")
        assertTrue(store(root).inspectChapter("chapter-one").removed)
        assertTrue(store(root).search("YEORUM").hits.isEmpty())
        rejected { store.indexBubble(receipt, receipt) }
        rejected { store.associateChapter("chapter-one", "series-one", 1) }
        assertTrue(File(receipt.source.sourcePath).isFile); assertTrue(File(receipt.outputPath!!).isFile)
    }

    @Test fun externalPathsAndOutOfSourceBoundsRejectBeforeAnyJournalCreation() = environment { root, receipt ->
        val store = store(root)
        val external = File(root.parentFile, "memory-external-${root.name}.jpg").apply { writeText("external") }
        try {
            val escaped = receipt.copy(source = receipt.source.copy(sourcePath = external.absolutePath, sourceSha256 = sha(external)))
            rejected { store.indexBubble(escaped, escaped) }
            val invalid = receipt.copy(source = receipt.source.copy(bounds = MemoryRegionBounds(0, 0, 721, 9170)))
            rejected { store.indexBubble(invalid, invalid) }
            assertTrue(store.inspectChapter("chapter-one").bubbles.isEmpty())
        } finally { external.delete() }
    }

    @Test fun seriesStylePreferenceAndTermRemovalAreDurableAndInspectible() = environment { root, receipt ->
        val store = store(root); setup(store, receipt)
        val style = SeriesStylePreference("custom", "hi", "Keep honorifics. Use casual dialogue between friends.")
        store.setStyle("series-one", style)
        assertEquals(style, store(root).profile("series-one")!!.style)
        store.removeSeries("series-one")
        assertNull(store(root).profile("series-one"))
        assertTrue(store.search("YEORUM").hits.all { it.seriesId == null })
        assertTrue(File(receipt.source.sourcePath).isFile)
    }
    @Test fun sourcedGlossaryRetainsAuthenticPageHashAndStopsReuseAfterItsSourceChanges() = environment { root, receipt ->
        val memory = store(root); setup(memory, receipt)
        memory.upsertTerm("series-one", SeriesGlossaryTerm("from-source", "Yeorum", "येओरुम", "hi", origin = MemoryLocation("chapter-one", 2)))
        assertEquals(receipt.source.sourceSha256, memory.profile("series-one")!!.glossary.single().originSourceSha256)
        memory.associateChapter("chapter-two", "series-one", 2)
        val request = MemoryRetrievalRequest("chapter-two", 1, "Yeorum, wait!", "hi", receipt.configurationIdentity)
        assertEquals(mapOf("Yeorum" to "येओरुम"), memory.relevant(request).glossary)
        File(receipt.source.sourcePath).writeText("changed original source")
        assertTrue(memory.relevant(request).glossary.isEmpty())
        assertEquals("Yeorum", memory.profile("series-one")!!.glossary.single().source)
    }

    @Test fun correctionTouchesOneOriginalCoordinateBubbleAndSearchRetainsBothArtifacts() = environment { root, receipt ->
        val memory = store(root); setup(memory, receipt)
        val neighbor = receipt.copy(source = receipt.source.copy(bounds = MemoryRegionBounds(30, 100, 500, 250)),
            originalOcr = "Jin, do not worry.", originalTranslation = "जिन, चिंता मत करो।")
        memory.indexBubble(neighbor, neighbor)
        memory.correct(receipt, receipt, 0, MemoryCorrectionEdit(correctedOcr = "Yeorum, sorry I'm so late!"))
        val reopened = store(root).inspectChapter("chapter-one")
        assertEquals(neighbor, reopened.bubbles.single { it.receipt.bubbleId == neighbor.bubbleId }.receipt)
        assertNull(reopened.bubbles.single { it.receipt.bubbleId == neighbor.bubbleId }.correction)
        assertEquals(neighbor.source.bounds, store(root).search("Jin").hits.single().source.bounds)
        assertEquals(receipt.source.bounds, store(root).search("late").hits.single().source.bounds)
    }

    @Test fun symlinkEscapesAndUnsupportedSchemaNeverBecomeInstalledMemoryOrDeleteOriginals() = environment { root, receipt ->
        val external = Files.createTempDirectory("external-memory").toFile()
        try {
            val managed = File(root, "reader_memory").apply { mkdirs() }
            val escape = File(managed, "series")
            Files.createSymbolicLink(escape.toPath(), external.toPath())
            rejected { store(root).createSeries("Untrusted external location", "escaped") }
            assertTrue(external.listFiles().orEmpty().isEmpty())
            escape.delete()
            val memory = store(root); setup(memory, receipt)
            val journal = File(managed, "chapters/chapter-one.json")
            val valid = journal.readBytes()
            File(journal.parentFile, journal.name + ".pending-crash").writeText("uncommitted partial")
            assertEquals(1, store(root).inspectChapter("chapter-one").bubbles.size)
            journal.writeText(JSONObject(journal.readText()).put("version", 99).toString())
            rejected { memory.inspectChapter("chapter-one") }
            assertTrue(journal.exists()); assertTrue(File(receipt.source.sourcePath).exists())
            journal.writeBytes(valid)
            assertEquals(receipt, memory.inspectChapter("chapter-one").bubbles.single().receipt)
        } finally { external.deleteRecursively() }
    }

    @Test fun excessiveRevisionAndTextRequestsFailWithoutLosingAcceptedPersonalHistory() = environment { root, receipt ->
        val memory = store(root); setup(memory, receipt)
        rejected { memory.correct(receipt, receipt, 0, MemoryCorrectionEdit(correctedOcr = "x".repeat(4097))) }
        for (revision in 0 until 32) memory.correct(receipt, receipt, revision, MemoryCorrectionEdit(translated = "user correction $revision"))
        rejected { memory.correct(receipt, receipt, 32, MemoryCorrectionEdit(translated = "unbounded correction")) }
        assertEquals(32, store(root).inspectChapter("chapter-one").bubbles.single().correction!!.revisions.size)
        rejected { memory.search("q".repeat(257)) }
        assertTrue(File(receipt.source.sourcePath).isFile)
    }

    @Test fun relinkingAChapterDoesNotMoveOldSeriesCorrectionsIntoAnotherSeries() = environment { root, receipt ->
        val memory = store(root); setup(memory, receipt)
        memory.correct(receipt, receipt, 0, MemoryCorrectionEdit(translated = "old-series-specific spelling"))
        memory.createSeries("Same display title", "series-two")
        memory.associateChapter("chapter-one", "series-two", 1)
        memory.associateChapter("chapter-later", "series-two", 2)
        val result = memory.relevant(MemoryRetrievalRequest("chapter-later", 1, "Yeorum, wait!", "hi", receipt.configurationIdentity))
        assertTrue("Changing a chapter link cannot silently reinterpret an old series correction", result.priorDialogue.isEmpty())
        assertEquals("old-series-specific spelling", memory.inspectChapter("chapter-one").bubbles.single().correction!!.edit.translated)
    }

}
