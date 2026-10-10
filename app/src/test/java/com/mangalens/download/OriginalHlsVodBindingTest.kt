package com.mangalens.download

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Authored UNRUN: selected protocol and complete private tuple admission only. */
class OriginalHlsVodBindingTest {
    private val selected = """{"extractor_key":"fixture","duration":4,"requested_formats":[{"url":"https://fixture.invalid/video.m3u8?synthetic=one","format_id":"hls-v1080","protocol":"m3u8_native","ext":"mp4","vcodec":"avc1","acodec":"none","height":1080},{"url":"https://fixture.invalid/audio.m3u8?synthetic=one","format_id":"hls-a","protocol":"m3u8_native","ext":"m4a","vcodec":"none","acodec":"mp4a"}]}"""
    private val playlist = "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:4\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:4,\nmedia.m4s\n#EXT-X-ENDLIST"
    private fun hints() = SiteMediaInfoParser.parse(selected, "https://fixture.invalid/page", true)!!
    private fun complete(): ResolvedMediaLink {
        val m = hints()
        fun capture(s: CapturedHlsTrackSource) = OriginalHlsVodPlaylist.capture(s, s.sourceUrl, "application/vnd.apple.mpegurl", playlist.toByteArray(), 4_000_000)
        return m.copy(videoFragments = capture(m.videoHlsSource!!), audioFragments = capture(m.audioHlsSource!!), videoHlsSource = null, audioHlsSource = null)
    }
    @Test fun actualProtocolCreatesTypedHintsWithoutFabricatedFragmentsOrManifestMime() {
        val m = hints()
        assertEquals("video/mp4", m.mimeType); assertEquals("audio/mp4", m.audioMimeType)
        assertEquals("m3u8_native", m.videoHlsSource!!.protocol); assertNotNull(m.audioHlsSource)
        assertNull(m.videoFragments); assertNull(m.audioFragments)
    }
    @Test fun playlistLookingUrlDoesNotSelectHlsWhenActualProtocolIsHttps() {
        val m = SiteMediaInfoParser.parse(selected.replace("m3u8_native", "https"), "https://fixture.invalid/page", true)!!
        assertNull(m.videoHlsSource); assertNull(m.audioHlsSource)
    }
    @Test fun unresolvedHintsAndPartialPairNeverBecomeBoundPrivateSource() {
        assertTrue(runCatching { requireBoundMediaSource(hints().url, hints()) }.isFailure)
        val m = complete(); assertTrue(runCatching { requireBoundMediaSource(m.url, m.copy(audioFragments = null)) }.isFailure)
    }
    @Test fun completePairRetainsTypedTransportBindingAndDifferentPlaylistCannotReuseIt() {
        val m = complete(); assertSame(m, requireBoundMediaSource(m.url, m))
        val kind = OriginalFragmentTransport.downloadKind(m)!!
        OriginalFragmentTransport.requireDownloadBinding(kind, m)
        val changed = m.copy(videoFragments = m.videoFragments!!.copy(hls = m.videoFragments!!.hls!!.copy(playlistSha256 = "a".repeat(64))))
        assertTrue(runCatching { OriginalFragmentTransport.requireDownloadBinding(kind, changed) }.isFailure)
    }
    @Test fun changedSignatureAudioSourceFormatOrRootDurationCannotReusePair() {
        val m = complete()
        for (changed in listOf(m.copy(url = m.url + "&synthetic=two"), m.copy(audioUrl = "https://fixture.invalid/new-audio"),
            m.copy(originalSelection = m.originalSelection!!.copy(audioFormatId = "other")), m.copy(expectedDurationUs = 5_000_000)))
            assertTrue(runCatching { requireBoundMediaSource(changed.url, changed) }.isFailure)
    }
    @Test fun malformedSelectedHlsPairFailsTypedRatherThanReturningGenericFallbackNull() {
        val missingUrl = JSONObject(selected).also { it.getJSONArray("requested_formats").getJSONObject(1).remove("url") }
        val ambiguous = JSONObject(selected).also { it.getJSONArray("requested_formats").getJSONObject(1).put("vcodec", "avc1") }
        val wrongUrl = JSONObject(selected).also { it.getJSONArray("requested_formats").getJSONObject(0).put("url", "file:///private") }
        val many = JSONObject(selected).also { val rows = it.getJSONArray("requested_formats"); rows.put(JSONObject(rows.getJSONObject(1).toString())) }
        val nonObject = JSONObject(selected).also { it.getJSONArray("requested_formats").put(1, "synthetic malformed selected track") }
        val nullPart = JSONObject(selected).also { it.getJSONArray("requested_formats").put(1, JSONObject.NULL) }
        for (value in listOf(missingUrl, ambiguous, wrongUrl, many, nonObject, nullPart)) {
            val error = runCatching { SiteMediaInfoParser.parse(value.toString(), "https://fixture.invalid/page", true) }.exceptionOrNull()
            assertTrue(error is OriginalHlsSelectedSourceException)
        }
        val ordinary = ambiguous.toString().replace("m3u8_native", "https")
        assertNull(SiteMediaInfoParser.parse(ordinary, "https://fixture.invalid/page", true))
    }
    @Test fun unknownHlsProtocolTsLiveUpcomingAndDrmFactsCannotBecomeHints() {
        for (value in listOf(selected.replace("m3u8_native", "m3u8_unknown"), selected.replace("\"ext\":\"mp4\"", "\"ext\":\"ts\""),
            JSONObject(selected).put("is_live", true).toString(), JSONObject(selected).put("is_upcoming", true).toString(), JSONObject(selected).put("has_drm", true).toString()))
            assertTrue(runCatching { SiteMediaInfoParser.parse(value, "https://fixture.invalid/page", true) }.getOrNull() == null)
    }
}
