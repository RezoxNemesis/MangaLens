package com.mangalens.core.search.embedding

import com.mangalens.core.translation.*
import com.mangalens.core.translation.memory.MemorySearchKind
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Authored only. These pure selector controls do not qualify native runtime or read authority. */
class NativeSavedDialogueSemanticCorpusTest {
    private fun letter(source: String = "The sword technique", target: String = "The sword technique") =
        SavedMangaLettering(source, target, 0, 0, 20, 20, "sans", 0, 0, 12f, "CENTER", 0, 0, 20, 20)
    private fun page(index: Int = 17, letters: List<SavedMangaLettering> = listOf(letter())) = ChapterTranslationPage(
        index, "/private/chapters/page-$index.png", "a".repeat(64), ChapterTranslationPageStatus.COMPLETED,
        "/private/chapter_translations/task/surface-$index.png", "b".repeat(64), 20, 20, letters)
    private fun task(pages: List<ChapterTranslationPage> = listOf(page())) = ChapterTranslationTask(
        "task", "generation-1", "1".repeat(32), "Saved chapter", ChapterTranslationConfig("en"), pages,
        ChapterTranslationStatus.COMPLETED, 1, 1, ownerRequestId = "owner-1")
    private fun corpus(value: ChapterTranslationTask) = NativeSavedDialogueSemanticCorpus.snapshot(listOf(value), { it.targetLanguage + ":" + it.styleId })
    private fun ocr(value: ChapterTranslationTask) = corpus(value).fields.first { it.hint.kind == MemorySearchKind.OCR }

    @Test fun sourceAndTranslationKindsHaveSeparateKeysEvenForIdenticalText() {
        val fields = corpus(task()).fields
        assertEquals(2, fields.size); assertNotEquals(fields[0].key, fields[1].key)
    }
    @Test fun sparseActualPageIndexAndLetterOrdinalArePreserved() {
        val fields = corpus(task(listOf(page(41), page(7, listOf(letter("First"), letter("Second")))))).fields
        assertEquals(listOf(41, 41, 7, 7, 7, 7), fields.map { it.hint.pageIndex })
        assertEquals(listOf(0, 0, 0, 0, 1, 1), fields.map { it.hint.letteringIndex })
    }
    @Test fun identicalRepeatedBubblesDoNotCollapseToOneCacheKey() {
        val fields = corpus(task(listOf(page(17, listOf(letter(), letter()))))).fields
        assertEquals(4, fields.map { it.key }.distinct().size)
    }
    @Test fun anotherChapterHasIndependentIdentity() { assertNotEquals(ocr(task()).key, ocr(task().copy(chapterId = "2".repeat(32))).key) }
    @Test fun taskGenerationInvalidatesCacheKey() { assertNotEquals(ocr(task()).key, ocr(task().copy(generation = "generation-2")).key) }
    @Test fun taskOwnerInvalidatesCacheKey() { assertNotEquals(ocr(task()).key, ocr(task().copy(ownerRequestId = "owner-2")).key) }
    @Test fun actualConfigurationIdentityInvalidatesCacheKey() { assertNotEquals(ocr(task()).key, ocr(task().copy(config = ChapterTranslationConfig("hi"))).key) }
    @Test fun anotherWholeChapterSourcePageInvalidatesSelectedFieldKey() {
        val original = task(listOf(page(17), page(29, emptyList())))
        val changed = original.copy(pages = original.pages.map { if (it.index == 29) it.copy(sourceSha256 = "c".repeat(64)) else it })
        assertNotEquals(ocr(original).key, ocr(changed).key)
    }
    @Test fun changedSavedOutputInvalidatesSourceIdentity() {
        assertNotEquals(ocr(task()).key, ocr(task(listOf(page().copy(cleanedSha256 = "c".repeat(64))))).key)
    }
    @Test fun textPastModelPrefixStillInvalidatesWholeTextKey() {
        val first = ocr(task(listOf(page(letters = listOf(letter("x".repeat(5000)))))))
        val changed = ocr(task(listOf(page(letters = listOf(letter("x".repeat(4999) + "y"))))))
        assertEquals(first.input, changed.input); assertNotEquals(first.textSha256, changed.textSha256); assertNotEquals(first.key, changed.key)
    }
    @Test fun modelInputPrefixNeverSplitsSurrogatePair() {
        val field = ocr(task(listOf(page(letters = listOf(letter("x".repeat(4095) + "😀tail"))))))
        assertEquals(4095, field.input.length); assertTrue(field.bodyTruncated); assertFalse(field.input.last().isHighSurrogate())
    }
    @Test fun nonLatinOnlySourceAndTranslationAreOmittedHonestly() {
        val found = corpus(task(listOf(page(letters = listOf(letter("漫画", "हिन्दी"))))))
        assertTrue(found.fields.isEmpty()); assertEquals(2, found.omitted)
    }
    @Test fun partialSavedPageRetainsActualOriginalKinds() {
        assertEquals(2, corpus(task(listOf(page().copy(status = ChapterTranslationPageStatus.PARTIAL)))).fields.size)
    }
    @Test fun failedSavedPageIsNotDialogueCorpusContent() { assertTrue(corpus(task(listOf(page().copy(status = ChapterTranslationPageStatus.FAILED)))).fields.isEmpty()) }
    @Test fun fieldInventoryCapReportsPartialCoverage() {
        val found = corpus(task((1..3).map { page(it, List(256) { letter() }) }))
        assertEquals(1024, found.fields.size); assertTrue(found.inventoryLimited)
    }
    @Test fun visitCapStopsLongNonEnglishInventory() {
        val found = corpus(task((1..10).map { page(it, List(256) { letter("漫画", "हिन्दी") }) }))
        assertEquals(4096, found.visits); assertTrue(found.inventoryLimited); assertTrue(found.fields.isEmpty())
    }
    @Test fun actualUtf8TextAndIdentityBudgetBoundsMixedLanguageInventory() {
        val found = corpus(task((1..3).map { page(it, List(256) { letter("A" + "中".repeat(7999), "B" + "中".repeat(7999)) }) }))
        assertTrue(found.receivedBytes <= NativeSavedDialogueSemanticCorpus.TEXT_BYTES); assertTrue(found.fields.size < 1024); assertTrue(found.inventoryLimited)
    }
    @Test fun cancellationRetiresBeforeBuildingFurtherFieldKeys() {
        var visited = 0
        assertThrows(CancellationException::class.java) {
            NativeSavedDialogueSemanticCorpus.snapshot(listOf(task()), { "captured-config" }) { if (++visited == 3) throw CancellationException() }
        }
        assertEquals(3, visited)
    }
    @Test fun absentWholeSourceHashOmitsReceiptWithoutPretendingAFieldCount() {
        val found = corpus(task(listOf(page().copy(sourceSha256 = null))))
        assertTrue(found.fields.isEmpty()); assertEquals(1, found.invalidTasks); assertEquals(0, found.omitted); assertTrue(found.inventoryLimited)
    }
    @Test fun fixedCorpusFilenamesKeepNativeAndMetadataVectorsSeparate() {
        assertEquals("library-vectors-v1.bin", SemanticVectorCorpus.LIBRARY.filename)
        assertEquals("dialogue-vectors-v1.bin", SemanticVectorCorpus.DIALOGUE.filename)
        assertNotEquals(SemanticVectorCorpus.LIBRARY.filename, SemanticVectorCorpus.DIALOGUE.filename)
    }
}
