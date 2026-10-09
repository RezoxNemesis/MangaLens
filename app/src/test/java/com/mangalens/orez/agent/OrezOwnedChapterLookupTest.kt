package com.mangalens.orez.agent

import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.reader.SavedChapter
import com.mangalens.core.translation.*
import com.mangalens.orez.OrezModelPin
import com.mangalens.orez.OrezTaskDao
import com.mangalens.orez.OrezTaskEntity
import com.mangalens.orez.agent.OrezChapterTools.Companion.outputs
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Real source, native journal and output validation. It does not claim OCR/refinement language quality. */
class OrezOwnedChapterLookupTest {
    @get:Rule val temporary = TemporaryFolder()
    private class Fixture(root: File) {
        val sources = File(root, "chapters").apply { mkdirs() }
        val journals = File(root, "journals").apply { mkdirs() }
        val source = File(sources, "page.img").apply { writeText("explicit original page") }
        var validations = 0
        val io = object : ChapterJournalIo {
            override fun read(file: File) = file.readBytes()
            override fun write(file: File, bytes: ByteArray) { file.writeBytes(bytes) }
        }
        val request = TranslationRefinementRequest(true, TranslationStyleProfile.FAITHFUL.copy(preserveNames = false),
            OrezModelPin("captured-model", "b".repeat(64), 2_000_000))
        val config = ChapterTranslationConfig("hi", "faithful", localRefinement = true, refinementRequest = request)
        val chapter = SavedChapter("a".repeat(32), "Saved fixture", "https://explicit.example/chapter",
            listOf(ChapterPage(1, "local:selected", source.absolutePath)))
        fun store() = ChapterTranslationStore(journals, sources, io) { file -> validations++; file.readText().startsWith("valid surface") }
        suspend fun complete(native: ChapterTranslationStore, owner: String): ChapterTranslationTask {
            val started = native.start(chapter, config, ownerRequestId = owner)
            native.markRunning(started.id, started.generation)
            if (started.pages.single().isComplete) return native.finish(started.id, started.generation)!!
            val running = native.beginPage(started.id, started.generation, 1)!!
            val output = native.createOutputFile(started.id, started.generation, 1).apply { writeText("valid surface") }
            val page = running.copy(status = ChapterTranslationPageStatus.COMPLETED, cleanedPath = output.absolutePath,
                cleanedSha256 = ChapterTranslationStore.sha256(output), imageWidth = 100, imageHeight = 200,
                lettering = listOf(SavedMangaLettering("Hello.", "नमस्ते।", 5, 10, 90, 60,
                    "sans-serif", 0, -16777216, 24f, "ALIGN_CENTER", 10, 15, 80, 45)))
            assertTrue(native.commitPage(started.id, started.generation, page))
            return native.finish(started.id, started.generation)!!
        }
        fun bytes(task: ChapterTranslationTask) = File(journals, "${task.id}.json").readBytes()
    }

    @Test fun heldOwnedScopeLookupCannotRepairReplacementOwnersCorruptSurface() = runTest {
        val f = Fixture(temporary.newFolder()); val native = f.store(); val old = f.complete(native, "orez-old")
        val held = CompletableDeferred<Unit>(); val proceed = CompletableDeferred<Unit>()
        val lookup = async { OrezOwnedChapterLookup.refresh(native, old.id, "orez-old", old.generation) {
            held.complete(Unit); proceed.await()
        } }
        held.await()
        val replacement = f.complete(native, "reader-new")
        File(replacement.pages.single().cleanedPath!!).writeText("corrupt surface")
        val before = f.bytes(replacement); val checks = f.validations
        proceed.complete(Unit)
        assertNull(lookup.await())
        assertArrayEquals(before, f.bytes(replacement))
        assertEquals(replacement, native.get(replacement.id))
        assertEquals(checks, f.validations)
    }

    @Test fun staleGenerationAndForeignOwnerAreRejectedBeforeAnyInspection() = runTest {
        val f = Fixture(temporary.newFolder()); val native = f.store(); val old = f.complete(native, "orez-old")
        val replacement = native.start(f.chapter, f.config, ownerRequestId = "orez-old") // exact replay retains generation
        val rotated = native.start(f.chapter, f.config, ownerRequestId = "orez-old", forceReprocess = true)
        val before = f.bytes(rotated)
        assertNull(OrezOwnedChapterLookup.refresh(native, old.id, "orez-old", old.generation) { error("Old generation must not inspect.") })
        assertNull(OrezOwnedChapterLookup.refresh(native, old.id, "reader-other", rotated.generation) { error("Another owner must not inspect.") })
        assertArrayEquals(before, f.bytes(rotated)); assertEquals(old.generation, replacement.generation)
    }

    @Test fun changedCapturedPinOrStyleIsRejectedBeforeRefreshingOwnCorruptOutput() = runTest {
        val f = Fixture(temporary.newFolder()); val native = f.store(); val task = f.complete(native, "orez-owner")
        val expected = OrezChapterNativeEvidence.receipt(task)
        File(task.pages.single().cleanedPath!!).writeText("corrupt surface")
        val before = f.bytes(task)
        val wrong = expected.options.copy(refinementRequest = f.request.copy(pinnedModel = f.request.pinnedModel!!.copy(sha256 = "d".repeat(64))))
        assertTrue(runCatching { OrezOwnedChapterLookup.refresh(native, task.id, "orez-owner", task.generation) {
            OrezChapterTools.verify(OrezChapterNativeEvidence.metadataReceipt(it), expected.chapter, wrong, "orez-owner")
        } }.isFailure)
        assertArrayEquals(before, f.bytes(task)); assertEquals(task, native.get(task.id))
    }

    @Test fun coldReopenPreservesFullRequestAndValidatesOnlyTheCapturedGeneration() = runTest {
        val f = Fixture(temporary.newFolder()); val native = f.store(); val task = f.complete(native, "orez-owner")
        val expected = OrezChapterNativeEvidence.receipt(task); val cold = f.store()
        assertTrue(cold.get(task.id)!!.validationPending)
        val verified = OrezOwnedChapterLookup.refresh(cold, task.id, "orez-owner", task.generation) {
            OrezChapterTools.verify(OrezChapterNativeEvidence.metadataReceipt(it), expected.chapter, expected.options, "orez-owner")
        }!!
        assertFalse(verified.validationPending)
        val receipt = OrezChapterNativeEvidence.receipt(verified)
        assertEquals(f.request, receipt.options.refinementRequest)
        assertEquals(expected, receipt)
        assertNotNull(receipt.options.refinementRequestFingerprint())
    }

    @Test fun currentOwnedCorruptSurfaceIsActuallyValidatedAndCannotClaimCompletion() = runTest {
        val f = Fixture(temporary.newFolder()); val native = f.store(); val task = f.complete(native, "orez-owner")
        val expected = OrezChapterNativeEvidence.receipt(task)
        File(task.pages.single().cleanedPath!!).writeText("corrupt surface")
        val verified = OrezOwnedChapterLookup.refresh(native, task.id, "orez-owner", task.generation) {
            OrezChapterTools.verify(OrezChapterNativeEvidence.metadataReceipt(it), expected.chapter, expected.options, "orez-owner")
        }!!
        assertEquals(ChapterTranslationStatus.FAILED, verified.status)
        assertEquals(0, verified.completedPages)
    }

    @Test fun actualNativeFullRequestReceiptReopensTypedPlanAndRejectsChangedPinProvenance() = runTest {
        val f = Fixture(temporary.newFolder()); val native = f.store()
        val initial = OrezAgentRuntime().decide("Translate this chapter into Hindi", OrezAgentContext(hasActiveChapter = true,
            activeChapterId = f.chapter.id, translationOptions = f.config.orezChapterOptions())).plan!!
        val task = f.complete(native, OrezDurablePlanRules.requestId(initial.id, 1))
        val receipt = OrezChapterNativeEvidence.receipt(task)
        val completed = initial.copy(status = OrezTaskStatus.COMPLETED, steps = initial.steps.map { step ->
            step.copy(status = OrezStepStatus.COMPLETED, outputKind = OrezDurablePlanRules.outputKind(step.call.name),
                outputs = if (step.index == 0) receipt.chapter.outputs(OrezDurablePlanRules.requestId(initial.id, 0)) else
                    receipt.outputs(OrezDurablePlanRules.requestId(initial.id, 1)) + ("destination" to "chapter-translation:${task.id}"))
        })
        val journal = object : OrezTaskDao {
            var row: OrezTaskEntity? = null
            override fun observeActive() = flowOf(listOfNotNull(row))
            override suspend fun get(id: String) = row?.takeIf { it.id == id }
            override suspend fun upsert(task: OrezTaskEntity) { row = task }
            override suspend fun pruneFinished(before: Long) = Unit
        }
        assertTrue(OrezTaskStore(journal).checkpoint(completed))
        val reopened = OrezTaskStore(journal).load(initial.id)!!
        assertEquals(f.request, reopened.authorization!!.translation!!.refinementRequest)
        assertEquals(f.request.style.preserveNames, reopened.authorization!!.translation!!.refinementRequest!!.style.preserveNames)
        assertEquals(receipt.options.refinementRequestFingerprint(), reopened.steps[1].outputs["refinementRequestFingerprint"])
        val changed = reopened.copy(authorization = reopened.authorization!!.copy(translation = receipt.options.copy(
            refinementRequest = f.request.copy(pinnedModel = f.request.pinnedModel!!.copy(sha256 = "e".repeat(64))))))
        assertTrue(runCatching { OrezDurablePlanRules.validate(changed) }.isFailure)
        assertEquals(task, native.get(task.id))
    }
}
