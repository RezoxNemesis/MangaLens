package com.mangalens.core.translation

import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.reader.SavedChapter
import com.mangalens.core.translation.memory.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Actual native and memory journals; textual surface predicate is not an OCR/translation-quality oracle. */
class NativeGenerationMemoryCaptureTest {
    @Test fun nativeCapturedGlossaryAndEarlierReceiptEnterActualPromptAndCacheNamespace() = fixture { f, store, memory, _, selected ->
        val captured = NativeGenerationMemoryCapture(memory, store).capture(selected, f.config, 0)!!
        assertEquals(2, captured.association.ordinal); assertEquals(1L, captured.associationRevision)
        assertEquals(mapOf("Jin" to "जिन"), captured.relevant(0, "Hello Jin, friend.").glossary)
        assertEquals("नमस्ते।", captured.relevant(0, "Hello Jin, friend.").priorDialogue.single().text)
        val inputs = CapturedMemoryRefinementPolicy.inputs(captured, 0, "Hello Jin, friend.", "नमस्ते, मित्र।", "hi", TranslationRefinementRequest(true, pinnedModel = com.mangalens.orez.OrezModelPin("fixture", "a".repeat(64), 100), inputProfileRevision = "orez-localization-v2"), "")
        val prompt = TranslationRefinementPolicy.prompt("Hello Jin, friend.", "नमस्ते, मित्र।", "hi", f.config.style(), inputs.chapterContext, inputs.glossary, inputs.packetSha256)
        assertTrue(prompt.contains("Jin => जिन")); assertTrue(prompt.contains("नमस्ते।")); assertTrue(prompt.contains(captured.sha256))
        assertTrue(CapturedMemoryRefinementPolicy.cacheStyle("style", captured, 0, "Hello Jin, friend.", "").contains(captured.sha256))
    }

    @Test fun queuedPacketSurvivesLaterTermEditAndExactSeriesRelinkWhileNewCaptureChanges() = fixture { f, store, memory, _, selected ->
        val capture = NativeGenerationMemoryCapture(memory, store)
        val packet = capture.capture(selected, f.config, 0)!!
        val queued = store.start(selected, f.config.copy(memoryPacket = packet), ownerRequestId = "packet:queued")
        memory.upsertTerm("series", SeriesGlossaryTerm("term", "Jin", "जिन वू", "hi"))
        memory.associateChapter(selected.id, "series", 2)
        val fresh = capture.capture(selected, f.config, 0)!!
        assertNotEquals(packet.sha256, fresh.sha256); assertEquals(2L, fresh.associationRevision)
        assertEquals(mapOf("Jin" to "जिन"), packet.relevant(0, "Hello Jin, friend.").glossary)
        assertEquals(mapOf("Jin" to "जिन वू"), fresh.relevant(0, "Hello Jin, friend.").glossary)
        val cold = f.store().refresh(queued.id, queued.generation)!!
        assertEquals(packet, cold.config.memoryPacket); assertEquals(packet.sha256, cold.config.memoryPacket!!.sha256)
        assertEquals(f.config.copy(memoryPacket = packet), cold.config)
    }

    @Test fun sourceReplacementBetweenCaptureAndNativeStartRejectsTheOldPacket() = fixture { f, store, memory, _, selected ->
        val packet = NativeGenerationMemoryCapture(memory, store).capture(selected, f.config, 0)!!
        val source = File(selected.pages.single().localPath!!)
        source.writeBytes(ByteArray(source.length().toInt()) { '#'.code.toByte() })
        assertTrue(runCatching { store.start(selected, f.config.copy(memoryPacket = packet)) }.isFailure)
        assertTrue(store.states.value.none { it.chapterId == selected.id })
    }

    @Test fun changedCapturedStyleOrModelCannotDispatchAPacketOwnedByAnotherBaseConfiguration() = fixture { f, store, memory, _, selected ->
        val packet = NativeGenerationMemoryCapture(memory, store).capture(selected, f.config, 0)!!
        val changedStyle = f.config.copy(styleId = "formal", memoryPacket = packet)
        val changedModel = f.config.copy(localRefinement = true,
            refinementRequest = TranslationRefinementRequest(true, pinnedModel = com.mangalens.orez.OrezModelPin("other", "f".repeat(64), 100)),
            memoryPacket = packet)
        assertEquals("formal", changedStyle.normalized().styleId)
        assertEquals("other", changedModel.normalized().refinementRequest!!.pinnedModel!!.modelId)
        assertTrue(runCatching { store.start(selected, changedStyle) }.isFailure)
        assertTrue(runCatching { store.start(selected, changedModel) }.isFailure)
        assertTrue(store.states.value.none { it.chapterId == selected.id })
    }

    @Test fun removedOrReplacedNativeOwnerGenerationDoesNotBecomeDialogue() = fixture { f, store, memory, task, selected ->
        f.completed(nativeStore = store, owner = "reader:replacement", forceReprocess = true)
        val capture = NativeGenerationMemoryCapture(memory, store)
        assertTrue(capture.capture(selected, f.config, 0)!!.relevant(0, "Hello Jin, friend.").priorDialogue.isEmpty())
        val removal = store.beginChapterRemoval(task.chapterId); store.finishChapterRemoval(removal)
        assertTrue(capture.capture(selected, f.config, 0)!!.relevant(0, "Hello Jin, friend.").priorDialogue.isEmpty())
    }

    @Test fun replacedSourceOutputAndConfigurationCannotBecomeCapturedDialogue() = runBlocking {
        for (change in listOf("source", "output", "config")) fixture { f, store, memory, task, selected ->
            when (change) {
                "source" -> f.source.writeBytes(ByteArray(f.source.length().toInt()) { '#'.code.toByte() })
                "output" -> File(task.pages.single().cleanedPath!!).let { output -> output.writeText(output.readText().replace("saved", "SAVED")) }
            }
            val configuration = if (change == "config") f.config.copy(highAccuracy = false) else f.config
            assertTrue(change, NativeGenerationMemoryCapture(memory, store).capture(selected, configuration, 0)!!
                .relevant(0, "Hello Jin, friend.").priorDialogue.isEmpty())
        }
    }

    @Test fun unknownOrderAndRemovedSeriesFailClosedForCrossChapterGeneration() = fixture { f, store, memory, _, selected ->
        memory.associateChapter(selected.id, "series", null)
        val capture = NativeGenerationMemoryCapture(memory, store)
        assertTrue(capture.capture(selected, f.config, 0)!!.relevant(0, "Hello Jin, friend.").priorDialogue.isEmpty())
        memory.removeSeries("series")
        assertNull(capture.capture(selected, f.config, 0))
    }

    @Test fun coldPendingNativeTaskIsOmittedWithoutRefreshingItsCompletionOrJournal() = fixture { f, _, memory, task, selected ->
        val before = f.journal(task.id).readBytes()
        val cold = f.store()
        assertTrue(cold.get(task.id)!!.validationPending)
        val packet = NativeGenerationMemoryCapture(memory, cold).capture(selected, f.config, 0)!!
        assertTrue(packet.relevant(0, "Hello Jin, friend.").priorDialogue.isEmpty())
        assertTrue(cold.get(task.id)!!.validationPending)
        assertArrayEquals(before, f.journal(task.id).readBytes())
    }

    @Test fun staleLargeUnrelatedPageIsNotValidatedAndCannotRewriteTheNativeJournalDuringCapture() = fixture { f, store, memory, _, selected ->
        val unrelated = File(f.sources, "unrelated-page.jpg").apply { writeText("unrelated original source bytes") }
        val chapter = f.chapter.copy(pages = f.chapter.pages + ChapterPage(1, "local:unrelated", unrelated.path))
        var task = store.start(chapter, f.config, ownerRequestId = "reader:two-pages")
        store.markRunning(task.id, task.generation)
        val pending = store.beginPage(task.id, task.generation, 1)!!
        val output = store.createOutputFile(task.id, task.generation, 1).apply { writeText("valid surface: unrelated saved paper") }
        val prototype = task.pages.single { it.index == 0 }
        assertTrue(store.commitPage(task.id, task.generation, prototype.copy(index = 1, sourcePath = pending.sourcePath,
            sourceSha256 = pending.sourceSha256, cleanedPath = output.path, cleanedSha256 = ChapterTranslationStore.sha256(output))))
        task = store.finish(task.id, task.generation)!!
        val reader = ReaderMemoryPublicationAuthority(); val selectedReceipt = reader.activate(ReaderTranslationPresentation.receipt(task))
        assertNotNull(NativeMemoryPublicationAdapter(f.root, store, reader).openEditor(selectedReceipt, 0, 0))
        val before = f.journal(task.id).readBytes()
        java.io.RandomAccessFile(unrelated, "rw").use { it.setLength(96L * 1024 * 1024) }
        val packet = NativeGenerationMemoryCapture(memory, store).capture(selected, f.config, 0)!!
        assertEquals("नमस्ते।", packet.relevant(0, "Hello Jin, friend.").priorDialogue.single().text)
        assertArrayEquals("A memory read must not validate/rewrite an unrelated page", before, f.journal(task.id).readBytes())
        assertEquals(task, store.get(task.id))
    }

    @Test fun additionalSelectedSourceScopeBeyondTheMemoryIoBudgetOmitsPacketWithoutChangingNativeData() = fixture { f, store, memory, task, selected ->
        // Every page stays below the native 40MiB source cap; their combined extra memory read exceeds64MiB.
        val pages = (1..3).map { index ->
            val large = File(f.sources, "selected-large-$index.jpg").apply {
                java.io.RandomAccessFile(this, "rw").use { it.setLength(24L * 1024 * 1024) }
            }
            ChapterPage(index, "local:large:$index", large.path)
        }
        val oversized = selected.copy(pages = selected.pages + pages)
        val before = f.journal(task.id).readBytes()
        assertNull(NativeGenerationMemoryCapture(memory, store).capture(oversized, f.config, 0))
        assertArrayEquals(before, f.journal(task.id).readBytes())
    }

    @Test fun repeatedRejectedBubblesOnOnePageProbeTheActualNativePageOnlyOnce() = fixture { f, store, memory, _, selected ->
        val previous = store.states.value.single()
        var task = store.start(f.chapter, f.config, ownerRequestId = "reader:two-bubbles", forceReprocess = true)
        store.markRunning(task.id, task.generation)
        val pending = store.beginPage(task.id, task.generation, 0)!!
        val output = store.createOutputFile(task.id, task.generation, 0).apply { writeText("valid surface: two saved bubbles") }
        val prototype = previous.pages.single()
        val first = prototype.lettering.single()
        val second = first.copy(source = "Hello, friend.", sourceLeft = 25, sourceRight = 47,
            originalSourceBounds = OriginalMangaGeometry.fromSampled(25, 2, 47, 47, 250, 333, 1001, 1009))
        assertTrue(store.commitPage(task.id, task.generation, prototype.copy(sourcePath = pending.sourcePath, sourceSha256 = pending.sourceSha256,
            cleanedPath = output.path, cleanedSha256 = ChapterTranslationStore.sha256(output), lettering = listOf(first, second))))
        task = store.finish(task.id, task.generation)!!
        val reader = ReaderMemoryPublicationAuthority(); val selectedReceipt = reader.activate(ReaderTranslationPresentation.receipt(task))
        val adapter = NativeMemoryPublicationAdapter(f.root, store, reader)
        assertNotNull(adapter.openEditor(selectedReceipt, 0, 0)); assertNotNull(adapter.openEditor(selectedReceipt, 0, 1))
        assertEquals(2, memory.inspectChapter(f.chapter.id).bubbles.size)
        output.writeText("invalid replaced output")
        var probes = 0
        val capture = NativeGenerationMemoryCapture(memory, store) { receipt, pageIndex ->
            probes++; store.prepareMemoryPublication(receipt, pageIndex)
        }
        assertTrue(capture.capture(selected, f.config, 0)!!.relevant(0, "Hello Jin, friend.").priorDialogue.isEmpty())
        assertEquals("Failed native proof is cached too", 1, probes)
    }

    @Test fun atomicSourceReplacementBetweenSizeAdmissionAndRealHashCannotEscapeTheArtifactBudget() = fixture { f, store, memory, task, selected ->
        val pages = (1..2).map { index ->
            val admitted = File(f.sources, "selected-admitted-$index.jpg").apply {
                java.io.RandomAccessFile(this, "rw").use { it.setLength(24L * 1024 * 1024) }
            }
            ChapterPage(index, "local:admitted:$index", admitted.path)
        }
        val chapter = selected.copy(pages = selected.pages + pages)
        val before = f.journal(task.id).readBytes()
        var replaced = false
        val capture = NativeGenerationMemoryCapture(memory, store, hashSelectedSource = { file ->
            if (file.name == "selected-admitted-1.jpg") {
                val replacement = File(file.parentFile, "larger-atomic-inode.jpg").apply {
                    java.io.RandomAccessFile(this, "rw").use { it.setLength(40L * 1024 * 1024) }
                }
                java.nio.file.Files.move(replacement.toPath(), file.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                replaced = true
            }
            ChapterTranslationStore.sha256(file)
        })
        assertNull(capture.capture(chapter, f.config, 0))
        assertTrue("The held real hash must encounter the atomic replacement", replaced)
        assertArrayEquals(before, f.journal(task.id).readBytes())
    }

    private fun fixture(block: suspend (NativeMemoryPublicationAdapterTest.Fixture, ChapterTranslationStore, SeriesMemoryStore, ChapterTranslationTask, SavedChapter) -> Unit) = runBlocking {
        NativeMemoryPublicationAdapterTest.Fixture().use { f ->
            val store = f.store(); val task = f.completed(nativeStore = store)
            val selectedSource = File(f.sources, "selected-page.jpg").apply { writeBytes(f.source.readBytes()) }
            val selected = SavedChapter("b".repeat(32), "Selected fixture", "local:next", listOf(ChapterPage(0, "local:next", selectedSource.path)))
            val authority = ReaderMemoryPublicationAuthority(); val chosen = authority.activate(ReaderTranslationPresentation.receipt(task))
            val adapter = NativeMemoryPublicationAdapter(f.root, store, authority)
            adapter.memory.createSeries("Explicit fixture", "series")
            adapter.memory.associateChapter(f.chapter.id, "series", 1); adapter.memory.associateChapter(selected.id, "series", 2)
            adapter.memory.upsertTerm("series", SeriesGlossaryTerm("term", "Jin", "जिन", "hi"))
            assertNotNull(adapter.openEditor(chosen, 0, 0))
            block(f, store, adapter.memory, task, selected)
        }
    }
}
