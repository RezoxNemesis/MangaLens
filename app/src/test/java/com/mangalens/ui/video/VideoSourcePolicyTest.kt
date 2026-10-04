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
}
