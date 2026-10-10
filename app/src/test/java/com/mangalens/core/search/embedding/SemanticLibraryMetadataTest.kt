package com.mangalens.core.search.embedding

import com.mangalens.core.reader.SavedChapter
import org.junit.Assert.*
import org.junit.Test

class SemanticLibraryMetadataTest {
    private fun chapter(id: Int = 1, title: String = "A lonely hero") = SavedChapter(id.toString(16).padStart(32, '0'), title, "https://example.com/chapter/$id", emptyList(), updatedAt = 1, addedAt = 1)
    private fun entry(value: SavedChapter) = SemanticLibraryMetadata.snapshot(listOf(value)).entries.single()
    @Test fun sameTitleDifferentSavedSourcesDoNotShareVectorKeys() { assertNotEquals(entry(chapter(1)).cacheKey, entry(chapter(2)).cacheKey) }
    @Test fun changedSourceUrlInvalidatesRowAndCacheIdentity() {
        val original = chapter(); val first = entry(original); val changed = original.copy(sourceUrl = "https://other.example/chapter")
        assertFalse(first.matchesCurrent(changed)); assertNotEquals(first.cacheKey, entry(changed).cacheKey)
    }
    @Test fun sourceIncarnationAddedAtInvalidatesIdentity() { assertNotEquals(entry(chapter()).cacheKey, entry(chapter().copy(addedAt = 2)).cacheKey) }
    @Test fun changedSeriesAndNotesInvalidateExactCurrentRows() {
        val original = chapter(); val first = entry(original)
        assertFalse(first.matchesCurrent(original.copy(seriesTitle = "Other"))); assertFalse(first.matchesCurrent(original.copy(notes = "changed")))
    }
    @Test fun textBeyondModelTruncationStillInvalidatesCache() {
        val original = chapter().copy(notes = "x".repeat(6000)); val changed = original.copy(notes = original.notes.dropLast(1) + "y")
        assertEquals(entry(original).text, entry(changed).text); assertNotEquals(entry(original).cacheKey, entry(changed).cacheKey)
    }
    @Test fun pageOrdinalChangesNeverFabricateNativeTextAuthority() { assertEquals(entry(chapter()).cacheKey, entry(chapter().copy(position = 99, scrollOffset = 3)).cacheKey) }
    @Test fun nonLatinOnlyMetadataIsHonestlyOmitted() {
        val found = SemanticLibraryMetadata.snapshot(listOf(chapter(title = "漫画")))
        assertTrue(found.entries.isEmpty()); assertEquals(1, found.omitted)
    }
    @Test fun oversizedMetadataIsOmittedWithVisibleLimit() {
        val found = SemanticLibraryMetadata.snapshot(listOf(chapter(title = "x".repeat(2049))))
        assertTrue(found.entries.isEmpty()); assertTrue(found.inventoryLimited); assertEquals(1, found.omitted)
    }
    @Test fun inventoryStopsAtActual1024Records() {
        val found = SemanticLibraryMetadata.snapshot((1..1025).map { chapter(it) })
        assertEquals(1024, found.entries.size); assertTrue(found.inventoryLimited)
    }
    @Test fun actualUtf8MetadataByteBudgetLimitsInventory() {
        val values = (1..1024).map { chapter(it, "English " + "中".repeat(2000)).copy(seriesTitle = "中".repeat(2048), notes = "中".repeat(8192), sourceUrl = "https://example.com/" + "中".repeat(8000)) }
        val found = SemanticLibraryMetadata.snapshot(values)
        assertTrue(found.entries.size < 1024); assertTrue(found.inventoryLimited); assertTrue(found.omitted > 0)
    }
    @Test fun clippedTextNeverEndsOnHalfSurrogatePair() {
        // Title + two labels leaves the final boundary inside the note emoji pair.
        val prefix = "A lonely hero\nSeries: \nNotes: "
        val value = chapter().copy(notes = "x".repeat(4095 - prefix.length) + "😀" + "rest")
        val found = entry(value)
        assertTrue(found.bodyTruncated); assertFalse(found.text.last().isHighSurrogate())
    }
    @Test fun currentRowMustBeUniqueWithinAdmittedInventory() { val value = chapter(); assertFalse(SemanticLibraryMetadata.current(listOf(value, value), entry(value))) }
    @Test fun removedAndChangedRowsFailCurrentOpenPredicate() {
        val value = chapter(); val first = entry(value)
        assertFalse(SemanticLibraryMetadata.current(emptyList(), first)); assertFalse(SemanticLibraryMetadata.current(listOf(value.copy(title = "Other")), first))
    }
    @Test fun explicitSnapshotCancellationStopsBeforeSecondRow() {
        var called = 0
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { SemanticLibraryMetadata.snapshot((1..30).map { chapter(it) }) { if (++called == 2) throw kotlinx.coroutines.CancellationException() } }
        assertEquals(2, called)
    }
    @Test fun semanticQueryRejectsOtherScriptAndControls() {
        assertThrows(IllegalArgumentException::class.java) { SemanticLibraryMetadata.query("漫画") }
        assertThrows(IllegalArgumentException::class.java) { SemanticLibraryMetadata.query("hero\nother") }
        assertEquals("a hero", SemanticLibraryMetadata.query(" a hero "))
    }
}
