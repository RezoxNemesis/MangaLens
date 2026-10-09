package com.mangalens.orez.agent

import com.mangalens.ui.video.SubtitleGenerationConfig
import com.mangalens.ui.video.SubtitleGenerationStatus
import com.mangalens.ui.video.SubtitleGenerationTask
import com.mangalens.ui.video.SubtitleSourceIdentity
import org.junit.Assert.*
import org.junit.Test

class OrezPairedSubtitleEvidenceTest {
    private fun selection() = OrezMediaSelection("https://video.example/selected.mp4", "https://page.example/watch", headers = mapOf("Cookie" to "video-cookie"),
        resolutionId = "captured-resolution", audio = OrezAudioSelection("https://audio.example/selected.m4a", "captured-resolution", mapOf("Cookie" to "audio-cookie")),
        expectedDurationUs = 11_000_000)
    private fun task() = selection().let { media -> SubtitleGenerationTask("a".repeat(32), "b".repeat(32),
        SubtitleSourceIdentity(media.nativeSubtitleSource(), "c".repeat(64), strongEtag = "\"audio-version\"", networkSize = 4000, networkUrl = media.audio!!.uri),
        SubtitleGenerationConfig(modelSha256 = "d".repeat(64), threads = 2), SubtitleGenerationStatus.QUEUED, ownerRequestId = "orez-selected-task-step-1") }

    @Test fun nativeAudioUsesOnlyItsHeadersAndFullCapturedPairCacheBinding() {
        val media = selection()
        val audio = media.nativeSubtitleSource()
        assertEquals(media.audio!!.uri, audio.uri)
        assertEquals(mapOf("Cookie" to "audio-cookie"), audio.headers)
        assertEquals("orez-audio-pair:${media.sourceId}", audio.cacheKey)
        assertNotEquals(media.copy(audio = media.audio.copy(headers = mapOf("Cookie" to "new-audio-cookie"))).nativeSubtitleSource().cacheKey, audio.cacheKey)
        assertNotEquals(media.copy(resolutionId = "new-resolution", audio = media.audio.copy(resolutionId = "new-resolution")).nativeSubtitleSource().cacheKey, audio.cacheKey)
    }

    @Test fun aNativePairCacheKeyAloneCannotManufactureSelectedVideoAuthority() {
        assertThrows(IllegalArgumentException::class.java) { OrezSubtitleNativeEvidence.metadataReceipt(task(), selection().sourceId) }
    }

    @Test fun actualNativeAudioCanBeMappedOnlyBackToTheExactCapturedPresentationPair() {
        val media = selection()
        val receipt = OrezSubtitleNativeEvidence.metadataReceipt(task(), media.sourceId, media)
        assertEquals(media, receipt.media.descriptor)
        assertEquals(media.sourceId, receipt.media.sourceId)
        assertEquals(task().source.fingerprint, receipt.media.sourceFingerprint)
        OrezSubtitleTools.verify(receipt, receipt.media, OrezSubtitleOptions(threads = 2), "orez-selected-task-step-1")
        for (changed in listOf(media.copy(headers = mapOf("Cookie" to "changed-video-cookie")),
            media.copy(audio = media.audio!!.copy(headers = mapOf("Cookie" to "changed-audio-cookie"))),
            media.copy(resolutionId = "new-resolution", audio = media.audio!!.copy(resolutionId = "new-resolution")),
            media.copy(expectedDurationUs = 13_000_000))) {
            assertThrows(IllegalArgumentException::class.java) { OrezSubtitleNativeEvidence.metadataReceipt(task(), changed.sourceId, changed) }
        }
        assertThrows(IllegalArgumentException::class.java) { OrezSubtitleNativeEvidence.metadataReceipt(task(), "selected-" + "e".repeat(32), media) }
    }

    @Test fun wrongNativeAudioUriAndHeadersNeverGainRightsFromMatchingOpaqueIdOrCacheKey() {
        val media = selection(); val original = task()
        for (source in listOf(original.source.source.copy(uri = media.uri), original.source.source.copy(headers = media.headers),
            original.source.source.copy(cacheKey = media.cacheKey))) {
            assertThrows(IllegalArgumentException::class.java) {
                OrezSubtitleNativeEvidence.metadataReceipt(original.copy(source = original.source.copy(source = source)), media.sourceId, media)
            }
        }
    }

    @Test fun capturedResolverDurationRejectsTruncatedOrUnrelatedAudioBeforeFullVideoClaim() {
        val media = selection()
        media.verifySubtitleTail(durationMs = 11_000, processedMs = 11_000)
        media.verifySubtitleTail(durationMs = 10_990, processedMs = 10_990)
        for ((duration, processed) in listOf(6_000L to 6_000L, 11_000L to 6_000L, 60_000L to 60_000L, 0L to 11_000L)) {
            assertThrows(IllegalArgumentException::class.java) { media.verifySubtitleTail(duration, processed) }
        }
    }
}
