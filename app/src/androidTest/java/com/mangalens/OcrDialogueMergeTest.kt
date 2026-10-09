package com.mangalens

import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mangalens.engine.AdvancedOcrTranslationEngine
import com.mangalens.engine.OcrTextBlock
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OcrDialogueMergeTest {
    @Test fun adjacentWordsKeepAllTextAndDoNotMutateInput() {
        val first = OcrTextBlock("BEATEN", RectF(100f, 100f, 180f, 120f), .9f)
        val second = OcrTextBlock("UP.", RectF(190f, 102f, 235f, 121f), .8f)
        val merged = AdvancedOcrTranslationEngine().mergeAdjacentLines(listOf(first, second))
        assertEquals(1, merged.size)
        assertEquals("BEATEN UP.", merged.single().text)
        assertEquals(RectF(100f, 100f, 235f, 121f), merged.single().bounds)
        assertEquals(.8f, merged.single().confidence, .0001f)
        assertEquals(RectF(100f, 100f, 180f, 120f), first.bounds)
    }

    @Test fun separateBubblesAndOverlappingReadingsDoNotMerge() {
        val first = OcrTextBlock("FIRST", RectF(10f, 100f, 80f, 120f))
        val distant = OcrTextBlock("SECOND", RectF(250f, 100f, 340f, 120f))
        val overlapping = first.copy(text = "OTHER", bounds = RectF(first.bounds))
        assertEquals(2, AdvancedOcrTranslationEngine().mergeAdjacentLines(listOf(first, distant)).size)
        assertEquals(2, AdvancedOcrTranslationEngine().mergeAdjacentLines(listOf(first, overlapping)).size)
    }
}
