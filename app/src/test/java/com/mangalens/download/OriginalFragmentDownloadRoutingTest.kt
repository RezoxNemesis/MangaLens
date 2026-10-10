package com.mangalens.download

import org.junit.Assert.*
import org.junit.Test

/** AUTHORED UNRUN: exact finite transport has priority over the anchor URL's manifest shape. */
class OriginalFragmentDownloadRoutingTest {
    private fun media(anchor: String = "https://fixture.invalid/manifest/video.mpd") = ResolvedMediaLink(
        anchor, "video/mp4", "fixture", expectedDurationUs = 8_000_000L,
        videoFragments = OriginalFragmentPlan(anchor, "1080", "video/mp4", 8_000_000L,
            listOf(OriginalMediaFragment("https://fixture.invalid/init"), OriginalMediaFragment("https://fixture.invalid/media"))))
    @Test fun manifestLookingFragmentAnchorRemainsOriginalWorkerTransport() {
        val media = media(); val kind = OriginalFragmentTransport.downloadKind(media)
        assertTrue(OriginalFragmentTransport.isFragmentRow(kind))
        SelectedDownloadTransportPolicy.requireWorkerCompatible(media)
        OriginalFragmentTransport.requireDownloadBinding(kind, media)
        val row = DownloadEntity("fixture", media.url, "fixture", "video/mp4", selectedTransport = kind)
        assertFalse(row.isAdaptive); assertTrue(row.isVideo); assertTrue(row.requiresBoundMediaReceipt())
    }
    @Test fun supportedIndependentAudioPlanAlsoOverridesManifestLookingAudioAnchor() {
        val media = media().copy(audioUrl = "https://fixture.invalid/audio.mpd", audioMimeType = "audio/mp4",
            audioFragments = OriginalFragmentPlan("https://fixture.invalid/audio.mpd", "audio", "audio/mp4", 8_000_000L,
                listOf(OriginalMediaFragment("https://fixture.invalid/audio-init"), OriginalMediaFragment("https://fixture.invalid/audio-media"))))
        SelectedDownloadTransportPolicy.requireSupported(media); SelectedDownloadTransportPolicy.requireWorkerCompatible(media)
        OriginalFragmentTransport.requireDownloadBinding(OriginalFragmentTransport.downloadKind(media), media)
    }
    @Test fun sameAnchorChangedSequenceCannotBorrowOldDurableTransportReceipt() {
        val media = media(); val kind = OriginalFragmentTransport.downloadKind(media)
        val plan = requireNotNull(media.videoFragments)
        val changed = media.copy(videoFragments = plan.copy(fragments = plan.fragments.reversed()))
        assertNotEquals(kind, OriginalFragmentTransport.downloadKind(changed))
        assertTrue(runCatching { OriginalFragmentTransport.requireDownloadBinding(kind, changed) }.isFailure)
    }
    @Test fun missingOrUnrecognizedDurableTransportNeverFallsBackToAnAnchorUrl() {
        val media = media(); val kind = OriginalFragmentTransport.downloadKind(media)
        assertTrue(runCatching { OriginalFragmentTransport.requireDownloadBinding(null, media) }.isFailure)
        assertTrue(runCatching { OriginalFragmentTransport.requireDownloadBinding(kind, null) }.isFailure)
        assertTrue(runCatching { OriginalFragmentTransport.requireDownloadBinding("future-transport", media) }.isFailure)
    }
    @Test fun migratedNullDiscriminatorKeepsHistoricalAdaptiveRowBehavior() {
        val row = DownloadEntity("legacy", "https://fixture.invalid/video.mpd", "legacy", "video/mp4")
        assertNull(row.selectedTransport); assertTrue(row.isAdaptive)
        assertNull(OriginalFragmentTransport.downloadKind(null)); OriginalFragmentTransport.requireDownloadBinding(null, null)
    }
    @Test fun genericFragmentRowStillRequiresItsCompletePrivateReceipt() {
        val media = media("https://fixture.invalid/video")
        val row = DownloadEntity("fixture", media.url, "fixture", "video/mp4", selectedTransport = OriginalFragmentTransport.downloadKind(media))
        assertEquals("generic", row.provider); assertTrue(row.requiresBoundMediaReceipt())
        assertTrue(runCatching { requireBoundMediaSource(row.sourceUrl, null, row.requiresBoundMediaReceipt()) }.isFailure)
    }
}
