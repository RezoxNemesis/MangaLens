package com.mangalens.ui.web

import com.mangalens.download.ProviderCaptionFormat
import com.mangalens.download.ProviderCaptionInventory
import com.mangalens.download.ProviderCaptionKind
import com.mangalens.download.ProviderCaptionTrack
import com.mangalens.ui.video.SpeechCue
import org.junit.Assert.*
import org.junit.Test

/** Changes to any current-source or real-clock fence must fail these positive and negative controls. */
class BrowserSourceCaptionScopeTest {
    private val page = "https://source.invalid/watch"
    private fun owner() = BrowserCaptionPageOwner("tab1", 7, page, "a".repeat(32))
    private fun sample() = BrowserCaptionClockSample(page, "b".repeat(32), "c".repeat(32), "d".repeat(32),
        "blob:https://source.invalid/actual-video", "audio-en", "en", 1100, 8000, 1.0, true, false, 4)
    private fun inventory() = ProviderCaptionInventory(page, null, "en", "en", 8000,
        listOf(ProviderCaptionTrack("https://source.invalid/original.vtt", "en", ProviderCaptionKind.MANUAL, ProviderCaptionFormat.VTT)))
    private fun scope() = BrowserSourceCaptionScope(owner(), sample(), inventory())

    @Test fun sourceIdentityCapturesTheCurrentElementWithoutBindingPlaybackProgress() {
        val source = scope()
        assertTrue(source.sourceId.matches(Regex("[a-f0-9]{32}")))
        assertEquals(source.sourceId, BrowserSourceCaptionScope(owner(), sample().copy(positionMs=3800, playbackRate=1.5, paused=false), inventory()).sourceId)
        assertNotEquals(source.sourceId, BrowserSourceCaptionScope(owner(), sample().copy(sourceVersion="e".repeat(32)), inventory()).sourceId)
    }
    @Test fun dispatchedInventoryCannotBeChangedByItsCaller() {
        val mutable = inventory().tracks.toMutableList()
        val source = BrowserSourceCaptionScope(owner(), sample(), inventory().copy(tracks=mutable))
        mutable.clear()
        assertEquals(1,source.inventory.tracks.size)
    }
    @Test fun exactCurrentElementCanPresentAtItsActualPausedClock() {
        assertTrue(scope().accepts(owner(), sample()))
        assertEquals("Do not open the door.", browserCaptionTextAt(listOf(
            SpeechCue(500,2000,"Do not open the door."), SpeechCue(3000,5000,"We need to leave now.")), sample()))
    }
    @Test fun seekAndSpeedUseTheActualClockWithoutElapsedTimeInterpolation() {
        val actual = sample().copy(positionMs=3800, playbackRate=1.5, paused=false)
        assertTrue(scope().accepts(owner(), actual))
        assertEquals("We need to leave now.", browserCaptionTextAt(listOf(
            SpeechCue(500,2000,"Do not open the door."), SpeechCue(3000,5000,"We need to leave now.")), actual))
        assertNull(browserCaptionTextAt(listOf(SpeechCue(500,2000,"Do not open the door.")), actual))
    }
    @Test fun tabNavigationAndWebViewReplacementRejectTheOldSource() {
        val source = scope()
        assertFalse(source.accepts(owner().copy(tabId="tab2"), sample()))
        assertFalse(source.accepts(owner().copy(navigationEpoch=8), sample()))
        assertFalse(source.accepts(owner().copy(webViewToken="e".repeat(32)), sample()))
    }
    @Test fun sameUrlNewDocumentElementOrSourceVersionCannotReuseOldCues() {
        val source = scope()
        assertFalse(source.accepts(owner(),sample().copy(documentNonce="e".repeat(32))))
        assertFalse(source.accepts(owner(),sample().copy(elementId="e".repeat(32))))
        assertFalse(source.accepts(owner(),sample().copy(sourceVersion="e".repeat(32))))
    }
    @Test fun actualMediaSourceAudioOrLanguageReplacementRetiresTheOldTrack() {
        val source = scope()
        assertFalse(source.accepts(owner(),sample().copy(currentSrc="blob:https://source.invalid/new-video")))
        assertFalse(source.accepts(owner(),sample().copy(audioTrackKey="audio-hi")))
        assertFalse(source.accepts(owner(),sample().copy(audioLanguage="hi")))
        assertFalse(source.accepts(owner(),sample().copy(durationMs=9000)))
    }
    @Test fun invalidUnreadyOrSeekingClockCannotClaimACue() {
        val cues=listOf(SpeechCue(500,2000,"Do not open the door."))
        assertNull(browserCaptionTextAt(cues,sample().copy(readyState=0)))
        assertNull(browserCaptionTextAt(cues,sample().copy(seeking=true)))
        assertNull(browserCaptionTextAt(cues,sample().copy(positionMs=-1)))
        assertNull(browserCaptionTextAt(cues,sample().copy(playbackRate=Double.NaN)))
        assertNull(browserCaptionTextAt(cues,sample().copy(positionMs=9_000)))
    }
    @Test fun cueEndpointsAndOverlapsComeFromActualCaptionTimes() {
        val cues=listOf(SpeechCue(500,2000,"First"), SpeechCue(1100,2000,"Overlap"))
        assertNull(browserCaptionTextAt(cues,sample().copy(positionMs=499)))
        assertEquals("First",browserCaptionTextAt(cues,sample().copy(positionMs=500)))
        assertEquals("First\nOverlap",browserCaptionTextAt(cues,sample().copy(positionMs=1100)))
        assertNull(browserCaptionTextAt(cues,sample().copy(positionMs=2000)))
    }
}
