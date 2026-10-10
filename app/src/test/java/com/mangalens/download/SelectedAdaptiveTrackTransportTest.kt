package com.mangalens.download

import org.junit.Assert.*
import org.junit.Test

/** Typed selected hints are incomplete until both real finite playlists are captured. */
class SelectedAdaptiveTrackTransportTest {
    private fun pair(videoProtocol: String, audioProtocol: String) = SiteMediaInfoParser.parse("""{
      "duration":8,"extractor_key":"Youtube","requested_formats":[
        {"format_id":"video","url":"https://fixture.invalid/video","ext":"mp4","height":1080,"protocol":"$videoProtocol","vcodec":"avc1","acodec":"none"},
        {"format_id":"audio","url":"https://fixture.invalid/audio","ext":"m4a","protocol":"$audioProtocol","vcodec":"none","acodec":"mp4a"}]
    }""", "https://fixture.invalid/source", true)!!
    @Test fun pairedHlsRetainsTypedProtocolHintsAndRefusesUncapturedAdmission() {
        val media = pair("m3u8_native", "m3u8_native")
        assertEquals("video/mp4", media.mimeType); assertEquals("audio/mp4", media.audioMimeType)
        assertEquals("m3u8_native", media.videoHlsSource!!.protocol); assertEquals("m3u8_native", media.audioHlsSource!!.protocol)
        assertNull(media.videoFragments); assertNull(media.audioFragments)
        assertEquals(1080, media.detectedHeight); assertNotNull(media.audioUrl)
        val failure = runCatching { SelectedDownloadTransportPolicy.requireSupported(media) }.exceptionOrNull()
        assertTrue(failure is MediaSourceException); assertEquals(SelectedDownloadTransportPolicy.PENDING_HLS, failure!!.message)
    }
    @Test fun adaptiveAudioCannotBeDroppedWhenVideoIsOrdinaryHttp() {
        val media = pair("https", "m3u8_native")
        assertEquals("video/mp4", media.mimeType)
        assertTrue(runCatching { SelectedDownloadTransportPolicy.requireSupported(media) }.exceptionOrNull() is MediaSourceException)
    }
    @Test fun selectedCompleteHttpPairKeepsExistingOriginalDownloadAdmission() {
        val media = pair("https", "https")
        assertEquals("video/mp4", media.mimeType); assertEquals("audio/mp4", media.audioMimeType)
        SelectedDownloadTransportPolicy.requireSupported(media)
    }
    @Test fun singleCombinedAdaptiveSourceKeepsExistingCacheBackedDownload() {
        val media = ResolvedMediaLink("https://fixture.invalid/playlist", "application/x-mpegURL", "fixture")
        SelectedDownloadTransportPolicy.requireSupported(media); SelectedDownloadTransportPolicy.requireSupported(null)
        assertNull(media.audioUrl)
    }
    @Test fun workerRefreshRefusesUnplannedAdaptivePlaylistBeforeItCanBecomeTrackBytes() {
        val media = ResolvedMediaLink("https://fixture.invalid/manifest/video.mpd", "application/dash+xml", "fixture")
        SelectedDownloadTransportPolicy.requireSupported(media)
        val failure = runCatching { SelectedDownloadTransportPolicy.requireWorkerCompatible(media) }.exceptionOrNull()
        assertTrue(failure is MediaSourceException)
        assertEquals(SelectedDownloadTransportPolicy.WORKER_ADAPTIVE, failure!!.message)
    }
    @Test fun workerRefreshRefusesAdaptivePairWhileCompleteHttpPairRemainsCompatible() {
        assertTrue(runCatching { SelectedDownloadTransportPolicy.requireWorkerCompatible(pair("m3u8_native", "https")) }.exceptionOrNull() is MediaSourceException)
        SelectedDownloadTransportPolicy.requireWorkerCompatible(pair("https", "https"))
    }

    @Test fun actualCapturedFiniteHlsPairKeepsFullPlanAdmissionAndRefusesEitherPendingHint() {
        val selected = pair("m3u8_native", "m3u8_native")
        val playlist = "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:8\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:8,\nmedia.m4s\n#EXT-X-ENDLIST"
        fun capture(source: CapturedHlsTrackSource) = OriginalHlsVodPlaylist.capture(source, source.sourceUrl,
            "application/vnd.apple.mpegurl", playlist.toByteArray(), 8_000_000L)
        val complete = selected.copy(videoFragments = capture(selected.videoHlsSource!!),
            audioFragments = capture(selected.audioHlsSource!!), videoHlsSource = null, audioHlsSource = null)
        assertSame(complete, requireBoundMediaSource(complete.url, complete))
        assertNotNull(OriginalFragmentTransport.downloadKind(complete))
        SelectedDownloadTransportPolicy.requireSupported(complete)
        SelectedDownloadTransportPolicy.requireWorkerCompatible(complete)
        for (pending in listOf(complete.copy(videoHlsSource = selected.videoHlsSource),
            complete.copy(audioHlsSource = selected.audioHlsSource))) {
            assertTrue(runCatching { SelectedDownloadTransportPolicy.requireSupported(pending) }.exceptionOrNull() is MediaSourceException)
            assertTrue(runCatching { SelectedDownloadTransportPolicy.requireWorkerCompatible(pending) }.exceptionOrNull() is MediaSourceException)
        }
    }

}
