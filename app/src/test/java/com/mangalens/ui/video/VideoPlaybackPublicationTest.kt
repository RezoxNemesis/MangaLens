package com.mangalens.ui.video

import org.junit.Assert.*
import org.junit.Test

class VideoPlaybackPublicationTest {
    private fun pair() = VideoPlaybackPublication.capture("https://cdn.example/v.mp4", mapOf("Cookie" to "video-only"),
        "https://page.example/watch", "https://audio.example/a.m4a", mapOf("Cookie" to "audio-only"))

    @Test fun unresolvedDurationNeverSilentlyDowngradesSplitSelectionToSilentVideo() {
        assertNull(pair().toOrezSelection())
    }

    @Test fun readyPairPublishesIndependentHeadersAndSameOpaqueResolutionIdentity() {
        val captured = pair().copy(durationUs = 5_000_000)
        val selected = captured.toOrezSelection()!!
        assertEquals(captured.resolutionId, selected.resolutionId)
        assertEquals(captured.resolutionId, selected.audio!!.resolutionId)
        assertEquals(captured.audioUrl, selected.audio!!.uri)
        assertEquals("audio-only", selected.audio!!.headers["Cookie"])
        assertEquals("video-only", selected.headers["Cookie"])
        assertEquals(5_000_000L, selected.expectedDurationUs)
    }

    @Test fun sameUrlsResolvedAgainRemainDistinctCapturedRequests() {
        val first = pair().copy(durationUs = 5_000_000).toOrezSelection()!!
        val second = pair().copy(durationUs = 5_000_000).toOrezSelection()!!
        assertNotEquals(first.sourceId, second.sourceId)
    }

    @Test fun lateReadyAndChangedHeaderTupleCannotUpdateNewPairDuration() {
        val old = pair(); val newer = pair()
        assertNull(VideoPlaybackPublication.acceptReady(newer, VideoReadyObservation(old, 5_000_000)))
        assertNull(VideoPlaybackPublication.acceptReady(old, VideoReadyObservation(old.copy(audioHeaders = mapOf("Cookie" to "changed")), 5_000_000)))
        assertEquals(5_000_000L, VideoPlaybackPublication.acceptReady(old, VideoReadyObservation(old, 5_000_000))!!.durationUs)
    }

    @Test fun playerRevisionUriReadyAndRealFiniteDurationAreAllRequired() {
        val current = pair()
        fun ready(bound: Long = 7, actual: Long = 7, url: String? = current.videoUrl, state: Boolean = true,
            millis: Long = 5_000, live: Boolean = false, dynamic: Boolean = false) =
            VideoPlaybackPublication.readyObservation(current, bound, actual, url, state, millis, live, dynamic)
        assertNotNull(ready())
        assertNull(ready(actual = 8)); assertNull(ready(url = "https://new.example/video")); assertNull(ready(state = false))
        assertNull(ready(millis = Long.MIN_VALUE + 1)); assertNull(ready(millis = 0)); assertNull(ready(millis = Long.MAX_VALUE))
        assertNull(ready(live = true)); assertNull(ready(dynamic = true))
    }

    @Test fun replacementChecksOldFullTupleAndClearsDurationUnderFreshIdentity() {
        val old = pair().copy(durationUs = 5_000_000); val replacement = pair()
        assertTrue(VideoPlaybackPublication.canReplace(old, old, replacement))
        assertFalse(VideoPlaybackPublication.canReplace(old, old.copy(audioHeaders = emptyMap()), replacement))
        assertFalse(VideoPlaybackPublication.canReplace(replacement, old, pair()))
        assertFalse(VideoPlaybackPublication.canReplace(old, old, replacement.copy(resolutionId = old.resolutionId)))
        assertFalse(VideoPlaybackPublication.canReplace(old, old, replacement.copy(durationUs = 5_000_000)))
    }

    @Test fun captureDetachesMutableMapsAndRejectsUnsafeAudioInsteadOfDroppingIt() {
        val video = mutableMapOf("Cookie" to "v"); val audio = mutableMapOf("Cookie" to "a")
        val captured = VideoPlaybackPublication.capture("https://cdn.example/v", video, null, "https://cdn.example/a", audio)
        video["Cookie"] = "v2"; audio["Cookie"] = "a2"
        assertEquals("v", captured.videoHeaders["Cookie"]); assertEquals("a", captured.audioHeaders["Cookie"])
        assertTrue(runCatching { VideoPlaybackPublication.capture("https://cdn.example/v", video, null, "file:///private/a", audio) }.isFailure)
    }

    @Test fun ordinaryUnsplitPlayableSelectionKeepsItsExplicitAuthority() {
        val captured = VideoPlaybackPublication.capture("https://cdn.example/v.mp4", emptyMap(), "https://page.example/watch", null, emptyMap())
        assertNotNull(captured.toOrezSelection())
        assertNull(captured.toOrezSelection()!!.audio)
        assertNotNull(VideoPlaybackPublication.capture("HTTPS://cdn.example/v.mp4", emptyMap(), null, null, emptyMap()).toOrezSelection())
    }
}
