package com.mangalens.download

import org.junit.Assert.*
import org.junit.Test

class SiteMediaInfoParserTest {
    @Test fun sourceDurationSurvivesBothCombinedAndSeparateTrackSelection() {
        val combined = SiteMediaInfoParser.parse(
            """{"url":"https://cdn.example/video.mp4","duration":600.25}""", "https://example.com/watch/1")!!
        assertEquals(600_250_000L, combined.expectedDurationUs)
        val separate = SiteMediaInfoParser.parse("""{"duration":3600,"requested_formats":[
          {"url":"https://cdn.example/video.mp4","ext":"mp4","vcodec":"avc1","acodec":"none"},
          {"url":"https://cdn.example/audio.m4a","ext":"m4a","vcodec":"none","acodec":"aac"}
        ]}""", "https://example.com/watch/1", true)!!
        assertEquals(3_600_000_000L, separate.expectedDurationUs)
        val absent = SiteMediaInfoParser.parse("""{"url":"https://cdn.example/video.mp4"}""", "https://example.com")!!
        assertNull(absent.expectedDurationUs)
    }

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
    @Test fun downloadSelectionRetainsBothTracksAndNeverPretendsVideoOnlyHasAudio() {
        val json = """{"title":"Fixture","requested_formats":[
          {"url":"https://cdn.example/video.mp4","ext":"mp4","height":1080,"vcodec":"avc1","acodec":"none"},
          {"url":"https://cdn.example/audio.m4a","ext":"m4a","vcodec":"none","acodec":"mp4a"}
        ]}"""
        assertNull(SiteMediaInfoParser.parse(json, "https://example.com/video"))
        val selected = SiteMediaInfoParser.parse(json, "https://example.com/video", true)!!
        assertEquals(1080, selected.detectedHeight)
        assertEquals("https://cdn.example/audio.m4a", selected.audioUrl)
        assertEquals("video/mp4", selected.mimeType)
    }

    @Test fun siteQualityPixelValueCanRecoverMissingHeight() {
        val result = SiteMediaInfoParser.parse(
            """{"url":"https://cdn.example/get_file/video.mp4","ext":"mp4","vcodec":"avc1","acodec":"aac","quality":2160}""",
            "https://rule34video.com/video/1/example/"
        )!!
        assertEquals(2160, result.detectedHeight)
    }


    @Test fun acceptsHighResolutionMp4VideoWithSeparateAacAudio() {
        val json = """{"title":"4K fixture","requested_formats":[
          {"url":"https://cdn.example/video-av1.mp4","ext":"mp4","height":2160,"vcodec":"av01.0.12M.08","acodec":"none"},
          {"url":"https://cdn.example/audio.m4a","ext":"m4a","vcodec":"none","acodec":"mp4a.40.2"}
        ],"http_headers":{"User-Agent":"fixture"}}"""
        val selected = SiteMediaInfoParser.parse(json, "https://www.youtube.com/watch?v=fixture", true)!!
        assertEquals(2160, selected.detectedHeight)
        assertEquals("https://cdn.example/video-av1.mp4", selected.url)
        assertEquals("https://cdn.example/audio.m4a", selected.audioUrl)
    }


}

