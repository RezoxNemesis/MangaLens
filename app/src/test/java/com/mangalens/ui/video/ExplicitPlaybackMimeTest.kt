package com.mangalens.ui.video

import com.mangalens.download.MediaTransportMime
import com.mangalens.download.SiteMediaInfoParser
import com.mangalens.ui.web.captureBrowserVideoSelection
import com.mangalens.ui.web.captureResolvedBrowserMedia
import org.junit.Assert.*
import org.junit.Test

/** Authored controls. Execution is deferred until the assembled implementation phase. */
class ExplicitPlaybackMimeTest {
    private val url = "https://cdn.example/manifest?signature=private"
    @Test fun suffixlessHlsParserMimeSurvivesActualAcceptedSelection() {
        val media = SiteMediaInfoParser.parse("""{"url":"$url","protocol":"m3u8_native","ext":"mp4"}""", "https://example.com/watch/1")!!
        val accepted = VideoPlaybackPublication.capture(media.url, emptyMap(), media.sourcePageUrl, null, emptyMap(), videoMimeType = media.mimeType)
        assertEquals("application/x-mpegURL", accepted.videoMimeType)
        assertEquals(url, accepted.videoUrl)
    }
    @Test fun suffixlessDashBrowserHandoffRetainsExactTransport() {
        val resolved = SiteMediaInfoParser.parse("""{"url":"$url","protocol":"dash","ext":"mp4"}""", "https://example.com/watch/1")!!
        val media = captureResolvedBrowserMedia(resolved, emptyMap(), emptyMap(), null)
        val accepted = captureBrowserVideoSelection(media, "https://example.com/watch/1")
        assertEquals("application/dash+xml", accepted.videoMimeType)
    }
    @Test fun combinedLegacyCaptureKeepsMissingMimeAndExactEmptySuffix() {
        val captured = VideoPlaybackPublication.capture(url, emptyMap(), null, null, emptyMap())
        assertNull(captured.videoMimeType); assertNull(captured.audioMimeType)
        assertEquals("", MediaTransportMime.identitySuffix(null, null))
    }
    @Test fun mediaTypeAliasesAndParametersUseOneCanonicalIdentity() {
        assertEquals("application/x-mpegURL", MediaTransportMime.capture("APPLICATION/VND.APPLE.MPEGURL; charset=utf-8"))
        assertEquals(MediaTransportMime.identitySuffix("application/x-mpegURL", null), MediaTransportMime.identitySuffix("application/vnd.apple.mpegurl", null))
    }
    @Test fun explicitMimeParticipatesInSameUrlStreamReplacement() {
        val legacy = PlaybackStreamIdentity(url)
        val hls = PlaybackStreamIdentity(url, videoMimeType = "application/x-mpegURL")
        assertFalse(legacy.equivalentTo(hls))
        assertFalse(hls.equivalentTo(hls.copy(videoMimeType = "application/dash+xml")))
        assertTrue(hls.equivalentTo(hls.copy(videoMimeType = "application/vnd.apple.mpegurl")))
    }
    @Test fun staleReadyObservationCannotPublishAnotherMimeForSameUri() {
        val old = VideoPlaybackPublication.capture(url, emptyMap(), null, null, emptyMap(), videoMimeType = "video/mp4")
        val next = old.copy(videoMimeType = "application/x-mpegURL")
        assertNull(VideoPlaybackPublication.acceptReady(next, VideoReadyObservation(old, 2_000_000L)))
        assertEquals(2_000_000L, VideoPlaybackPublication.acceptReady(next, VideoReadyObservation(next, 2_000_000L))!!.durationUs)
    }
    @Test fun separatedAudioMimeRemainsOnSameCompleteTuple() {
        val media = SiteMediaInfoParser.parse("""{"requested_formats":[{"url":"$url","ext":"webm","vcodec":"vp9","acodec":"none"},{"url":"https://cdn.example/audio","ext":"webm","vcodec":"none","acodec":"opus"}]}""", "https://example.com/watch/1", true)!!
        val captured = VideoPlaybackPublication.capture(media.url, emptyMap(), null, media.audioUrl, emptyMap(), videoMimeType = media.mimeType, audioMimeType = media.audioMimeType)
        assertEquals("video/webm", captured.videoMimeType); assertEquals("audio/webm", captured.audioMimeType)
        assertFalse(PlaybackStreamIdentity(url, audioUrl = media.audioUrl, audioMimeType = "audio/webm").equivalentTo(PlaybackStreamIdentity(url, audioUrl = media.audioUrl, audioMimeType = "audio/mp4")))
    }
    @Test fun sniffedHlsKindPreservesKnownManifestDispatchWithoutResolver() {
        val accepted = captureBrowserVideoSelection(SniffedMedia(url, emptyMap(), "HLS"), "https://example.com/watch/1")
        assertEquals("application/x-mpegURL", accepted.videoMimeType)
    }
    @Test fun unsupportedDocumentMimeCannotBecomeVideoTransport() {
        for (mime in listOf("text/html", "image/jpeg", "application/javascript", "video/mp4\nignored")) {
            try { VideoPlaybackPublication.capture(url, emptyMap(), null, null, emptyMap(), videoMimeType = mime); fail("Rejected non-media MIME $mime") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun unknownOctetStreamRetainsLegacyUriInference() {
        assertNull(MediaTransportMime.capture("application/octet-stream"))
        assertEquals("", MediaTransportMime.identitySuffix("application/octet-stream", null))
    }
    @Test fun cancelledRefreshCannotApplySameUriWithChangedMime() {
        val requests = PlaybackRefreshRequests(); val token = requests.begin(); requests.cancel()
        var applied = false
        assertFalse(requests.publish(token, PlaybackStreamIdentity(url), PlaybackStreamIdentity(url, videoMimeType = "application/x-mpegURL"), { applied = true }, {}))
        assertFalse(applied)
    }
    @Test fun absentAudioCannotCarryASeparateAudioMimeIdentity() {
        val captured = VideoPlaybackPublication.capture(url, emptyMap(), null, null, emptyMap(), audioMimeType = "audio/mp4")
        assertNull(captured.audioMimeType)
    }
}
