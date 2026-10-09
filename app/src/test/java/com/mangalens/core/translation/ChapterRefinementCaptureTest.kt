package com.mangalens.core.translation

import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.reader.SavedChapter
import com.mangalens.orez.OrezModelPin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ChapterRefinementCaptureTest {
    private val pin = OrezModelPin("orez-lite-v1", "a".repeat(64), 240_000_000)
    private val request = TranslationRefinementRequest(true, TranslationStyleProfile.NATURAL.copy(
        instruction = "Preserve negation; use natural dialogue.", preserveHonorifics = false, preserveNames = true, naturalDialogue = false), pin)

    @Test fun exactStyleFlagsAndModelRemainAfterColdReopenPauseAndResume() = runBlocking {
        Fixture().use { f ->
            val store = f.store()
            val task = store.start(f.chapter, ChapterTranslationConfig("hi", localRefinement = true, refinementRequest = request), ownerRequestId = "captured:owner")
            store.pause(task.id, task.generation)
            val cold = f.store()
            val reopened = cold.refresh(task.id) ?: error("Captured journal was not readable")
            assertEquals(request, reopened.config.refinementRequest)
            assertEquals(request.style, reopened.config.style())
            val resumed = cold.resume(reopened.id, reopened.generation)!!
            assertEquals(request, resumed.config.refinementRequest)
            assertNotEquals(task.generation, resumed.generation)
            assertNull(cold.cancel(task.id, task.generation))
            assertEquals("original", f.source.readText())
        }
    }

    @Test fun modelIdentityAndStyleFlagsSeparateDurableSlots() {
        Fixture().use { f ->
            val store = f.store()
            val config = ChapterTranslationConfig("hi", localRefinement = true, refinementRequest = request)
            val first = store.start(f.chapter, config)
            val otherModel = store.start(f.chapter, config.copy(refinementRequest = request.copy(pinnedModel = pin.copy(sha256 = "b".repeat(64)))))
            val otherFlags = store.start(f.chapter, config.copy(refinementRequest = request.copy(style = request.style.copy(preserveNames = false))))
            assertNotEquals(first.id, otherModel.id)
            assertNotEquals(first.id, otherFlags.id)
            assertEquals(request, store.get(first.id)!!.config.refinementRequest)
        }
    }

    @Test fun enabledLegacyJournalNeverInventsAModelPin() = runBlocking {
        Fixture().use { f ->
            val config = ChapterTranslationConfig("hi", localRefinement = true)
            val task = f.store().start(f.chapter, config, ownerRequestId = "legacy:owner")
            val reopened = f.store().refresh(task.id)!!
            assertNull(reopened.config.refinementRequest)
            assertEquals(task.id, reopened.id)
            assertEquals(config, reopened.config)
        }
    }

    @Test fun codecRejectsMalformedPinsAndDoesNotDropExplicitFlags() {
        assertEquals(request, TranslationRefinementRequestCodec.decode(TranslationRefinementRequestCodec.encode(request)))
        val json = TranslationRefinementRequestCodec.encode(request)
        json.getJSONObject("model").put("sha256", "not-a-sha")
        try { TranslationRefinementRequestCodec.decode(json); fail("Malformed model pin was accepted") } catch (_: IllegalArgumentException) { }
        try { ChapterTranslationConfig("hi", localRefinement = false, refinementRequest = request).normalized(); fail("Disabled config accepted enabled request") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun ownedReplayUsesItsExistingPinBeforeAnyAmbientCapture() = runBlocking {
        Fixture().use { f ->
            val task = f.store().start(f.chapter, ChapterTranslationConfig("hi", localRefinement = true, refinementRequest = request), ownerRequestId = "captured:owner")
            var calls = 0
            val replay = ChapterRefinementCapturePolicy.forStart(task.config.copy(refinementRequest = null), f.chapter.id,
                task.ownerRequestId, false, listOf(task)) { calls++; request.copy(pinnedModel = pin.copy(sha256 = "b".repeat(64))) }
            assertEquals(0, calls)
            assertEquals(task.config, replay)
        }
    }

    @Test fun legacyOwnedReplayKeepsUnavailablePinAndExplicitNewRequestCapturesOnce() = runBlocking {
        Fixture().use { f ->
            val config = ChapterTranslationConfig("hi", localRefinement = true)
            val task = f.store().start(f.chapter, config, ownerRequestId = "legacy:owner")
            var calls = 0
            val replay = ChapterRefinementCapturePolicy.forStart(config, f.chapter.id, task.ownerRequestId, false, listOf(task)) { calls++; request }
            assertEquals(0, calls)
            assertNull(replay.refinementRequest)
            val fresh = ChapterRefinementCapturePolicy.forStart(config, f.chapter.id, task.ownerRequestId, true, listOf(task)) { calls++; request }
            assertEquals(1, calls)
            assertEquals(request, fresh.refinementRequest)
            val explicit = ChapterRefinementCapturePolicy.forStart(fresh, f.chapter.id, "new:owner", false, listOf(task)) { calls++; request }
            assertEquals(fresh, explicit)
            assertEquals(1, calls)
            val disabled = ChapterRefinementCapturePolicy.forStart(ChapterTranslationConfig("hi"), f.chapter.id, null, false, emptyList()) { calls++; request }
            assertNull(disabled.refinementRequest)
            assertEquals(1, calls)
        }
    }

    @Test fun generationQualifiedRefreshCannotReadAndRepairAnotherOwnersSource() = runBlocking {
        Fixture().use { f ->
            val store = f.store()
            val old = store.start(f.chapter, ChapterTranslationConfig("hi"), ownerRequestId = "old:owner")
            val current = store.start(f.chapter, ChapterTranslationConfig("hi"), ownerRequestId = "new:owner")
            val before = File(f.journals, current.id + ".json").readBytes()
            f.source.writeText("replaced source")
            assertNull(store.refresh(old.id, old.generation))
            assertEquals(current, store.get(current.id))
            assertArrayEquals(before, File(f.journals, current.id + ".json").readBytes())
            assertEquals("replaced source", f.source.readText())
        }
    }
    @Test fun formalAndCustomLetteringKeepSelectedHindiEvidenceAfterColdReopen() = runBlocking {
        for (style in listOf(TranslationStyleProfile.FORMAL, TranslationStyleProfile.custom("Keep respectful second-person Hindi.")))
            for (target in listOf("hi", "hi-latn")) Fixture().use { f ->
                val captured = request.copy(style = style)
                val config = ChapterTranslationConfig(target, style.id, if (style.id == "custom") style.instruction else "",
                    localRefinement = true, refinementRequest = captured)
                val store = f.store()
                val task = store.start(f.chapter, config)
                store.markRunning(task.id, task.generation)
                val page = store.beginPage(task.id, task.generation, 1)!!
                val source = "Give me your book."
                val hindi = "आपकी पुस्तक मुझे दीजिए।"
                val draft = if (target == "hi-latn") HinglishTranslationOutput.fromHindiDraft(source, hindi, style) else TranslationDraft(hindi)
                val selected = TranslationQualityPolicy.chooseDraft(source, draft, "", target, style)
                val output = store.createOutputFile(task.id, task.generation, 1).apply { writeText("controlled surface") }
                val lettering = SavedMangaLettering(source, selected.text, 5, 10, 90, 80, "sans-serif", 0, -16777216,
                    24f, "ALIGN_CENTER", 10, 15, 80, 70, savedHindiDraft = selected.hindiDraft)
                assertTrue(store.commitPage(task.id, task.generation, page.copy(status = ChapterTranslationPageStatus.COMPLETED,
                    cleanedPath = output.absolutePath, cleanedSha256 = ChapterTranslationStore.sha256(output), imageWidth = 100,
                    imageHeight = 200, lettering = listOf(lettering))))
                store.finish(task.id, task.generation)
                val reopened = f.store().refresh(task.id, task.generation)!!
                assertEquals(captured, reopened.config.refinementRequest)
                assertEquals(lettering, reopened.pages.single().lettering.single())
                assertTrue(TranslationQualityPolicy.isUsable(source, selected.text, target, selected.hindiDraft))
                if (target == "hi-latn") assertEquals(hindi, selected.hindiDraft) else assertEquals(hindi, selected.text)
            }
    }

    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("captured-chapter").toFile()
        val sources = File(root, "chapters").apply { mkdirs() }
        val journals = File(root, "translations").apply { mkdirs() }
        val source = File(sources, "1.img").apply { writeText("original") }
        val chapter = SavedChapter("a".repeat(32), "Fixture", "content://fixture",
            listOf(ChapterPage(1, "content://fixture", source.absolutePath)))
        private val io = object : ChapterJournalIo {
            override fun read(file: File) = file.readBytes()
            override fun write(file: File, bytes: ByteArray) { file.parentFile!!.mkdirs(); file.writeBytes(bytes) }
        }
        fun store() = ChapterTranslationStore(journals, sources, io) { true }
        override fun close() { root.deleteRecursively() }
    }
}
