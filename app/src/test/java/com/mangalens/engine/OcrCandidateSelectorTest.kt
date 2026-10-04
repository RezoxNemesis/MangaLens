package com.mangalens.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrCandidateSelectorTest {
    @Test
    fun autoSelectionDoesNotRewardLongWrongScriptHallucination() {
        val candidates = listOf(
            OcrCandidateSelector.Candidate(
                script = "LATIN",
                texts = listOf("THE SECOND SEMESTER", "OF MY FOURTH YEAR."),
                confidences = listOf(.91f, .87f)
            ),
            OcrCandidateSelector.Candidate(
                script = "DEVANAGARI",
                texts = listOf("अबसलातोय रोथियायलोरस योजना", "अंतरंग साथ योजना है"),
                confidences = listOf(.24f, .21f)
            )
        )

        val choice = OcrCandidateSelector.choose(candidates)
        assertEquals(0, choice?.index)
        assertTrue((choice?.score ?: 0.0) > .70)
    }

    @Test
    fun japaneseOutputWinsWhenKanaIsConfident() {
        val candidates = listOf(
            OcrCandidateSelector.Candidate("LATIN", listOf("lI 7 I1"), listOf(.31f)),
            OcrCandidateSelector.Candidate("JAPANESE", listOf("こんにちは 世界"), listOf(.86f)),
            OcrCandidateSelector.Candidate("CHINESE", listOf("世 界"), listOf(.58f))
        )
        assertEquals(1, OcrCandidateSelector.choose(candidates)?.index)
    }

    @Test
    fun mixedWrongScriptRegionIsRejected() {
        assertFalse(OcrCandidateSelector.isReadableRegion("LATIN", "अबसलातोय योजना", .75f))
        assertFalse(OcrCandidateSelector.isReadableRegion("DEVANAGARI", "ABSALATOY ROTHIAYLORS", .75f))
        assertTrue(OcrCandidateSelector.isReadableRegion("LATIN", "Come back tomorrow.", .52f))
        assertTrue(OcrCandidateSelector.isReadableRegion("DEVANAGARI", "यह दूसरा सेमेस्टर है", .52f))
    }

    @Test
    fun tinySymbolNoiseIsRejected() {
        assertFalse(OcrCandidateSelector.isReadableRegion("LATIN", "||", .9f))
        assertFalse(OcrCandidateSelector.isReadableRegion("LATIN", "• • •", .9f))
        assertTrue(OcrCandidateSelector.isReadableRegion("LATIN", "OK!", .42f))
    }
}
