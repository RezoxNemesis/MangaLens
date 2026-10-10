package com.mangalens.core.translation

import com.mangalens.core.reader.ChapterPage
import com.mangalens.core.reader.SavedChapter
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Authored UNRUN. Actual bounded files/journals; this does not pretend to execute OCR or BitmapFactory. */
class ReconstructionConfigurationCompatibilityTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("reconstruction-version").toFile()
        val sources = File(root, "chapters").apply { mkdirs() }; val journals = File(root, "translation").apply { mkdirs() }
        val source = File(sources, "original.img").apply { writeText("unchanged original bytes") }
        val chapter = SavedChapter("a".repeat(32), "Version fixture", "content://original", listOf(ChapterPage(37, "content://original", source.path)))
        val io = object : ChapterJournalIo { override fun read(file: File) = file.readBytes(); override fun write(file: File, bytes: ByteArray) { file.writeBytes(bytes) } }
        fun store() = ChapterTranslationStore(journals, sources, io) { false }
        override fun close() { root.deleteRecursively() }
    }
    @Test fun freshConfigurationCapturesVersionTwo() { assertEquals(2, ChapterReconstructionInputPolicy.newGeneration(ChapterTranslationConfig("hi")).normalized().reconstructionVersion) }
    @Test fun legacyConfigIdentityRemainsTheExactOldLengthPrefixedIdentity() {
        Fixture().use { f ->
            val c = ChapterTranslationConfig("hi", reconstructionVersion = 1)
            val identity = listOf("hi", "natural", "", "AUTO", "true", "true", "false").joinToString("|") { "${it.length}:$it" }
            val expected = MessageDigest.getInstance("SHA-256").digest((f.chapter.id + "|" + identity).toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
            assertEquals(expected, f.store().start(f.chapter, c).id)
        }
    }
    @Test fun freshVersionHasASeparateNativeIdentityAndDoesNotRelabelTheOldTask() {
        Fixture().use { f -> val store = f.store(); val old = store.start(f.chapter, ChapterTranslationConfig("hi", reconstructionVersion = 1))
            val fresh = store.start(f.chapter, ChapterTranslationConfig("hi", reconstructionVersion = 2)); assertNotEquals(old.id, fresh.id); assertEquals(1, store.get(old.id)!!.config.reconstructionVersion) }
    }
    @Test fun oldJournalWithoutVersionReopensAsOne() {
        Fixture().use { f -> val old = f.store().start(f.chapter, ChapterTranslationConfig("hi", reconstructionVersion = 1))
            val json = JSONObject(File(f.journals, old.id + ".json").readText()); assertFalse(json.getJSONObject("config").has("reconstructionVersion"))
            assertEquals(1, f.store().get(old.id)!!.config.reconstructionVersion) }
    }
    @Test fun freshJournalPersistsTwoAndItsNativeIdIsStableOnColdReopen() {
        Fixture().use { f -> val fresh = f.store().start(f.chapter, ChapterTranslationConfig("hi", reconstructionVersion = 2))
            assertEquals(2, JSONObject(File(f.journals, fresh.id + ".json").readText()).getJSONObject("config").getInt("reconstructionVersion"))
            assertEquals(fresh.id, f.store().get(fresh.id)!!.id); assertEquals(2, f.store().get(fresh.id)!!.config.reconstructionVersion) }
    }
    @Test fun legacyCacheNamespaceStaysUnchangedAndNewCacheNamespaceIsSeparate() {
        assertEquals("natural", CapturedMemoryRefinementPolicy.styleIdentity("natural", ChapterTranslationConfig("hi", reconstructionVersion = 1)))
        assertEquals("natural:glyph-reconstruction-v2", CapturedMemoryRefinementPolicy.styleIdentity("natural", ChapterTranslationConfig("hi", reconstructionVersion = 2)))
    }
    @Test fun nativeMemoryConfigurationIdentityIncludesActualReconstructionVersion() {
        Fixture().use { f -> val store = f.store(); assertNotEquals(store.memoryConfigurationIdentity(ChapterTranslationConfig("hi", reconstructionVersion = 1)), store.memoryConfigurationIdentity(ChapterTranslationConfig("hi", reconstructionVersion = 2))) }
    }
    @Test fun unsupportedVersionCannotDispatchAsKnownTwo() {
        try { ChapterTranslationConfig("hi", reconstructionVersion = 3).normalized(); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun actualJournalRoundTripsRecordedNoTextSummaryWithoutOutputOrInventedConfidence() {
        Fixture().use { f -> val store = f.store(); val task = store.start(f.chapter, ChapterTranslationConfig("hi", reconstructionVersion = 2)); store.markRunning(task.id, task.generation)
            val page = store.beginPage(task.id, task.generation, 37)!!
            val diagnostic = SavedPageOcrDiagnostics(page.sourceSha256!!, 100, 200, 2, 0, emptyList())
            assertTrue(store.commitPage(task.id, task.generation, page.copy(status = ChapterTranslationPageStatus.NO_TEXT, ocrDiagnostics = diagnostic)))
            val restored = f.store().get(task.id)!!.pages.single(); assertEquals(diagnostic, restored.ocrDiagnostics); assertNull(restored.cleanedPath); assertTrue(restored.lettering.isEmpty())
            assertEquals("unchanged original bytes", f.source.readText()) }
    }
    @Test fun diagnosticVersionMismatchIsRejectedBeforeJournalPublication() {
        Fixture().use { f -> val store = f.store(); val task = store.start(f.chapter, ChapterTranslationConfig("hi", reconstructionVersion = 2)); store.markRunning(task.id, task.generation)
            val page = store.beginPage(task.id, task.generation, 37)!!; val journal = File(f.journals, task.id + ".json").readBytes()
            val wrong = SavedPageOcrDiagnostics(page.sourceSha256!!, 100, 200, 1, 0, emptyList())
            try { store.commitPage(task.id, task.generation, page.copy(status = ChapterTranslationPageStatus.NO_TEXT, ocrDiagnostics = wrong)); fail() } catch (_: IllegalArgumentException) { }
            assertArrayEquals(journal, File(f.journals, task.id + ".json").readBytes()) }
    }
}
