package com.mangalens.orez.agent

import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.reader.SavedChapter
import com.mangalens.core.translation.ChapterJournalIo
import com.mangalens.core.translation.ChapterTranslationStore
import com.mangalens.orez.OrezTaskDao
import com.mangalens.orez.OrezTaskEntity
import com.mangalens.orez.agent.OrezChapterTools.Companion.outputs
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Actual source/native journal/plan codecs with a map DAO transport; not a Room or OCR acceptance claim. */
class OrezNativeChapterFixtureScopeTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Journal : OrezTaskDao {
        val rows = mutableMapOf<String, OrezTaskEntity>()
        override fun observeActive() = flowOf(rows.values.toList())
        override suspend fun get(id: String) = rows[id]
        override suspend fun upsert(task: OrezTaskEntity) { rows[task.id] = task }
        override suspend fun pruneFinished(before: Long) = Unit
    }

    private class Fixture(root: File) {
        val sourceDirectory = File(root, "chapters").apply { mkdirs() }
        val journals = File(root, "journals").apply { mkdirs() }
        val source = File(sourceDirectory, "accepted.img").apply { writeText("the exact captured original") }
        val chapter = SavedChapter("a".repeat(32), "Actual fixture scope", "content://selected-fixture",
            listOf(ChapterPage(1, "content://selected-fixture/1", source.absolutePath)))
        val options = OrezTranslationOptions(targetLanguage = "en", ocrScript = "LATIN", highAccuracy = false,
            localRefinement = false)
        val proposed = OrezAgentRuntime().decide("Translate this chapter into English", OrezAgentContext(
            hasActiveChapter = true, activeChapterId = chapter.id, translationOptions = options)).plan!!
        val requestId = OrezDurablePlanRules.requestId(proposed.id, 1)
        val io = object : ChapterJournalIo {
            override fun read(file: File) = file.readBytes()
            override fun write(file: File, bytes: ByteArray) { file.writeBytes(bytes) }
        }
        val native = ChapterTranslationStore(journals, sourceDirectory, io) { false }
        suspend fun inspect() = OrezChapterSourceEvidence.inspect(sourceDirectory, chapter.id, chapter.title,
            chapter.pages.map { OrezChapterSource(it.index, it.localPath) })
        fun intent(inspected: OrezChapterSnapshot) = proposed.copy(status = OrezTaskStatus.RUNNING, steps = proposed.steps.map {
            if (it.index == 0) it.copy(status = OrezStepStatus.COMPLETED, outputKind = OrezOutputKind.SAVED_CHAPTER,
                outputs = inspected.outputs(OrezDurablePlanRules.requestId(proposed.id, 0)))
            else it.copy(status = OrezStepStatus.RUNNING)
        })
        fun nativeBytes(taskId: String) = File(journals, "$taskId.json").readBytes()
    }

    @Test fun nativeOwnerAloneCannotResolveAnUncommittedInspection(): Unit = runBlocking {
        val fixture = Fixture(temporary.newFolder())
        val transport = Journal(); val store = OrezTaskStore(transport)
        val native = fixture.native.start(fixture.chapter, fixture.options.nativeChapterConfig(), ownerRequestId = fixture.requestId)
        assertEquals(fixture.requestId, native.ownerRequestId)
        assertNull(store.load(fixture.proposed.id))
        val before = fixture.nativeBytes(native.id)
        assertTrue(runCatching { OrezChapterPlanScope.expected(fixture.proposed, fixture.proposed.steps[1]) }.isFailure)
        assertArrayEquals(before, fixture.nativeBytes(native.id))
        assertEquals(native, fixture.native.get(native.id))
    }

    @Test fun inspectedIntentColdReopensAndVerifiesTheExactNativeOptionsAndSource(): Unit = runBlocking {
        val fixture = Fixture(temporary.newFolder())
        val transport = Journal(); val journal = OrezTaskStore(transport)
        val inspected = fixture.inspect()
        assertTrue(journal.checkpoint(fixture.intent(inspected)))
        val task = fixture.native.start(fixture.chapter, fixture.options.nativeChapterConfig(), ownerRequestId = fixture.requestId)
        val reopened = OrezTaskStore(transport).load(fixture.proposed.id)!!
        val scoped = OrezChapterPlanScope.expected(reopened, reopened.steps[1])
        assertEquals(inspected, scoped)
        assertEquals(fixture.options.nativeChapterConfig(), reopened.authorization!!.translation!!.nativeChapterConfig())
        val verified = OrezOwnedChapterLookup.refresh(fixture.native, task.id, fixture.requestId, task.generation) {
            OrezChapterTools.verify(OrezChapterNativeEvidence.metadataReceipt(it), scoped,
                reopened.authorization!!.translation!!, fixture.requestId)
        }!!
        val receipt = OrezChapterNativeEvidence.receipt(verified)
        assertEquals(fixture.requestId, receipt.ownerRequestId)
        assertEquals(inspected, receipt.chapter)
        val withReceipt = reopened.copy(steps = reopened.steps.map {
            if (it.index == 1) it.copy(outputs = receipt.outputs(fixture.requestId)) else it
        })
        assertTrue(OrezTaskStore(transport).checkpoint(withReceipt))
        val cold = OrezTaskStore(transport).load(reopened.id)!!
        assertEquals(task.id, cold.steps[1].outputs["translationTaskId"])
        assertEquals(task.generation, cold.steps[1].outputs["generation"])
        assertEquals(scoped.sourceFingerprint, cold.steps[1].outputs["sourceFingerprint"])
    }

    @Test fun committedOldInspectionCannotAuthorizeAChangedSourceOrReplacementOwner(): Unit = runBlocking {
        val fixture = Fixture(temporary.newFolder())
        val transport = Journal(); val journal = OrezTaskStore(transport)
        val inspected = fixture.inspect()
        assertTrue(journal.checkpoint(fixture.intent(inspected)))
        val old = fixture.native.start(fixture.chapter, fixture.options.nativeChapterConfig(), ownerRequestId = fixture.requestId)
        fixture.source.writeText("a different original after inspection")
        val changed = fixture.native.start(fixture.chapter, fixture.options.nativeChapterConfig(), ownerRequestId = fixture.requestId)
        assertNotEquals(old.generation, changed.generation)
        val before = fixture.nativeBytes(changed.id)
        assertTrue(runCatching { OrezOwnedChapterLookup.refresh(fixture.native, changed.id, fixture.requestId, changed.generation) {
            OrezChapterTools.verify(OrezChapterNativeEvidence.metadataReceipt(it), inspected, fixture.options, fixture.requestId)
        } }.isFailure)
        assertArrayEquals(before, fixture.nativeBytes(changed.id))
        val replacement = fixture.native.start(fixture.chapter, fixture.options.nativeChapterConfig(), ownerRequestId = "reader-new")
        val replacedBytes = fixture.nativeBytes(replacement.id)
        assertNull(OrezOwnedChapterLookup.refresh(fixture.native, replacement.id, fixture.requestId, replacement.generation) {
            error("A replacement owner must be rejected before scope inspection.")
        })
        assertArrayEquals(replacedBytes, fixture.nativeBytes(replacement.id))
        assertEquals(replacement, fixture.native.get(replacement.id))
    }
}
