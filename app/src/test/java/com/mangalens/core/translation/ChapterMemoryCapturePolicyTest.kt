package com.mangalens.core.translation

import com.mangalens.orez.OrezModelPin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChapterMemoryCapturePolicyTest {
    private val config = ChapterTranslationConfig("hi", localRefinement = true,
        refinementRequest = TranslationRefinementRequest(true, pinnedModel = OrezModelPin("fixture", "a".repeat(64), 100)))

    @Test fun alreadyCapturedModelWithOwnedNullMemoryReplayDoesNotReadLaterAmbientSeries() = runBlocking {
        var captures = 0
        val previous = task(config)
        val actual = ChapterMemoryCapturePolicy.forStart(config, previous.chapterId, previous.ownerRequestId, false, listOf(previous)) {
            captures++; error("Owned absence must not be recaptured")
        }
        assertEquals(config, actual); assertEquals(0, captures)
    }

    @Test fun newExplicitOwnerOrForceCapturesOnceWhilePreservingTheAlreadyPinnedModelTargetAndStyle() = runBlocking {
        for ((owner, force) in listOf("new-owner" to false, "old-owner" to true)) {
            var captures = 0
            val actual = ChapterMemoryCapturePolicy.forStart(config, "c".repeat(32), owner, force, listOf(task(config))) { selected ->
                captures++; assertEquals(config, selected); null
            }
            assertEquals(1, captures); assertEquals(config, actual)
            assertEquals(config.refinementRequest!!.pinnedModel, actual.refinementRequest!!.pinnedModel)
        }
    }

    @Test fun disabledRefinementDoesNotReadPersonalMemoryAndAmbiguousOwnedIdentitiesFailClosed() = runBlocking {
        val disabled = ChapterTranslationConfig("hi")
        assertEquals(disabled, ChapterMemoryCapturePolicy.forStart(disabled, "c".repeat(32), "owner", false, emptyList()) { error("No generative memory read") })
        val previous = task(config)
        assertTrue(runCatching { ChapterMemoryCapturePolicy.forStart(config, previous.chapterId, previous.ownerRequestId, false,
            listOf(previous, previous.copy(id = "d".repeat(32)))) { error("Ambiguous scope") } }.isFailure)
    }

    private fun task(configuration: ChapterTranslationConfig) = ChapterTranslationTask("a".repeat(32), "b".repeat(32), "c".repeat(32),
        "Captured fixture", configuration, listOf(ChapterTranslationPage(0, "/fixture/chapters/page.png", "d".repeat(64))),
        ChapterTranslationStatus.QUEUED, 1, 1, ownerRequestId = "old-owner")
}
