package com.mangalens.orez

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OrezLiveAnswerPolicyTest {
    private val excerpt = "Gold is a chemical element and a precious metal."

    @Test fun encyclopediaFallbackKeepsItsAttributionAndTemporalLimit() {
        val answer = OrezLiveAnswerPolicy.fallback("wikipedia", listOf(excerpt))
        assertTrue(answer, answer.contains("Wikipedia encyclopedia"))
        assertTrue(answer, answer.contains("cannot verify current news, prices or schedules"))
        assertTrue(answer, answer.contains(excerpt))
    }

    @Test fun encyclopediaBackgroundNeverInvokesAmbientModelSynthesis() = runBlocking {
        val answer = OrezLiveAnswerPolicy.answer(" Wikipedia ", listOf(excerpt)) {
            fail("A model must not present encyclopedia background as verified current information")
            "Today's gold price is 99 dollars."
        }
        assertTrue(answer, answer.contains(excerpt))
        assertFalse(answer, answer.contains("99 dollars"))
    }

    @Test fun unusableEncyclopediaExcerptsKeepTheLimitWithoutInventingAnAnswer() {
        val answer = OrezLiveAnswerPolicy.fallback("wikipedia", listOf("Main menu: sign in to create account"))
        assertTrue(answer, answer.contains("cannot verify current news, prices or schedules"))
        assertTrue(answer, answer.contains("not clean enough"))
        assertFalse(answer, answer.contains("sign in"))
    }

    @Test fun ordinaryWebEvidenceKeepsWorkingModelSynthesis() = runBlocking {
        var calls = 0
        val answer = OrezLiveAnswerPolicy.answer("web", listOf(excerpt)) {
            calls++; "The source reports a chemical element."
        }
        assertEquals(1, calls)
        assertEquals("The source reports a chemical element.", answer)
    }

    @Test fun modelUnavailableUsesCleanDistinctBoundedExcerpts() = runBlocking {
        val answer = OrezLiveAnswerPolicy.answer("web", listOf(excerpt, excerpt, "Jump to content and main menu links are available.")) { null }
        assertEquals(excerpt, answer)
    }

    @Test fun aDifferentProviderIsNotMislabelledAsWikipedia() {
        val answer = OrezLiveAnswerPolicy.fallback("web", listOf(excerpt))
        assertEquals(excerpt, answer)
        assertFalse(answer.contains("Wikipedia"))
    }

    @Test fun cancellationFromTheRealSynthesisBoundaryPropagates() = runBlocking {
        try {
            OrezLiveAnswerPolicy.answer("web", listOf(excerpt)) { throw CancellationException("cancelled") }
            fail("Cancellation must not become a fallback answer")
        } catch (expected: CancellationException) {
            assertEquals("cancelled", expected.message)
        }
    }
}
