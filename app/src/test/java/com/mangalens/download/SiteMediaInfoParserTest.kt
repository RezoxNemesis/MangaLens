package com.mangalens.download

import org.junit.Assert.*
import org.junit.Test

class SiteMediaInfoParserTest {
    @Test fun combinesNativeSiteMetadataWithScopedContext() {
        val result = SiteMediaInfoParser.parse("""{
          "url":"https://cdn.example/stream?signature=secret", "ext":"mp4", "height":1080,
          "vcodec":"avc1", "acodec":"aac", "title":"A reel", "extractor_key":"Instagram",
          "http_headers":{"Referer":"https://instagram.com/reel/123", "Cookie":"session=ok", "Authorization":"secret"}
        }""", "https://instagram.com/reel/123")!!
        assertEquals("video/mp4", result.mimeType)
        assertEquals(1080, result.detectedHeight)
        assertEquals("Instagram", result.provider)
        assertEquals("https://instagram.com/reel/123", result.sourcePageUrl)
        assertEquals("session=ok", result.headers["Cookie"])
        assertFalse(result.headers.containsKey("Authorization"))
    }

    @Test fun adaptiveProtocolWorksWithoutFilenameSuffix() {
        val result = SiteMediaInfoParser.parse("""{"url":"https://cdn.example/manifest?id=123","protocol":"m3u8_native","ext":"mp4"}""", "https://example.com/video/1")!!
        assertEquals("application/x-mpegURL", result.mimeType)
    }

    @Test fun rejectsProtectedSplitAudioOnlyAndUnsafeSources() {
        for (fields in listOf(
            "\"has_drm\":true", "\"vcodec\":\"none\"", "\"acodec\":\"none\"",
            "\"requested_formats\":[{},{}]", "\"_type\":\"playlist\""
        )) {
            assertNull(SiteMediaInfoParser.parse("{\"url\":\"https://cdn.example/video.mp4\",$fields}", "https://example.com/video"))
        }
        for (url in listOf("file:///private", "javascript:alert(1)", "https://user:secret@example.com/video")) {
            assertNull(SiteMediaInfoParser.parse("{\"url\":\"$url\"}", "https://example.com"))
        }
    }
}
