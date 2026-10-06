package com.mangalens.orez

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrezCorpusWindowPlannerTest {
    @Test
    fun smallCorpusUsesOneCompleteWindow() {
        val windows = OrezCorpusWindowPlanner.plan(
            totalRows = 900,
            query = "manga translation",
            windowSize = 2_048,
            maxWindows = 6
        )
        assertEquals(listOf(OrezRowWindow(1, 900)), windows)
    }

    @Test
    fun largeCorpusStaysBoundedAndCoversHeadTailAndMiddle() {
        val windows = OrezCorpusWindowPlanner.plan(
            totalRows = 500_000,
            query = "manga translation",
            windowSize = 2_048,
            maxWindows = 6
        )
        assertTrue(windows.size <= 6)
        assertEquals(1L, windows.first().startRow)
        assertEquals(500_000L, windows.last().endRow)
        assertTrue(windows.any { it.startRow > 10_000 && it.endRow < 490_000 })
        assertTrue(windows.all { it.startRow >= 1 && it.endRow <= 500_000 && it.endRow >= it.startRow })
        assertTrue(windows.sumOf { it.endRow - it.startRow + 1 } <= 6L * 2_048L)
    }

    @Test
    fun differentQueriesProbeDifferentMiddleRegions() {
        val manga = OrezCorpusWindowPlanner.plan(1_000_000, "manga bubbles", 2_048, 6)
        val video = OrezCorpusWindowPlanner.plan(1_000_000, "video subtitles", 2_048, 6)
        assertNotEquals(manga.drop(1).dropLast(1), video.drop(1).dropLast(1))
    }
}
