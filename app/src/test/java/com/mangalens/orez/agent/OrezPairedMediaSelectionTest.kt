package com.mangalens.orez.agent

import com.mangalens.orez.OrezTaskDao
import com.mangalens.orez.OrezTaskEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OrezPairedMediaSelectionTest {
    private fun pair() = OrezMediaSelection("https://video.example/selected.mp4", "https://page.example/watch", "Video",
        mapOf("Cookie" to "video-cookie"), resolutionId = "resolution-one",
        audio = OrezAudioSelection("https://audio.example/selected.m4a", "resolution-one", mapOf("Cookie" to "audio-cookie")),
        expectedDurationUs = 11_000_000)
    private class Journal : OrezTaskDao {
        var row: OrezTaskEntity? = null
        override fun observeActive() = flowOf(listOfNotNull(row))
        override suspend fun get(id: String) = row?.takeIf { it.id == id }
        override suspend fun upsert(task: OrezTaskEntity) { row = task }
        override suspend fun pruneFinished(before: Long) = Unit
    }

    @Test fun legacySingleStreamSourceIdentityKeepsExactPriorLengthFramedSpelling() {
        val legacy = OrezMediaSelection("https://media.example/video.mp4", "https://example/watch", headers = mapOf("Referer" to "https://example"))
        assertEquals("selected-c3851377aca162e0f6df6aea6c101e03", legacy.sourceId)
        assertEquals(legacy.sourceId, legacy.captured().sourceId)
    }

    @Test fun fullPairAndResolutionAndDurationArePartOfTheCapturedSourceIdentity() {
        val original = pair()
        for (changed in listOf(original.copy(audio = original.audio!!.copy(uri = "https://audio.example/another.m4a")),
            original.copy(audio = original.audio!!.copy(headers = mapOf("Cookie" to "different-audio-cookie"))),
            original.copy(headers = mapOf("Cookie" to "different-video-cookie")),
            original.copy(resolutionId = "resolution-two", audio = original.audio!!.copy(resolutionId = "resolution-two")),
            original.copy(expectedDurationUs = 12_000_000), original.copy(audio = null))) {
            assertNotEquals("Every paired authority field must fence retries", original.sourceId, changed.sourceId)
        }
    }

    @Test fun bothHeaderMapsAreSnapshottedBeforeTheAcceptedRequestCanSuspend() {
        val video = mutableMapOf("Cookie" to "captured-video")
        val audio = mutableMapOf("Cookie" to "captured-audio")
        val live = pair().copy(headers = video, audio = pair().audio!!.copy(headers = audio))
        val decision = OrezAgentRuntime().decide("Generate English subtitles for this selected video", OrezAgentContext(selectedMedia = live))
        val selected = decision.plan!!.authorization!!.selectedMedia!!
        val id = selected.sourceId
        video["Cookie"] = "later-video"; audio["Cookie"] = "later-audio"
        assertEquals("captured-video", selected.headers["Cookie"])
        assertEquals("captured-audio", selected.audio!!.headers["Cookie"])
        assertEquals(id, selected.sourceId)
    }

    @Test fun crossResolutionMissingDurationUnsafeAudioAndBadHeadersAreRejectedBeforeNativeDispatch() {
        val valid = pair()
        assertEquals(OrezTaskStatus.RUNNING, OrezAgentRuntime().decide("Generate English subtitles", OrezAgentContext(selectedMedia = valid)).plan!!.status)
        for (invalid in listOf(valid.copy(resolutionId = null), valid.copy(audio = valid.audio!!.copy(resolutionId = "other-resolution")),
            valid.copy(expectedDurationUs = null), valid.copy(expectedDurationUs = 0),
            valid.copy(audio = valid.audio!!.copy(uri = "javascript:readEveryFile()")),
            valid.copy(audio = valid.audio!!.copy(headers = mapOf("Cookie" to "bad\r\nInjected: header"))))) {
            val decision = OrezAgentRuntime().decide("Generate English subtitles", OrezAgentContext(selectedMedia = invalid))
            assertEquals("A paired source is authority only for its one captured resolution", OrezTaskStatus.FAILED, decision.plan!!.status)
        }
    }

    @Test fun pairedSelectionAndItsIndependentHeadersSurviveJournalReopen() = runTest {
        val media = pair()
        val plan = OrezAgentRuntime().decide("Generate English subtitles", OrezAgentContext(selectedMedia = media)).plan!!
        val dao = Journal(); val store = OrezTaskStore(dao); store.checkpoint(plan)
        val reopened = OrezTaskStore(dao).load(plan.id)!!
        assertEquals(media, reopened.authorization!!.selectedMedia)
        assertEquals(media.sourceId, reopened.steps[0].call.arguments["sourceId"])
        OrezDurablePlanRules.validate(reopened)
    }

    @Test fun aPromptAudioOrPageUriCannotReplaceTheSelectedVideoAuthority() {
        val media = pair()
        for (uri in listOf(media.audio!!.uri, "https://page.example/different-watch", "file:///not-selected.mp4")) {
            val decision = OrezAgentRuntime().decide("Generate subtitles for $uri", OrezAgentContext(selectedMedia = media))
            assertNull(decision.plan)
            assertTrue(decision.message.contains("Select the requested playable video"))
        }
    }

    @Test fun toolArgumentsAndModelCatalogExposeNoPrivateStreamHeadersOrAudioDescriptor() {
        val media = pair()
        val plan = OrezAgentRuntime().decide("Generate English subtitles", OrezAgentContext(selectedMedia = media)).plan!!
        val public = plan.steps.joinToString { it.call.toString() } + OrezToolRegistry().catalog()
        for (secret in listOf(media.uri, media.audio!!.uri, "video-cookie", "audio-cookie", "resolution-one")) assertFalse(public.contains(secret))
        assertEquals(setOf("sourceId"), plan.steps[0].call.arguments.keys)
    }
}
