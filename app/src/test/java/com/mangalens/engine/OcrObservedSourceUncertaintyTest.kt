package com.mangalens.engine

import org.junit.Assert.*
import org.junit.Test

/** Recorded native hypotheses, with controls for real names and technical dialogue. */
class OcrObservedSourceUncertaintyTest {
    @Test fun originalOverArtworkReadingIsUncertainWithoutChangingItsWords() {
        val source = "From thel6the war\\aALaa"
        assertTrue(OcrSourceQuality.needsPixelRetry(source))
        assertEquals(source, OcrSourceQuality.normalizeLatinSource(source))
    }

    @Test fun truncatedNumericFunctionWordRetryIsNotARecoveredSource() {
        assertTrue(OcrSourceQuality.needsPixelRetry("From the6ther"))
        assertTrue(OcrSourceQuality.needsPixelRetry("FROM THE6THER"))
    }

    @Test fun aDigitInsideTheCopulaComplementRequestsPixelsWithoutInventingTheAdjective() {
        val source = "Y-YEORUM!\nSORRY IM S0\nATE?!"
        assertTrue(OcrSourceQuality.needsPixelRetry(source))
        assertEquals(source, OcrSourceQuality.normalizeLatinSource(source))
        assertTrue(OcrSourceQuality.needsPixelRetry("WE ARE S0 TIRED."))
        assertTrue(OcrSourceQuality.needsPixelRetry("I'M N0T READY."))
    }

    @Test fun anotherClauseWithIntrawordBackslashIsUncertainRatherThanRepairedByVocabulary() {
        val source = "This is a strange wo\\rld."
        assertTrue(OcrSourceQuality.needsPixelRetry(source))
        assertEquals(source, OcrSourceQuality.normalizeLatinSource(source))
    }

    @Test fun highConfidenceDoesNotMakeTheRecordedCorruptionEligibleForLettering() {
        val originals = listOf("From thel6the war\\aALaa", "From the6ther", "Y-YEORUM!\nSORRY IM S0\nATE?!")
        for (source in originals) {
            val reading = reading(source, .99f)
            assertEquals(source, listOf(0), chooseOcrRetryTargets(listOf(reading)))
            assertTrue(source, chooseOcrRetryReadingGroup(reading("From a place", .40f), listOf(reading)).isEmpty())
        }
    }

    @Test fun aCompleteHigherConfidenceCorruptedCropCannotReplaceTheReadableOriginal() {
        val original = reading("From the other world", .50f)
        val damaged = reading("From thel6the war\\aALaa", .99f)
        assertTrue(chooseOcrRetryReadingGroup(original, listOf(damaged)).isEmpty())
        assertEquals("From the other world", original.source)
    }

    @Test fun autoKeepsAnUnresolvedStructurallyValidReadingForTheVisiblePartialGuard() {
        val clear = reading("Please bring it here.", .98f)
        val uncertain = reading("From thel6the war\\aALaa", .285f).copy(plausible = false, retryable = true)
        val otherBubble = reading("I HAVE NO FAMILY.", .95f)
        val readings = listOf(clear, uncertain, otherBubble)
        assertEquals(listOf(0, 1, 2), retainOcrReadingsForReview(readings))
        assertEquals("Please bring it here.", readings.first().source)
        assertEquals("I HAVE NO FAMILY.", readings.last().source)
    }

    @Test fun autoDoesNotExposeOrdinaryWeakArtworkOrInvalidUncertainGeometryAsDialogue() {
        val valid = reading("Please bring it here.", .98f)
        val ordinaryWeak = reading("floating", .20f).copy(plausible = false, retryable = true)
        val artwork = reading("From thel6the war\\aALaa", .285f).copy(plausible = false, retryable = false)
        val invalid = artwork.copy(retryable = true, bounds = OcrBox(100f, 20f, 10f, 0f))
        assertEquals(listOf(0), retainOcrReadingsForReview(listOf(valid, ordinaryWeak, artwork, invalid)))
    }

    @Test fun autoWithoutExtraAccuracyPassesStillKeepsKnownUncertaintyForThePartialGuard() {
        val uncertain = reading("From thel6the war\\aALaa", .285f).copy(plausible = false, retryable = true)
        val ordinaryWeak = reading("floating", .20f).copy(plausible = false, retryable = true)
        val artwork = uncertain.copy(retryable = false)
        assertEquals(listOf(0), chooseOcrReadings(listOf(uncertain, ordinaryWeak, artwork), allowWeakForRetry = false))
    }

    @Test fun namesIdentifiersVersionsAmountsAndEffectsRemainUnchanged() {
        for (source in listOf("R2D2", "C3PO", "B2", "S0", "Im S0", "IM S0", "The R2D2 droid is here.",
            "I'M R2D2.", "I'M UNIT S0.", "The PlayStation5 console is here.", "The S0 generator is ready.",
            "I paid 10 dollars.", "I AM 21.", "S0JIRO", "Yeorum", "Akalifa", "우중충...", "끼이익", "탁")) {
            assertFalse(source, OcrSourceQuality.needsPixelRetry(source))
            assertEquals(source, OcrSourceQuality.normalizeLatinSource(source))
        }
    }

    @Test fun explicitCodeAndPathSyntaxIsNotMistakenForBrokenEnglishLettering() {
        for (source in listOf("Use version2beta with the SDK.", "The code is `the6ther`.",
            "Copy from C:\\Temp\\world.", "The path is folder\\file.",
            "Read from https://example.test/the6ther.", "From alpha\\beta in the command.")) {
            assertFalse(source, OcrSourceQuality.needsPixelRetry(source))
            assertEquals(source, OcrSourceQuality.normalizeLatinSource(source))
        }
    }

    @Test fun visiblyValidOtherWorldAndApologyReadingsAreNotMarkedByTheNewRules() {
        assertFalse(OcrSourceQuality.needsPixelRetry("From the other world: 'Akalifa'!"))
        assertFalse(OcrSourceQuality.needsPixelRetry("Y-YEORUM!\nSORRY I'M SO\nLATE?!"))
    }

    @Test fun mentioningATechnicalWordCannotMaskAnUnrelatedCorruptedEnglishClause() {
        assertTrue(OcrSourceQuality.needsPixelRetry("The file is gone. I'M S0 TIRED."))
        assertTrue(OcrSourceQuality.needsPixelRetry("The code is here. From the6ther place."))
    }

    private fun reading(text: String, confidence: Float) = OcrReading(text, "LATIN", confidence,
        OcrBox(0f, 0f, 300f, 100f), textSize = 40f)
}
