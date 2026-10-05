package com.mangalens.ui.video

import org.junit.Assert.*
import org.junit.Test

class VideoSourcePolicyTest {
    @Test fun htmlWatchPagesUseWebsitePlayer() {
        assertTrue(VideoSourcePolicy.isSourcePage("https://example.com/id/video/title"))
        assertTrue(VideoSourcePolicy.isSourcePage("https://example.com/watch?v=123"))
        assertTrue(VideoSourcePolicy.isSourcePage("https://example.com/player.html"))
    }
    @Test fun directAndSignedOpaqueMediaStillUseMedia3() {
        assertFalse(VideoSourcePolicy.isSourcePage("https://example.com/video/file.MP4?token=123"))
        assertFalse(VideoSourcePolicy.isSourcePage("https://example.com/manifest.m3u8?x=1"))
        assertFalse(VideoSourcePolicy.isSourcePage("https://cdn.example.com/token/123?signature=abc"))
    }

    @Test fun webpageMediaDiscoveryPrefersManifestOverSegments() {
        val preferred = VideoSourcePolicy.preferredMediaUrl(
            listOf(
                "https://cdn.example.com/segment-0001.ts?token=abc",
                "https://cdn.example.com/master.m3u8?token=abc",
                "https://cdn.example.com/movie-720p.mp4?token=abc"
            )
        )
        assertEquals("https://cdn.example.com/master.m3u8?token=abc", preferred)
        assertTrue(VideoSourcePolicy.isLikelyMediaRequest(preferred!!))
        assertNull(VideoSourcePolicy.preferredMediaUrl(listOf("https://example.com/watch/title")))
    }
    @Test fun recognisesExtensionlessYouTubePlaybackRequest() {
        val url = "https://rr1---sn.example.googlevideo.com/videoplayback?id=abc&mime=video%2Fmp4&itag=137"
        assertTrue(VideoSourcePolicy.isLikelyMediaRequest(url, mapOf("Accept" to "video/*")))
    }

    @Test fun rejectsYouTubeAudioOnlyRequestAsPrimaryVideo() {
        val url = "https://rr1---sn.example.googlevideo.com/videoplayback?id=abc&mime=audio%2Fmp4&itag=140"
        assertFalse(VideoSourcePolicy.isLikelyMediaRequest(url, mapOf("Accept" to "*/*")))
    }

    @Test fun recognisesExtensionlessInstagramCdnVideo() {
        val url = "https://scontent.cdninstagram.com/o1/v/t16/f2/m82/fixture?efg=video"
        assertTrue(
            VideoSourcePolicy.isLikelyMediaRequest(
                url,
                mapOf("Range" to "bytes=0-", "Accept" to "*/*")
            )
        )
    }

    @Test fun treatsInstagramAndYouTubePagesAsSourcePages() {
        assertTrue(VideoSourcePolicy.isSourcePage("https://www.instagram.com/reel/ABC123/"))
        assertTrue(VideoSourcePolicy.isSourcePage("https://www.youtube.com/watch?v=ABC123"))
    }

}
