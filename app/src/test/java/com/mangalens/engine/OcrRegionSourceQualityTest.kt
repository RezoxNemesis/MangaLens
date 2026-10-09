package com.mangalens.engine

import org.junit.Assert.*
import org.junit.Test

class OcrRegionSourceQualityTest {
    @Test fun recoversTheObservedAuxiliaryPronounSpaceWithoutChangingDialogueWords() {
        val source = "HEY, SOJIRO.\nWHY DIDIT TAKE YOU\nSO LONG JUST TO BUY\nA COKE?!"
        assertEquals(source.replace("DIDIT", "DID IT"), OcrSourceQuality.normalizeLatinSource(source))
    }

    @Test fun recoversTheObservedConjunctionPronounSpaceAcrossAnOcrLineBreak() {
        val source = "I CHECKED ALL THE STORES\nAND VENDING MACHINES, BUTI\nCOULDN'T FIND THE STRAWBERRY\nCOKE YOU WANTED...."
        assertEquals(source.replace("BUTI", "BUT I"), OcrSourceQuality.normalizeLatinSource(source))
    }

    @Test fun restoresOnlyTheSupportedApostropheAndRequestsPixelsForTheChangedWord() {
        val source = "Y-YEORUM!\nSORRY IM SO LATER!"
        assertEquals("Y-YEORUM!\nSORRY I'M SO LATER!", OcrSourceQuality.normalizeLatinSource(source))
        assertTrue(OcrSourceQuality.needsPixelRetry(source))
        assertTrue(OcrSourceQuality.needsPixelRetry(OcrSourceQuality.normalizeLatinSource(source)))
        assertFalse(OcrSourceQuality.needsPixelRetry("Y-YEORUM!\nSORRY I'M SO LATE?!"))
    }

    @Test fun suspiciousAnnotationIsRetriedRatherThanInventingTheVisualSource() {
        val source = "From hebHhe worlaAa"
        assertEquals(source, OcrSourceQuality.normalizeLatinSource(source))
        assertTrue(OcrSourceQuality.needsPixelRetry(source))
        assertFalse(OcrSourceQuality.needsPixelRetry("From the other world: 'Akalifa'!"))
    }

    @Test fun supportedSpacingRepairsGeneralizeToOtherQuestionsAndClauses() {
        assertEquals("HOW COULD YOU LEAVE?", OcrSourceQuality.normalizeLatinSource("HOW COULDYOU LEAVE?"))
        assertEquals("I CALLED, AND WE CAN HELP.", OcrSourceQuality.normalizeLatinSource("I CALLED, ANDWE CAN HELP."))
        assertEquals("IM SOFTWARE", OcrSourceQuality.normalizeLatinSource("IM SOFTWARE"))
        assertEquals("I'M NOT ALONE.", OcrSourceQuality.normalizeLatinSource("IM NOT ALONE."))
    }

    @Test fun namesForeignWordsBrandsAndEffectsAreNotGuessedOrDiscarded() {
        for (source in listOf("ANDI CAN SING.", "HEY, BUTI!", "DIDIT", "Yeorum", "Akalifa", "McDonald",
            "Use my iPhone with eBay.", "Mujhe nahi pata.", "fooBar bazQux", "우중충...", "끼이익", "탁")) {
            assertEquals(source, OcrSourceQuality.normalizeLatinSource(source))
            assertFalse("Unexpected uncertainty for $source", OcrSourceQuality.needsPixelRetry(source))
        }
    }

    @Test fun highConfidenceCorruptionStillGetsARegionRetry() {
        val clear = reading("Please bring it here.", .96f).copy(textSize = 48f)
        val malformed = reading("From hebHhe worlaAa", .96f).copy(textSize = 48f)
        assertEquals(listOf(1), chooseOcrRetryTargets(listOf(clear, malformed)))
        assertTrue(ocrReadingQuality(malformed) < ocrReadingQuality(clear))
    }

    @Test fun highConfidenceApostropheAndWordAmbiguityStillGetsAPixelRetry() {
        val reading = reading("Y-YEORUM!\nSORRY IM SO LATER!", .96f).copy(textSize = 48f)
        assertEquals(listOf(0), chooseOcrRetryTargets(listOf(reading)))
    }

    @Test fun aConfidentOneWordFragmentCannotReplaceTheWholeAnnotation() {
        val original = reading("From hebHhe worlaAa", .52f)
        val fragment = reading("From", .98f).copy(bounds = OcrBox(100f, 100f, 150f, 140f))
        assertTrue(chooseOcrRetryReadingGroup(original, listOf(fragment)).isEmpty())
    }

    @Test fun nativeRetryFragmentsCanRecoverOneOriginalLineWithoutMergingNeighbors() {
        val original = reading("From hebHhe worlaAa", .90f)
        val left = reading("From the other world:", .89f).copy(bounds = OcrBox(100f, 100f, 330f, 140f))
        val right = reading("'Akalifa'!", .90f).copy(bounds = OcrBox(338f, 100f, 500f, 140f))
        val neighbor = reading("WHAT NOW?", .99f).copy(bounds = OcrBox(100f, 210f, 500f, 260f))
        assertEquals(listOf(0, 1), chooseOcrRetryReadingGroup(original, listOf(left, right, neighbor)))
    }

    @Test fun uppercasingTheSameCorruptedWordsIsNotARecovery() {
        val original = reading("From hebHhe worlaAa", .75f)
        assertTrue(chooseOcrRetryReadingGroup(original, listOf(reading("FROM HEBHHE WORLAAA", .99f))).isEmpty())
    }

    @Test fun aFailedCropKeepsItsOriginalAndOtherRegionsForAnActionablePartialResult() {
        val original = reading("From hebHhe worlaAa", .92f)
        assertTrue(chooseOcrRetryReadingGroup(original, emptyList()).isEmpty())
        assertEquals("From hebHhe worlaAa", original.source)
    }

    @Test fun nativeRetryMustRecoverTheChangedWordInsteadOfAConfidentNameOnly() {
        val original = reading("Y-YEORUM!\nSORRY I'M SO LATER!", .96f).copy(bounds = OcrBox(100f, 100f, 500f, 240f))
        val repaired = original.copy(source = "Y-YEORUM!\nSORRY I'M SO LATE?!", confidence = .91f)
        val name = reading("Y-YEORUM!", .99f).copy(bounds = OcrBox(100f, 100f, 500f, 140f))
        assertEquals(listOf(1), chooseOcrRetryReadingGroup(original, listOf(name, repaired)))
    }

    @Test fun aHigherConfidenceCropCannotSilentlyLoseAKnownNegation() {
        val original = reading("I HAVE NO FAMILY OR GUARDIAN.", .55f)
        val changedMeaning = reading("I HAVE A FAMILY OR GUARDIAN.", .98f)
        assertTrue(chooseOcrRetryReadingGroup(original, listOf(changedMeaning)).isEmpty())
        val contracted = reading("I COULDN'T FIND IT.", .55f)
        assertTrue(chooseOcrRetryReadingGroup(contracted, listOf(reading("I COULD FIND IT.", .98f))).isEmpty())
        assertEquals(listOf(0), chooseOcrRetryReadingGroup(contracted, listOf(reading("I COULD NOT FIND IT.", .98f))))
    }

    @Test fun uncertaintyUpscaleRetainsTheExistingAllocationLimits() {
        val bounds = OcrBox(120f, 100f, 420f, 155f)
        val ordinary = planOcrRetry(bounds, 720, 2048, 24f)!!
        val uncertain = planOcrRetryWithScale(bounds, 720, 2048, 24f, 1_000_000, 1280, 3f)!!
        assertTrue(uncertain.scaleX > ordinary.scaleX)
        assertTrue(uncertain.scaledWidth <= 1280 && uncertain.scaledHeight <= 1280)
        assertTrue(uncertain.scaledWidth.toLong() * uncertain.scaledHeight <= 1_000_000L)
        assertEquals(ordinary.crop, uncertain.crop)
        assertNull(planOcrRetryWithScale(bounds, 720, 2048, 24f, 1_000_000, 1280, Float.NaN))
    }

    private fun reading(text: String, confidence: Float) = OcrReading(text, "LATIN", confidence,
        OcrBox(100f, 100f, 500f, 140f), textSize = 30f)
}
