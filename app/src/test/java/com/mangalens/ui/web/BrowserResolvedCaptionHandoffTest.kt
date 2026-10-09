package com.mangalens.ui.web

import com.mangalens.download.ProviderCaptionDiscovery
import com.mangalens.download.ProviderCaptionInventory
import com.mangalens.download.ResolvedMediaLink
import com.mangalens.ui.video.VideoPlaybackPublication
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BrowserResolvedCaptionHandoffTest {
    private val page = "https://www.youtube.com/watch?v=abcdefghijk"
    private fun discovered() = requireNotNull(ProviderCaptionDiscovery.fromMetadata(
        JSONObject().put("id", "abcdefghijk").put("language", "ko").put("duration", 12)
            .put("subtitles", JSONObject().put("en", JSONArray().put(JSONObject()
                .put("ext", "vtt")
                .put("url", "https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en")))),
        page, "en"
    ))

    @Test fun actualDiscoveredCaptionsReachAcceptedVideoAndOrezWithChosenAudio() {
        val inventory = discovered()
        val resolved = ResolvedMediaLink("https://media.invalid/video.mp4", "video/mp4",
            "youtube", sourcePageUrl = page, audioUrl = "https://media.invalid/audio.m4a",
            providerCaptions = inventory)
        val media = captureResolvedBrowserMedia(resolved,
            mapOf("Referer" to page, "Cookie" to "video-session=fixture"),
            mapOf("Referer" to page, "Cookie" to "audio-session=fixture"), "Browser title")
        assertEquals(inventory, media.providerCaptions)
        assertEquals("en", media.providerCaptions?.selectedAudioLanguage)
        val accepted = captureBrowserVideoSelection(media, page).copy(durationUs = 12_000_000)
        val orez = requireNotNull(accepted.toOrezSelection())
        assertEquals(inventory, accepted.providerCaptions)
        assertEquals(inventory, orez.providerCaptions)
        assertEquals(accepted.resolutionId, orez.audio?.resolutionId)
        assertEquals(media.audioHeaders, orez.audio?.headers)
        assertEquals(media.headers, orez.headers)
        assertEquals("Browser title", media.title)
    }

    @Test fun callerMutationCannotChangeTheCapturedAudioOrProviderTuple() {
        val discovered = discovered()
        val mutableTracks = discovered.tracks.toMutableList()
        val inventory: ProviderCaptionInventory = discovered.copy(tracks = mutableTracks)
        val videoHeaders = mutableMapOf("Referer" to page)
        val audioHeaders = mutableMapOf("Cookie" to "audio-session=fixture")
        val media = captureResolvedBrowserMedia(ResolvedMediaLink("https://media.invalid/video.mp4",
            "video/mp4", audioUrl = "https://media.invalid/audio.m4a", providerCaptions = inventory),
            videoHeaders, audioHeaders, null)
        mutableTracks.clear(); videoHeaders.clear(); audioHeaders.clear()
        assertEquals(1, requireNotNull(media.providerCaptions).tracks.size)
        assertEquals(page, media.headers["Referer"])
        assertEquals("audio-session=fixture", media.audioHeaders["Cookie"])
    }

    @Test fun ordinaryResolvedMediaKeepsCaptionAvailabilityUnknown() {
        val media = captureResolvedBrowserMedia(ResolvedMediaLink("https://media.invalid/video.mp4",
            "application/x-mpegURL"), emptyMap(), emptyMap(), null)
        assertNull(media.providerCaptions)
        assertEquals("HLS", media.kind)
        val accepted = captureBrowserVideoSelection(media, page)
        assertNull(accepted.providerCaptions)
        assertNull(accepted.durationUs)
        assertNull(accepted.toOrezSelection()?.providerCaptions)
    }
}
