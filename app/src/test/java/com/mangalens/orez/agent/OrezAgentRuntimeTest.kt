package com.mangalens.orez.agent

import com.mangalens.orez.OrezRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrezAgentRuntimeTest {
    private val runtime = OrezAgentRuntime()

    @Test fun batchDownloadRetainsEveryUniqueUrlAndTheRequestedQuality() {
        val decision = runtime.decide("Download at 720p https://example.com/a.mp4 and https://example.com/b.mp4 and https://example.com/a.mp4", OrezAgentContext())
        assertFalse(decision.continueToBrain)
        assertEquals(2, decision.plan!!.steps.size)
        assertEquals(listOf("https://example.com/a.mp4", "https://example.com/b.mp4"), decision.plan!!.steps.map { it.call.arguments["value"] })
        assertTrue(decision.plan!!.steps.all { it.call.arguments["quality"] == "P720" })
        assertEquals(OrezRoute.DOWNLOADS, decision.immediateRoute)
    }

    @Test fun excessiveOrAmbiguousBatchDoesNotDispatchAPartialRequest() {
        val oversized = runtime.decide("Download " + (0..8).joinToString(" ") { "https://example.com/$it.mp4" }, OrezAgentContext())
        assertEquals(OrezTaskStatus.FAILED, oversized.plan!!.status)
        assertEquals(null, oversized.immediateRoute)
        val ambiguous = runtime.decide("Download at 720p and 1080p https://example.com/a.mp4", OrezAgentContext())
        assertEquals(OrezTaskStatus.FAILED, ambiguous.plan!!.status)
        assertEquals(null, ambiguous.immediateRoute)
    }

    @Test fun anOpenRequestMentioningDownloadsCannotStartATransfer() {
        val decision = runtime.decide("Open this download website https://example.com/watch/1", OrezAgentContext())
        assertEquals("open_video_url", decision.plan!!.steps.single().call.name)
    }

    @Test fun invalidSecondUrlRejectsWholeBatch() {
        val decision = runtime.decide("Download https://example.com/a.mp4 and https://user:secret@example.com/b.mp4", OrezAgentContext())
        assertEquals(OrezTaskStatus.FAILED, decision.plan!!.status)
        assertEquals(null, decision.immediateRoute)
    }

    @Test fun untrustedBatchCannotInitiateTransfers() {
        val decision = runtime.decide("Download https://example.com/a.mp4 and https://example.com/b.mp4",
            OrezAgentContext(origin = OrezTrustOrigin.WEB_CONTENT, explicitUserRequest = false))
        assertEquals(OrezTaskStatus.FAILED, decision.plan!!.status)
        assertEquals(null, decision.immediateRoute)
    }

    @Test fun currentUrlDownloadBecomesBackgroundTool() {
        val decision = runtime.decide("Download this at best quality", OrezAgentContext(activeUrl = "https://example.com/watch/1"))
        assertEquals("enqueue_download", decision.plan!!.steps.single().call.name)
        assertEquals("https://example.com/watch/1", decision.routeValue)
    }

    @Test fun libraryQuestionRemainsGroundedConversation() {
        assertTrue(runtime.decide("Summarize my library", OrezAgentContext(hasLibrary = true)).continueToBrain)
    }
    @Test fun instructionsQuestionIsNotACommand() {
        assertTrue(runtime.decide("How do I open settings?", OrezAgentContext()).continueToBrain)
        assertTrue(runtime.decide("How do I translate this chapter?", OrezAgentContext(hasActiveChapter = true)).continueToBrain)
        assertTrue(runtime.decide("Why did downloading https://example.com/v.mp4 fail?", OrezAgentContext()).continueToBrain)
    }

    @Test fun explicitPlayOverridesMangaUrlHeuristic() {
        assertEquals(OrezRoute.VIDEO_PLAYER,
            runtime.decide("Play https://manga.example.com/trailer", OrezAgentContext()).immediateRoute)
        assertEquals(OrezRoute.VIDEO_PLAYER,
            runtime.decide("Play this manga trailer https://manga.example.com/trailer", OrezAgentContext()).immediateRoute)
    }
    @Test fun queryParametersAreNotDownloadCommands() {
        val decision = runtime.decide("https://example.com/movie.mp4?download=1", OrezAgentContext())
        assertEquals(OrezRoute.VIDEO_PLAYER, decision.immediateRoute)
        assertEquals("open_video_url", decision.plan!!.steps.single().call.name)
    }
    @Test fun urlWordsCannotInitiateChapterTranslation() {
        val decision = runtime.decide("Open this chapter https://example.com/translation", OrezAgentContext(hasActiveChapter = true))
        assertEquals(OrezRoute.MANGA_READER, decision.immediateRoute)
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

