package com.mangalens.download

import org.junit.Assert.*
import org.junit.Test

class OriginalMediaParserTest {
    @Test fun highestWebmVideoAndOpusRetainBothSourceContextsAndDuration() {
        val source = "https://www.youtube.com/watch?v=fixture"
        val media = SiteMediaInfoParser.parse("""{
          "duration":600.25,"title":"Original fixture","extractor_key":"Youtube",
          "requested_formats":[
            {"url":"https://video.example/highest","ext":"webm","height":2160,
             "vcodec":"vp9","acodec":"none","http_headers":{"Referer":"$source","Cookie":"video=one"}},
            {"url":"https://audio.example/original","ext":"webm","vcodec":"none","acodec":"opus",
             "http_headers":{"Referer":"$source","Cookie":"audio=two"}}
          ]
        }""", source, true)
        assertNotNull("Highest original WebM/Opus pair was discarded", media)
        media!!
        assertEquals("https://video.example/highest", media.url)
        assertEquals("https://audio.example/original", media.audioUrl)
        assertEquals("video/webm", media.mimeType)
        assertEquals(2160, media.detectedHeight)
        assertEquals(600_250_000L, media.expectedDurationUs)
        assertEquals("video=one", media.headers["Cookie"])
        assertEquals("audio=two", media.audioHeaders["Cookie"])
        assertEquals(source, media.sourcePageUrl)
    }

    @Test fun highestCombinedVideoCanPairWithHigherQualityOriginalAudio() {
        val media = SiteMediaInfoParser.parse("""{"requested_formats":[
          {"url":"https://video.example/2160-combined","ext":"mp4","height":2160,
           "vcodec":"avc1.640033","acodec":"mp4a.40.2"},
          {"url":"https://audio.example/opus","ext":"webm","vcodec":"none","acodec":"opus"}
        ]}""", "https://example.com/watch/fixture", true)
        assertNotNull("Combined high video must not force a lower split-video representation", media)
        assertEquals(2160, media!!.detectedHeight)
        assertEquals("https://audio.example/opus", media.audioUrl)
    }

    @Test fun originalAv1WebmDoesNotFallBackToLowerMp4() {
        val media = SiteMediaInfoParser.parse("""{"requested_formats":[
          {"url":"https://video.example/av1","ext":"webm","height":4320,"vcodec":"av01.0.16M.10","acodec":"none"},
          {"url":"https://audio.example/opus","ext":"webm","vcodec":"none","acodec":"opus"}
        ]}""", "https://example.com/watch/fixture", true)
        assertNotNull("An original AV1 source must remain available for lossless remux", media)
        assertEquals(4320, media!!.detectedHeight)
    }

    @Test fun combinedOriginalMatroskaKeepsItsActualContainerMime() {
        val media = SiteMediaInfoParser.parse("""{"url":"https://video.fixture.invalid/original.mkv",
          "ext":"mkv","protocol":"https","height":2160,"vcodec":"h264","acodec":"opus"
        }""", "https://fixture.invalid/source", true)
        assertNotNull(media)
        assertEquals("video/x-matroska", media!!.mimeType)
    }
}
