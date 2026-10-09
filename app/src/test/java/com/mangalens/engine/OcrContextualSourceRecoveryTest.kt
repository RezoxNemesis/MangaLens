package com.mangalens.engine

import org.junit.Assert.*
import org.junit.Test

class OcrContextualSourceRecoveryTest {
    private val raw = "Y-YEORUM!\nSORRY IM S0\nATE?!"
    private val box = OcrBox(110f, 103f, 403f, 249f)
    private fun reading(source: String, confidence: Float, bounds: OcrBox = box) = OcrReading(
        OcrSourceQuality.normalizeLatinSource(source), "LATIN", confidence, bounds, plausible = true, textSize = 40f)

    @Test fun preservesActualStableNameLettersRatherThanAcceptingHigherConfidenceDeslant() {
        val original = reading(raw, .49438658f)
        val deslant = reading("YoYEORM!\nSORRY IM SO\nLATE?!", .5844546f, OcrBox(98.657f, 103.49f, 420.204f, 251.396f))
        assertTrue("Candidate really has higher native confidence", deslant.confidence > original.confidence)
        assertTrue("Name disagreement must not become better source", chooseOcrRetryReadingGroup(original, listOf(deslant)).isEmpty())
    }

    @Test fun acceptsActualContextualScaleClauseAndEquivalentStutterLetters() {
        val original = reading(raw, .49438658f)
        val scaled = reading("YYEORUM!\nSORRY IM SO\nLATE?!", .53445095f, OcrBox(108.85167f, 99.64115f, 405.26315f, 254.1268f))
        assertEquals(listOf(0), chooseOcrRetryReadingGroup(original, listOf(scaled)))
        assertEquals("YYEORUM!\nSORRY I'M SO\nLATE?!", scaled.source)
    }

    @Test fun numericRotationComplementRemainsUncertainWithoutInventingSo() {
        for (source in listOf("YYEORUM!\nSORRY IM 50\nLATE?!", "SORRY IM50 LATE?!", "Sorry I'm 50 late?!")) {
            assertTrue(source, OcrSourceQuality.needsPixelRetry(source))
            assertEquals(source, OcrSourceQuality.normalizeLatinSource(source))
        }
        val rotated = reading("YYEORUM!\nSORRY IM 50\nLATE?!", .5572917f)
        assertTrue(chooseOcrRetryReadingGroup(reading(raw, .49438658f), listOf(rotated)).isEmpty())
    }

    @Test fun ageAmountCodeAndForeignNameControlsArePreserved() {
        for (source in listOf("I'm 50 years old.", "I am 50 today.", "I'm 50 and happy.", "Sorry I'm 50 minutes late.",
            "I'm 50 dollars short.", "The code IM50 is valid.", "SORRY, THE MODEL IM50 IS READY.", "Im50", "Code: `IM50 LATE`", "I'M SO LATE!"))
            assertFalse(source, OcrSourceQuality.needsPixelRetry(source))
    }

    @Test fun alternativeRealSourceNamesAndStableWordsCannotChangeInAnyHigherConfidenceRetry() {
        val original = reading("K-KAORI!\nSORRY IM S0\nATE?!", .45f)
        assertTrue(chooseOcrRetryReadingGroup(original, listOf(reading("KoKARI!\nSORRY I'M SO LATE?!", .9f))).isEmpty())
        assertEquals(listOf(0), chooseOcrRetryReadingGroup(original, listOf(reading("KKAORI!\nSORRY I'M SO LATE?!", .7f))))
        val no = reading("Jin, do not lose the SWORD!", .4f)
        assertTrue(chooseOcrRetryReadingGroup(no, listOf(reading("Jim, do not lose the SWORD!", .9f))).isEmpty())
        assertTrue(chooseOcrRetryReadingGroup(no, listOf(reading("Jin, do not lose the WORLD!", .9f))).isEmpty())
    }
    @Test fun onlyClassifierMarkedComparativeWordMayChangeWhileClearTemporalWordStaysObserved() {
        val uncertain = reading("NORA! SORRY I'M SO LATER!", .5f)
        assertTrue(OcrSourceQuality.needsPixelRetry(uncertain.source))
        assertEquals(listOf(0), chooseOcrRetryReadingGroup(uncertain, listOf(reading("NORA! SORRY I'M SO LATE!", .8f))))
        val clear = reading("NORA! SEE YOU LATER!", .4f)
        assertTrue(chooseOcrRetryReadingGroup(clear, listOf(reading("NORA! SEE YOU LATE!", .8f))).isEmpty())
    }

    @Test fun contextualReadingCannotAbsorbTextFromAnUnchangedNeighbor() {
        val original = reading("NORA! SORRY IM S0 ATE?!", .45f, OcrBox(250f, 300f, 550f, 450f))
        val neighbor = reading("DO NOT OPEN THE DOOR!", .9f, OcrBox(610f, 290f, 870f, 460f))
        val merged = reading("NORA! SORRY I'M SO LATE?! DO NOT OPEN THE DOOR!", .9f,
            OcrBox(250f, 300f, 675f, 460f))
        assertFalse("Legacy unscoped selection really accepts this merged crop",
            chooseOcrRetryReadingGroup(original, listOf(merged)).isEmpty())
        assertTrue("Known neighboring source must remain its own region",
            chooseOcrRetryReadingGroup(original, listOf(merged), listOf(neighbor)).isEmpty())
        val isolated = reading("NORA! SORRY I'M SO LATE?!", .8f, original.bounds)
        assertEquals(listOf(0), chooseOcrRetryReadingGroup(original, listOf(isolated), listOf(neighbor)))
        assertEquals("DO NOT OPEN THE DOOR!", neighbor.source)
    }

}
