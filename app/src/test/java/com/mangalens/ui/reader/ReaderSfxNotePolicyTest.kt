package com.mangalens.ui.reader

import com.mangalens.core.translation.memory.MemorySfxPresentation
import org.junit.Assert.*
import org.junit.Test

/** Authored UNRUN. Reading metadata only, never an engine result, crop or mutation authority. */
class ReaderSfxNotePolicyTest {
    private fun row(index: Int = 0) = ReaderSfxNoteRow(index, MemorySfxPresentation.ALONGSIDE, "धम!", null,
        ReaderSfxOriginalReadiness.UNAVAILABLE)
    @Test fun offscreenAndPrefetchedPagesDoNotPopulateReaderNotes() {
        val selected = ReaderSfxNotePolicy.visible(setOf(37), listOf(ReaderSfxNoteGroup(12, listOf(row())), ReaderSfxNoteGroup(37, listOf(row(6)))))
        assertEquals(listOf(37), selected.map { it.pageIndex }); assertEquals(6, selected.single().rows.single().nativeIndex)
    }
    @Test fun spreadHasAtMostTwoActualVisiblePagesWithTheirOriginalIndices() {
        val groups = listOf(91, 37, 4).map { ReaderSfxNoteGroup(it, listOf(row(it))) }
        val selected = ReaderSfxNotePolicy.visible(linkedSetOf(37, 91, 4), groups)
        assertEquals(listOf(37, 91), selected.map { it.pageIndex }); assertEquals(listOf(37, 91), selected.map { it.rows.single().nativeIndex })
    }
    @Test fun latestCurrentCompositionReplacesTheEarlierPageNoteSnapshot() {
        val first = ReaderSfxNoteGroup(37, listOf(row().copy(translated = "old")))
        val second = ReaderSfxNoteGroup(37, listOf(row().copy(translated = "current")))
        assertEquals("current", ReaderSfxNotePolicy.visible(setOf(37), listOf(first, second)).single().rows.single().translated)
    }
    @Test fun rowAndTextBudgetsRemainBoundedWithoutChangingTheInput() {
        val input = ReaderSfxNoteGroup(37, (0..7).map { row(it).copy(translated = "x".repeat(700), annotation = "y".repeat(400)) }, 3)
        val selected = ReaderSfxNotePolicy.visible(setOf(37), listOf(input)).single()
        assertEquals(4, selected.rows.size); assertEquals(7, selected.overflow)
        assertEquals(512, selected.rows[0].translated.length); assertEquals(256, selected.rows[0].annotation!!.length)
        assertEquals(700, input.rows[0].translated.length); assertEquals(8, input.rows.size)
    }
    @Test fun negativeOrEmptyVisibilityCannotKeepAnEarlierNote() {
        val group = ReaderSfxNoteGroup(37, listOf(row()))
        assertTrue(ReaderSfxNotePolicy.visible(emptySet(), listOf(group)).isEmpty())
        assertTrue(ReaderSfxNotePolicy.visible(setOf(-1), listOf(group)).isEmpty())
        assertTrue(ReaderSfxNotePolicy.visible(setOf(37), listOf(group.copy(rows = emptyList()))).isEmpty())
    }
    @Test fun readinessAndPolicyAreDisplayMetadataRatherThanClaimsOfNewOutput() {
        for (readiness in ReaderSfxOriginalReadiness.entries) {
            val input = row().copy(original = readiness, presentation = MemorySfxPresentation.ANNOTATE, annotation = "Personal note")
            assertEquals(input, ReaderSfxNotePolicy.visible(setOf(37), listOf(ReaderSfxNoteGroup(37, listOf(input)))).single().rows.single())
        }
    }
    @Test fun overflowCannotWrapIntoANegativeCount() {
        val group = ReaderSfxNoteGroup(37, (0..7).map(::row), Int.MAX_VALUE)
        assertEquals(256, ReaderSfxNotePolicy.visible(setOf(37), listOf(group)).single().overflow)
    }
}
