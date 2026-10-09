package com.mangalens.orez.agent

import com.mangalens.core.translation.TranslationRefinementRequest
import com.mangalens.core.translation.TranslationStyleProfile
import com.mangalens.orez.OrezModelPin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OrezChapterPlanCaptureTest {
    private val request = TranslationRefinementRequest(true, TranslationStyleProfile.FAITHFUL,
        OrezModelPin("explicit-model", "b".repeat(64), 2_000_000))
    private fun plan(options: OrezTranslationOptions = OrezTranslationOptions("hi-latn", "faithful", localRefinement = true)) =
        OrezAgentRuntime().decide("Translate this chapter into Hinglish", OrezAgentContext(hasActiveChapter = true, activeChapterId = "c".repeat(32), translationOptions = options)).plan!!

    @Test fun newEnabledPlanCapturesItsChosenStyleAndPinExactlyOnce() = runTest {
        val original = plan(); var captures = 0
        val captured = OrezChapterPlanCapture.capture(original) { style -> captures++; assertEquals(request.style, style); request }
        assertEquals(1, captures)
        assertEquals(original.id, captured.id)
        assertEquals(original.steps, captured.steps)
        assertEquals(original.authorization!!.chapterIds, captured.authorization!!.chapterIds)
        assertEquals(request, captured.authorization!!.translation!!.refinementRequest)
        val replay = OrezChapterPlanCapture.capture(captured) { captures++; error("Captured request must not be read from ambient settings again.") }
        assertEquals(captured, replay); assertEquals(1, captures)
    }

    @Test fun missingModelIsCapturedAsUnavailableWithoutSubstitutingAnotherModel() = runTest {
        var captures = 0
        val captured = OrezChapterPlanCapture.capture(plan()) { style -> captures++; TranslationRefinementRequest(true, style, null) }
        assertEquals(1, captures)
        assertTrue(captured.authorization!!.translation!!.refinementRequest!!.enabled)
        assertNull(captured.authorization!!.translation!!.refinementRequest!!.pinnedModel)
    }

    @Test fun disabledAndUnrelatedTasksDoNotCaptureOrLoadARefinementModel() = runTest {
        val disabled = plan(OrezTranslationOptions("hi-latn", localRefinement = false))
        assertEquals(disabled, OrezChapterPlanCapture.capture(disabled) { error("Disabled refinement must not capture a model.") })
        val download = OrezAgentRuntime().decide("Download https://explicit.example/video.mp4", OrezAgentContext()).plan!!
        assertEquals(download, OrezChapterPlanCapture.capture(download) { error("An unrelated download must not capture a model.") })
    }

    @Test fun importedOriginCannotCaptureOrPromoteARefinementRequest() = runTest {
        val original = plan(); val imported = original.copy(authorization = original.authorization!!.copy(origin = OrezTrustOrigin.IMPORTED_CONTENT))
        var captures = 0
        val failure = runCatching { OrezChapterPlanCapture.capture(imported) { captures++; request } }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(0, captures)
    }

    @Test fun cancellationDuringModelCapturePublishesNoPartiallyCapturedPlan() = runTest {
        val original = plan(); var published = false
        val failure = runCatching {
            OrezChapterPlanCapture.capture(original) { throw CancellationException("Cancelled while reading the verified model pin.") }
            published = true
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertFalse(published)
        assertNull(original.authorization!!.translation!!.refinementRequest)
    }

    @Test fun rejectedNewChapterPlanNeverCapturesAnAmbientModel() = runTest {
        val rejected = plan().copy(status = OrezTaskStatus.FAILED)
        var captures = 0
        val result = OrezChapterPlanCapture.capture(rejected) { captures++; request }
        assertEquals(rejected, result)
        assertEquals(0, captures)
    }
}
