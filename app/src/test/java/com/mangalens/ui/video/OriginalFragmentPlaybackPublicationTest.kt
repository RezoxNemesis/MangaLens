package com.mangalens.ui.video

import com.mangalens.download.OriginalFragmentPlan
import com.mangalens.download.OriginalMediaFragment
import com.mangalens.download.OriginalFragmentTransport
import org.junit.Assert.*
import org.junit.Test

/** Authored UNRUN. Old identity is exact when plans are absent; a changed sequence is a new source. */
class OriginalFragmentPlaybackPublicationTest {
    private val url = "https://fixture.invalid/base"
    private fun plan(rows: List<OriginalMediaFragment> = listOf(OriginalMediaFragment("https://fixture.invalid/init", expectedBytes = 8), OriginalMediaFragment("https://fixture.invalid/one", expectedBytes = 11))) = OriginalFragmentPlan(url, "v1080", "video/mp4", 8_000_000L, rows)
    private fun capture(p: OriginalFragmentPlan?) = VideoPlaybackPublication.capture(url, emptyMap(), "https://fixture.invalid/page", null, emptyMap(), videoMimeType = "video/mp4", videoFragments = p)
    @Test fun reorderedSameAnchorCannotReuseReadyObservationOrRefreshIdentity() {
        val first = capture(plan()); val second = first.copy(videoFragments = plan().copy(fragments = plan().fragments.reversed()))
        assertFalse(VideoPlaybackPublication.sameTuple(first, second))
        assertNull(VideoPlaybackPublication.acceptReady(second, VideoReadyObservation(first, 8_000_000)))
        assertFalse(PlaybackStreamIdentity(url, videoFragments = first.videoFragments).equivalentTo(PlaybackStreamIdentity(url, videoFragments = second.videoFragments)))
    }
    @Test fun absentPlansPreserveLegacyRequestIdentity() {
        assertEquals("", OriginalFragmentTransport.identitySuffix(null, null))
        assertTrue(PlaybackStreamIdentity(url).equivalentTo(PlaybackStreamIdentity(url)))
    }
    @Test fun differentSourceOrMimeCannotBorrowCapturedPlan() {
        assertTrue(runCatching { OriginalFragmentTransport.capture(plan(), url + "?changed", "video/mp4") }.isFailure)
        assertTrue(runCatching { OriginalFragmentTransport.capture(plan(), url, "video/webm") }.isFailure)
        assertTrue(runCatching { OriginalFragmentTransport.capture(plan(), null, "video/mp4") }.isFailure)
    }
    @Test fun acceptedSourceHasNoMutableCallerFragmentAlias() {
        val rows = plan().fragments.toMutableList(); val captured = capture(plan(rows)); val sha = captured.videoFragments!!.sha256()
        rows.reverse(); rows.clear()
        assertEquals(sha, captured.videoFragments!!.sha256()); assertEquals(2, captured.videoFragments!!.fragments.size)
        assertTrue(runCatching { (captured.videoFragments!!.fragments as MutableList<OriginalMediaFragment>).clear() }.isFailure)
    }
}
