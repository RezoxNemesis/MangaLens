package com.mangalens.core.translation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ChapterTranslationRequestTest {
    private val task = ChapterTranslationTask("task", "generation-1", "chapter", "Chapter",
        ChapterTranslationConfig("hi"), emptyList(), ChapterTranslationStatus.QUEUED, 0L, 0L)

    @Test fun cancelBeforeDispatchDoesNotCreateWork() = runBlocking {
        val request = ChapterTranslationRequest().apply { cancel() }
        var starts = 0
        val result = request.dispatch({ starts++; task }, { it }, { it }, { it })
        assertNull(result)
        assertEquals(0, starts)
    }

    @Test fun cancelWhileSchedulingCancelsTheReturnedGenerationBeforePresentation() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val request = ChapterTranslationRequest()
        var cancelledGeneration: String? = null
        val result = async { request.dispatch({ entered.complete(Unit); release.await(); task }, { it }, { it }, {
            cancelledGeneration = it.generation
            it.copy(status = ChapterTranslationStatus.CANCELLED)
        }) }
        entered.await()
        request.cancel()
        release.complete(Unit)
        assertEquals(ChapterTranslationStatus.CANCELLED, result.await()!!.status)
        assertEquals(task.generation, cancelledGeneration)
    }

    @Test fun pauseWhileSchedulingAppliesBeforeReturning() = runBlocking {
        val request = ChapterTranslationRequest().apply { setPaused(true) }
        val result = request.dispatch({ task }, { it.copy(status = ChapterTranslationStatus.PAUSED) }, { it }, { it })
        assertEquals(ChapterTranslationStatus.PAUSED, result!!.status)
    }

    @Test fun cancelDuringPauseStillCancelsInsteadOfPublishingPausedWork() = runBlocking {
        val request = ChapterTranslationRequest().apply { setPaused(true) }
        val result = request.dispatch({ task }, { request.cancel(); it.copy(status = ChapterTranslationStatus.PAUSED) }, { it }, {
            it.copy(status = ChapterTranslationStatus.CANCELLED)
        })
        assertEquals(ChapterTranslationStatus.CANCELLED, result!!.status)
    }

    @Test fun resumeDuringPauseUsesItsNewGeneration() = runBlocking {
        val request = ChapterTranslationRequest().apply { setPaused(true) }
        var resumedGeneration: String? = null
        val result = request.dispatch({ task }, { request.setPaused(false); it.copy(status = ChapterTranslationStatus.PAUSED) }, {
            resumedGeneration = it.generation
            it.copy(status = ChapterTranslationStatus.QUEUED, generation = "generation-2")
        }, { it })
        assertEquals("generation-1", resumedGeneration)
        assertEquals("generation-2", result!!.generation)
        assertEquals(ChapterTranslationStatus.QUEUED, result.status)
    }
}
