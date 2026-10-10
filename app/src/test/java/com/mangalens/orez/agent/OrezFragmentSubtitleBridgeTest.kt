package com.mangalens.orez.agent

import com.mangalens.download.OriginalFragmentPlan
import com.mangalens.download.OriginalMediaFragment
import com.mangalens.orez.OrezTaskDao
import com.mangalens.orez.OrezTaskEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/** Authored UNRUN. Private selected-plan authority, real durable codec and completion proof gates. */
class OrezFragmentSubtitleBridgeTest {
    private fun plan(role: String) = OriginalFragmentPlan("https://$role.example/manifest.mpd", role, "$role/mp4",
        8_000_000L, listOf(OriginalMediaFragment("https://$role.example/init", expectedBytes = 8)))
    private fun pair() = OrezMediaSelection(plan("video").sourceUrl, headers = mapOf("Cookie" to "captured-video"),
        resolutionId = "a".repeat(32), expectedDurationUs = 8_000_000L,
        audio = OrezAudioSelection(plan("audio").sourceUrl, "a".repeat(32), mapOf("Cookie" to "captured-audio")),
        videoFragments = plan("video"), audioFragments = plan("audio"))
    private class Journal : OrezTaskDao {
        var row: OrezTaskEntity? = null
        override fun observeActive() = flowOf(listOfNotNull(row))
        override suspend fun get(id: String) = row?.takeIf { it.id == id }
        override suspend fun upsert(task: OrezTaskEntity) { row = task }
        override suspend fun pruneFinished(before: Long) = Unit
    }
    @Test fun pairedNativeSubtitlesUseOnlyTheAcceptedAudioPlanAndItsOwnHeaders() {
        val media = pair()
        val source = media.nativeSubtitleSource()
        assertEquals(media.audio!!.uri, source.uri)
        assertEquals(media.audio.headers, source.headers)
        assertEquals(media.audioFragments, source.fragmentPlan)
        assertEquals(media.resolutionId, source.sourceResolutionId)
        assertNotEquals(media.uri, source.uri)
        assertTrue(media.hasFragmentSourceCandidate())
        assertFalse(media.copy(resolutionId = "old-resolution", audio = media.audio.copy(resolutionId = "old-resolution")).hasFragmentSourceCandidate())
        assertFalse(media.copy(audioFragments = media.audioFragments!!.copy(sourceUrl = "https://other.example/audio")).hasFragmentSourceCandidate())
    }
    @Test fun capturedPlansCannotBorrowACallerMutableFragmentListAndLegacyIdentityIsExact() {
        val mutable = plan("audio").fragments.toMutableList()
        val accepted = pair().copy(audioFragments = plan("audio").copy(fragments = mutable)).captured()
        val id = accepted.sourceId
        mutable[0] = mutable[0].copy(url = "https://changed.example/segment")
        assertEquals(id, accepted.sourceId)
        assertEquals("https://audio.example/init", accepted.audioFragments!!.fragments.single().url)
        val legacy = OrezMediaSelection("https://media.example/video.mp4", "https://example/watch", headers = mapOf("Referer" to "https://example"))
        assertEquals("selected-c3851377aca162e0f6df6aea6c101e03", legacy.sourceId)
        assertEquals(legacy.sourceId, legacy.captured().sourceId)
    }
    @Test fun completeSelectedPairSurvivesTheRealPrivateTaskJournal() = runBlocking {
        val media = pair()
        val selected = OrezAgentRuntime().decide("Generate English subtitles", OrezAgentContext(selectedMedia = media)).plan!!
        val journal = Journal()
        assertTrue(OrezTaskStore(journal).checkpoint(selected))
        val cold = OrezTaskStore(journal).load(selected.id)!!
        assertEquals(media, cold.authorization!!.selectedMedia)
        assertEquals(media.sourceId, cold.steps.first().call.arguments["sourceId"])
        assertEquals(media.audioFragments!!.sha256(), cold.authorization.selectedMedia!!.audioFragments!!.sha256())
        OrezDurablePlanRules.validate(cold)
    }
    @Test fun exportedSubtitleCompletionRequiresActualEncodedByteProofAndFullClockTail() {
        val media = pair()
        val options = OrezSubtitleOptions(threads = 2)
        val snapshot = OrezSubtitleSnapshot(media.sourceId, media, "b".repeat(64), "c".repeat(64))
        val receipt = OrezSubtitleReceipt("d".repeat(32), "e".repeat(32), "orez:captured", snapshot, options,
            OrezNativeSubtitleStatus.COMPLETED, 8000, 8000, 1, 1,
            OrezSubtitleExports("f".repeat(64), "0".repeat(64), 99, 105))
        assertThrows(IllegalArgumentException::class.java) { OrezSubtitleTools.verifyCompleted(receipt) }
        val original = byteArrayOf(0, 0, 0, 8, 102, 116, 121, 112)
        val proof = receipt.copy(fragmentContentSha256 = MessageDigest.getInstance("SHA-256").digest(original)
            .joinToString("") { "%02x".format(it) }, fragmentSize = original.size.toLong())
        OrezSubtitleTools.verifyCompleted(proof)
        assertThrows(IllegalArgumentException::class.java) { OrezSubtitleTools.verifyCompleted(proof.copy(fragmentSize = 0)) }
        assertThrows(IllegalArgumentException::class.java) { OrezSubtitleTools.verifyCompleted(proof.copy(processedMs = 6000)) }
        assertThrows(IllegalArgumentException::class.java) { OrezSubtitleTools.verifyCompleted(proof.copy(validationPending = true)) }
    }
}
