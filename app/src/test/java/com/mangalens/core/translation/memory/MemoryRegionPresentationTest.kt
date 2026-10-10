package com.mangalens.core.translation.memory

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Authored UNRUN. Real private byte/journal checks; the publication fence is a test-owned synchronous fence. */
class MemoryRegionPresentationTest {
    private fun fixture(block: suspend (SeriesMemoryStore, File, MemoryPublicationReceipt) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("personal-sfx").toFile()
        try {
            val source = File(root, "chapters/original.png").apply { parentFile!!.mkdirs(); writeText("original byte fixture") }
            val output = File(root, "chapter_translations/output.png").apply { parentFile!!.mkdirs(); writeText("native surface byte fixture") }
            fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            val receipt = MemoryPublicationReceipt(MemorySourceProof("chapter", 37, source.path, sha(source), 100, 200,
                MemoryRegionBounds(10, 20, 80, 90)), "task", "G1", "hi", "captured-config", "Bang!", "धम!", output.path, sha(output))
            val store = SeriesMemoryStore(root, MemoryPublicationFence { _, commit -> commit() })
            store.indexBubble(receipt, receipt)
            block(store, root, receipt)
        } finally { root.deleteRecursively() }
    }
    @Test fun everyExplicitSfxPolicyAcceptsOnlyItsManualSfxKind() {
        for (mode in MemorySfxPresentation.entries) MemoryRegionPresentation(MemoryUserRegionKind.SFX, mode).validate()
        assertTrue(runCatching { MemoryRegionPresentation(MemoryUserRegionKind.DIALOGUE, MemorySfxPresentation.REPLACE).validate() }.isFailure)
    }
    @Test fun anSfxLabelWithoutAnExplicitPolicyCannotBeSaved() {
        assertTrue(runCatching { MemoryRegionPresentation(MemoryUserRegionKind.SFX).validate() }.isFailure)
    }
    @Test fun annotationsAreBoundedAndNotSilentlyUsedForOtherPolicies() {
        MemoryRegionPresentation(MemoryUserRegionKind.SFX, MemorySfxPresentation.ANNOTATE, "A crash sound").validate()
        for (text in listOf("", " ", "x".repeat(257), "bad\u0000note")) assertTrue(runCatching {
            MemoryRegionPresentation(MemoryUserRegionKind.SFX, MemorySfxPresentation.ANNOTATE, text).validate() }.isFailure)
        assertTrue(runCatching { MemoryRegionPresentation(MemoryUserRegionKind.SFX, MemorySfxPresentation.KEEP_ORIGINAL, "note").validate() }.isFailure)
    }
    @Test fun manualPolicySurvivesRealColdJournalReopenWithoutChangingSourceOrOutput() = fixture { store, root, receipt ->
        val source = File(receipt.source.sourcePath).readBytes(); val output = File(receipt.outputPath!!).readBytes()
        val edit = MemoryCorrectionEdit(regionPresentation = MemoryRegionPresentation(MemoryUserRegionKind.SFX, MemorySfxPresentation.ANNOTATE, "Sudden impact"))
        store.correct(receipt, receipt, 0, edit)
        val reopened = SeriesMemoryStore(root, MemoryPublicationFence { _, commit -> commit() }).inspectChapter("chapter").bubbles.single()
        assertEquals(edit, reopened.correction!!.edit); assertEquals(receipt, reopened.correction!!.original)
        assertArrayEquals(source, File(receipt.source.sourcePath).readBytes()); assertArrayEquals(output, File(receipt.outputPath).readBytes())
    }
    @Test fun rollbackAndRemovalApplyToPersonalPresentationAlongWithText() = fixture { store, _, receipt ->
        val first = MemoryCorrectionEdit(regionPresentation = MemoryRegionPresentation(MemoryUserRegionKind.SFX, MemorySfxPresentation.KEEP_ORIGINAL))
        store.correct(receipt, receipt, 0, first)
        store.correct(receipt, receipt, 1, first.copy(regionPresentation = MemoryRegionPresentation(MemoryUserRegionKind.NARRATION)))
        assertEquals(first, store.rollback(receipt, receipt, 2, 1).edit)
        assertEquals(MemoryCorrectionEdit(), store.rollback(receipt, receipt, 3, 0).edit)
        store.removeCorrection(receipt, receipt, 4)
        assertNull(store.inspectChapter("chapter").bubbles.single().correction)
    }
    @Test fun staleRevisionCannotOverwriteANewerPersonalSfxSelection() = fixture { store, _, receipt ->
        val first = MemoryCorrectionEdit(regionPresentation = MemoryRegionPresentation(MemoryUserRegionKind.SFX, MemorySfxPresentation.KEEP_ORIGINAL))
        store.correct(receipt, receipt, 0, first)
        assertTrue(runCatching { store.correct(receipt, receipt, 0, first.copy(regionPresentation = MemoryRegionPresentation(MemoryUserRegionKind.NARRATION))) }.isFailure)
        assertEquals(first, store.inspectChapter("chapter").bubbles.single().correction!!.edit)
    }
    @Test fun actualCodecKeepsOrdinaryJournalSchemaAndNoSyntheticClassification() = fixture { store, _, _ ->
        val snapshot = store.inspectChapter("chapter")
        val bytes = SeriesMemoryCodec.chapter(MemoryChapterJournal(snapshot.chapterId, snapshot.association, snapshot.bubbles, snapshot.removed, snapshot.associationRevision))
        val json = JSONObject(bytes.toString(Charsets.UTF_8)); assertEquals(3, json.getInt("version")); assertFalse(json.toString().contains("userRegion"))
        assertNull(SeriesMemoryCodec.readChapter(bytes).bubbles.single().correction)
    }
    @Test fun codecMakesPersonalPolicySchemaExplicitAndRejectsUnknownKind() = fixture { store, _, receipt ->
        store.correct(receipt, receipt, 0, MemoryCorrectionEdit(regionPresentation = MemoryRegionPresentation(MemoryUserRegionKind.SFX, MemorySfxPresentation.ALONGSIDE)))
        val snapshot = store.inspectChapter("chapter")
        val json = JSONObject(SeriesMemoryCodec.chapter(MemoryChapterJournal(snapshot.chapterId, snapshot.association, snapshot.bubbles, snapshot.removed, snapshot.associationRevision)).toString(Charsets.UTF_8))
        assertEquals(4, json.getInt("version"))
        json.getJSONArray("bubbles").getJSONObject(0).getJSONObject("correction").getJSONArray("revisions").getJSONObject(0).getJSONObject("userRegion").put("kind", "MODEL_CONFIDENT_DIALOGUE")
        assertTrue(runCatching { SeriesMemoryCodec.readChapter(json.toString().toByteArray()) }.isFailure)
    }
}
