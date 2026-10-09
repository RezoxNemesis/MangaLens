package com.mangalens.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrFusionPolicyTest {
    @Test fun mixedScriptsKeepTheBestReadingOfEachSeparateRegion() {
        val readings = listOf(
            reading("I will protect you.", "LATIN", .95f, 20f),
            reading("DECORATIVE GARBAGE WORDS".repeat(5), "LATIN", .45f, 220f),
            reading("こんにちは世界", "JAPANESE", .90f, 220f)
        )
        assertEquals(setOf(0, 2), chooseOcrReadings(readings).toSet())
    }

    @Test fun aLongerLowConfidenceReadingDoesNotBeatShortReliableText() {
        val readings = listOf(
            reading("STOP!", "LATIN", .95f),
            reading("STOP THE LONG HALLUCINATED WORDS KEEP COMING", "LATIN", .44f)
        )
        assertEquals(listOf(0), chooseOcrReadings(readings))
    }

    @Test fun nativeScriptPlausibilityBreaksConfidenceTies() {
        val readings = listOf(
            reading("こんにちは", "LATIN", .90f),
            reading("こんにちは", "JAPANESE", .90f)
        )
        assertEquals(listOf(1), chooseOcrReadings(readings))
    }

    @Test fun touchingNeighbouringBubblesAreNotDuplicates() {
        val readings = listOf(
            reading("First bubble", "LATIN", .9f, 20f),
            reading("Second bubble", "LATIN", .9f, 55f)
        )
        assertEquals(2, chooseOcrReadings(readings).size)
    }

    @Test fun implausibleAndInvalidGeometryNeverSurviveFusion() {
        val readings = listOf(
            reading("FLOATING", "LATIN", .99f).copy(plausible = false),
            reading("bad box", "LATIN", .9f).copy(bounds = OcrBox(30f, 50f, 20f, 70f)),
            reading("real dialogue", "LATIN", .90f, 140f)
        )
        assertEquals(listOf(2), chooseOcrReadings(readings))
    }

    @Test fun combiningMarksDoNotPenaliseHindiScriptPlausibility() {
        val hindi = reading("नमस्ते दुनिया", "DEVANAGARI", .85f)
        val latin = hindi.copy(script = "LATIN")
        assertTrue(ocrReadingQuality(hindi) > ocrReadingQuality(latin))
    }

    @Test fun aStructurallyPlausibleWeakReadingCanRemainForItsCropRetry() {
        val weak = reading("Are you okay?", "LATIN", .2f).copy(plausible = false, retryable = true)
        val artwork = reading("FLOATING", "LATIN", .2f, 140f).copy(plausible = false, retryable = false)
        assertTrue(chooseOcrReadings(listOf(weak, artwork)).isEmpty())
        assertEquals(listOf(0), chooseOcrReadings(listOf(weak, artwork), allowWeakForRetry = true))
    }

    private fun reading(text: String, script: String, confidence: Float, top: Float = 20f) =
        OcrReading(text, script, confidence, OcrBox(20f, top, 220f, top + 40f))
}
