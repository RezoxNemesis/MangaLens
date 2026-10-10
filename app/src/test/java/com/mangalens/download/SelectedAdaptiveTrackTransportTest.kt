package com.mangalens.download

import org.junit.Assert.*
import org.junit.Test

/** Authored UNRUN. A selected adaptive pair is playable as two real sources, never one completed file. */
class SelectedAdaptiveTrackTransportTest {
    private fun pair(videoProtocol: String, audioProtocol: String) = SiteMediaInfoParser.parse("""{
      "duration":8,"extractor_key":"Youtube","requested_formats":[
        {"format_id":"video","url":"https://fixture.invalid/video","ext":"mp4","height":1080,"protocol":"$videoProtocol","vcodec":"avc1","acodec":"none"},
        {"format_id":"audio","url":"https://fixture.invalid/audio","ext":"m4a","protocol":"$audioProtocol","vcodec":"none","acodec":"mp4a"}]
    }""", "https://fixture.invalid/source", true)!!
    @Test fun pairedHlsRetainsActualVideoAndAudioAdaptiveMime() {
        val media = pair("m3u8_native", "m3u8_native")
        assertEquals("application/x-mpegURL", media.mimeType); assertEquals("application/x-mpegURL", media.audioMimeType)
        assertEquals(1080, media.detectedHeight); assertNotNull(media.audioUrl)
        val failure = runCatching { SelectedDownloadTransportPolicy.requireSupported(media) }.exceptionOrNull()
        assertTrue(failure is MediaSourceException); assertEquals(SelectedDownloadTransportPolicy.PAIRED_ADAPTIVE, failure!!.message)
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

}
