package com.mangalens.orez.agent

import com.mangalens.orez.OrezRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrezAgentRuntimeTest {
    private val runtime = OrezAgentRuntime()

    @Test fun currentUrlDownloadBecomesBackgroundTool() {
        val decision = runtime.decide("Download this at best quality", OrezAgentContext(activeUrl = "https://example.com/watch/1"))
        assertEquals("enqueue_download", decision.plan!!.steps.single().call.name)
        assertEquals("https://example.com/watch/1", decision.routeValue)
    }

    @Test fun libraryQuestionRemainsGroundedConversation() {
        assertTrue(runtime.decide("Summarize my library", OrezAgentContext(hasLibrary = true)).continueToBrain)
    }

    @Test fun explicitPlayOverridesMangaUrlHeuristic() {
        assertEquals(OrezRoute.VIDEO_PLAYER,
            runtime.decide("Play https://manga.example.com/trailer", OrezAgentContext()).immediateRoute)
    }

    @Test fun requestedLanguageSurvivesPlanning() {
        val decision = runtime.decide("Translate this whole chapter into English", OrezAgentContext(hasActiveChapter = true))
        assertEquals("en", decision.plan!!.steps.first().call.arguments["targetLanguage"])
    }

    @Test fun credentialBearingUrlsCannotReachTools() {
        val decision = runtime.decide("Open https://user:password@example.com", OrezAgentContext())
        assertEquals(OrezTaskStatus.FAILED, decision.plan!!.status)
        assertEquals(null, decision.immediateRoute)
    }

    @Test(expected = IllegalArgumentException::class)
    fun modelCannotSpoofTrustedToolRiskOrRoute() {
        OrezToolRegistry().validate(OrezToolCall("open_library", OrezCapability.LIBRARY,
            OrezToolRisk.READ_ONLY, "Delete files", route = OrezRoute.SETTINGS))
    }

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
