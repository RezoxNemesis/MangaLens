package com.mangalens.orez

import org.junit.Assert.*
import org.junit.Test

class SavedBubbleOrezTraceTest {
    @Test fun originalOneArgumentTraceKeepsItsKnownV2ProfileAndInputHash() {
        val trace = OrezGenerationTrace { 10L }
        val input = OrezLocalizationProfile.formattedPrompt("SOURCE: Fixture")
        trace.captureInput(input)
        val result = trace.snapshot("FIXTURE")
        assertEquals(OrezLocalizationV2.REVISION, result.profileRevision)
        assertEquals(OrezLocalizationV2.hash(input), result.formattedInputSha256)
    }
    @Test fun explanationTraceCarriesItsDistinctFixedProfileAndActualFormattedInputHash() {
        val trace = OrezGenerationTrace({ 10L }, OrezGenerationTraceProfile.SAVED_BUBBLE)
        val input = SavedBubbleOrezProfile.formattedPrompt("Fixture excerpt")
        trace.captureInput(input)
        val result = trace.snapshot("FIXTURE")
        assertEquals(SavedBubbleOrezProfile.REVISION, result.profileRevision)
        assertNotEquals(OrezLocalizationV2.REVISION, result.profileRevision)
        assertEquals(OrezLocalizationV2.hash(input), result.formattedInputSha256)
    }
    @Test fun eachProfileAttemptKeepsItsOwnCompletionAndPhaseClock() {
        var now = 1L
        val original = OrezGenerationTrace { now }
        val selected = OrezGenerationTrace({ now }, OrezGenerationTraceProfile.SAVED_BUBBLE)
        selected.mark(OrezGenerationPhase.WAIT_GENERATION_ADMISSION)
        now = 12L
        val result = selected.snapshot("FIXTURE")
        assertEquals(11L, result.phaseMs["WAIT_GENERATION_ADMISSION"])
        assertNull(result.completion)
        assertNull(original.snapshot("FIXTURE").formattedInputSha256)
        assertEquals(OrezLocalizationV2.REVISION, original.snapshot("FIXTURE").profileRevision)
    }
}
