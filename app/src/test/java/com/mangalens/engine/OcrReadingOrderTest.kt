package com.mangalens.engine

import org.junit.Assert.*
import org.junit.Test

class OcrReadingOrderTest {
    @Test fun westernDialogueUsesLeftToRightWithinTheSameRow() {
        val readings = listOf(
            text("I am here.", "LATIN", OcrBox(20f, 105f, 150f, 145f)),
            text("Where are you?", "LATIN", OcrBox(200f, 98f, 350f, 138f)),
            text("Next panel", "LATIN", OcrBox(10f, 300f, 160f, 340f))
        )
        assertEquals(listOf(0, 1, 2), orderOcrReadings(readings))
    }

    @Test fun japaneseColumnsReadRightToLeftBeforeTheNextPanel() {
        val readings = listOf(
            text("左の言葉", "JAPANESE", OcrBox(100f, 98f, 130f, 248f)),
            text("右の言葉", "JAPANESE", OcrBox(160f, 105f, 190f, 255f)),
            text("次の場面", "JAPANESE", OcrBox(250f, 400f, 280f, 550f))
        )
        assertEquals(listOf(1, 0, 2), orderOcrReadings(readings))
    }

    @Test fun aTallBubbleOfHorizontalLinesIsNotVerticalText() {
        val box = OcrBox(100f, 100f, 180f, 280f)
        val horizontal = text("これは横書きの文章です", "JAPANESE", box).copy(lineBounds = listOf(
            OcrBox(100f, 100f, 180f, 120f), OcrBox(100f, 160f, 180f, 180f), OcrBox(100f, 240f, 180f, 260f)
        ))
        assertFalse(isVerticalOcr(horizontal))
        assertTrue(isVerticalOcr(text("これは縦書きです", "JAPANESE", OcrBox(100f, 100f, 128f, 280f))))
    }

    @Test fun latinPseudoWordsDoNotDeclareJapaneseColumnOrientation() {
        assertFalse(isVerticalOcr(text("FLOATING", "JAPANESE", OcrBox(100f, 100f, 125f, 280f))))
    }

    @Test fun stackedSquareJapaneseGlyphsAreDetectedAsVertical() {
        val box = OcrBox(100f, 100f, 126f, 210f)
        val reading = text("大丈夫です", "JAPANESE", box).copy(lineBounds = listOf(
            OcrBox(100f, 100f, 125f, 125f), OcrBox(101f, 135f, 126f, 160f), OcrBox(100f, 170f, 125f, 195f)
        ))
        assertTrue(isVerticalOcr(reading))
    }

    @Test fun verticalGlyphLinesKeepWholeColumnsInOrderDespitePixelJitter() {
        val lines = listOf(
            OcrBox(150f, 95f, 175f, 120f), OcrBox(200f, 100f, 225f, 125f),
            OcrBox(150.5f, 125f, 175.5f, 150f), OcrBox(200.5f, 130f, 225.5f, 155f),
            OcrBox(151f, 155f, 176f, 180f), OcrBox(199.5f, 160f, 224.5f, 185f)
        )
        assertEquals(listOf(1, 3, 5, 0, 2, 4), orderVerticalOcrLines(lines))
    }

    @Test fun adjacentAlignedJapaneseColumnsCanFormOneSpeechRegion() {
        val right = text("大丈夫", "JAPANESE", OcrBox(150f, 100f, 175f, 250f))
        val left = text("行こう", "JAPANESE", OcrBox(112f, 105f, 137f, 255f))
        assertTrue(canMergeVerticalOcr(right, left))
    }

    @Test fun distantColumnsAndFuriganaSizedTextStaySeparate() {
        val right = text("大丈夫", "JAPANESE", OcrBox(150f, 100f, 175f, 250f))
        val distant = text("行こう", "JAPANESE", OcrBox(30f, 105f, 55f, 255f))
        val small = text("だい", "JAPANESE", OcrBox(138f, 105f, 146f, 145f)).copy(textSize = 8f)
        assertFalse(canMergeVerticalOcr(right, distant))
        assertFalse(canMergeVerticalOcr(right, small))
    }

    @Test fun aDetectedVerticalColumnCanRetainItsNextSingleGlyphFragment() {
        val upper = text("大丈夫", "JAPANESE", OcrBox(100f, 100f, 126f, 195f)).copy(lineBounds = listOf(
            OcrBox(100f, 100f, 125f, 125f), OcrBox(101f, 135f, 126f, 160f), OcrBox(100f, 170f, 125f, 195f)
        ))
        val lower = text("だ", "JAPANESE", OcrBox(100f, 210f, 125f, 235f))
        assertTrue(canMergeVerticalOcr(upper, lower))
    }

    private fun text(source: String, script: String, box: OcrBox) =
        OcrReading(source, script, .90f, box, textSize = 25f)
}
