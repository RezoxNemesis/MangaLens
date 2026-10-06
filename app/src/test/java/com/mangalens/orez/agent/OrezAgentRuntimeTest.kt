package com.mangalens.orez.agent

import com.mangalens.orez.OrezRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrezAgentRuntimeTest {
    private val runtime = OrezAgentRuntime()

    @Test
    fun routesActiveChapterTranslationWithoutUrl() {
        val decision = runtime.decide(
            "Translate this whole chapter to Hindi",
            OrezAgentContext(hasActiveChapter = true)
        )
        assertFalse(decision.continueToBrain)
        assertEquals(OrezRoute.TRANSLATE_ACTIVE_CHAPTER, decision.immediateRoute)
        assertNotNull(decision.plan)
    }

    @Test
    fun downloadIntentRoutesGenericUrlToDownloadRoom() {
        val decision = runtime.decide(
            "Download this at best quality https://example.com/watch/123",
            OrezAgentContext()
        )
        assertEquals(OrezRoute.DOWNLOADS, decision.immediateRoute)
        assertEquals("https://example.com/watch/123", decision.routeValue)
    }

    @Test
    fun conversationalQuestionFallsThroughToBrain() {
        val decision = runtime.decide(
            "Explain why this character is angry",
            OrezAgentContext(hasActiveChapter = true)
        )
        assertTrue(decision.continueToBrain)
    }

    @Test
    fun untrustedContentCannotInitiateLocalMutation() {
        val decision = runtime.decide(
            "Translate this whole chapter",
            OrezAgentContext(
                hasActiveChapter = true,
                origin = OrezTrustOrigin.WEB_CONTENT,
                explicitUserRequest = false
            )
        )
        assertFalse(decision.continueToBrain)
        assertEquals(null, decision.immediateRoute)
    }
}
