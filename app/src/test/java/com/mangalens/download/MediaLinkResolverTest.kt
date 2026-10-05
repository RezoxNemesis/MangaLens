package com.mangalens.download

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class MediaLinkResolverTest {
    private fun fixture(test: (MockWebServer, MediaLinkResolver) -> Unit) {
        val server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
        try { test(server, MediaLinkResolver(client)) }
        finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }

    @Test fun ordinaryShortHtmlResolvesRelativeVideoAndTitle() = fixture { server, resolver ->
        server.enqueue(MockResponse().setBody("""
            <title>My local fixture</title><video><source src="/media/clip-720p.mp4"></video>
        """.trimIndent()))
        val result = resolver.resolve(server.url("/watch").toString())!!
        assertEquals(server.url("/media/clip-720p.mp4").toString(), result.url)
        assertEquals("video/mp4", result.mimeType)
        assertEquals(720, result.detectedHeight)
        assertEquals("My local fixture", result.title)
    }

    @Test fun openGraphVideoWorksWhenContentPrecedesProperty() = fixture { server, resolver ->
        server.enqueue(MockResponse().setBody(
            "<meta content='/secure/master.m3u8?token=abc' property='og:video:secure_url'>"
        ))
        val result = resolver.resolve(server.url("/watch").toString())!!
        assertEquals(server.url("/secure/master.m3u8?token=abc").toString(), result.url)
        assertEquals("application/x-mpegURL", result.mimeType)
    }

    @Test fun shortChunkedPageSupportsImagesAndAdaptiveStreams() = fixture { server, resolver ->
        server.enqueue(MockResponse().setChunkedBody("<img src='/cover.png'><source src='/stream.m3u8'>", 7))
        val result = resolver.resolve(server.url("/chapter").toString())!!
        assertEquals(server.url("/stream.m3u8").toString(), result.url)
        assertEquals("application/x-mpegURL", result.mimeType)
    }

    @Test fun opaqueSignedVideoEndpointUsesContentTypeWithoutReadingWholeMedia() = fixture { server, resolver ->
        server.enqueue(MockResponse().setHeader("Content-Type", "video/mp4").setBody("media bytes"))
        val result = resolver.resolve(server.url("/play?id=1&token=secret").toString())!!
        assertEquals("video/mp4", result.mimeType)
        assertEquals(server.url("/play?id=1&token=secret").toString(), result.url)
    }

    @Test fun videoPageThumbnailIsNotReportedAsVideoDownload() = fixture { server, resolver ->
        server.enqueue(MockResponse().setBody("<meta property='og:image' content='/poster.jpg'>"))
        assertNull(resolver.resolve(server.url("/video/123").toString()))
    }

    @Test fun dedicatedExtractorFallbackIsActuallyInvoked() {
        val server = MockWebServer().apply { start() }
        var invoked = false
        val extractor = SiteMediaExtractor { url, quality ->
            invoked = true
            assertEquals(DownloadQuality.P720, quality)
            ResolvedMediaLink("https://cdn.example/player?id=1", "video/mp4", sourcePageUrl = url)
        }
        try {
            server.enqueue(MockResponse().setBody("<title>Rendered player</title>"))
            val result = MediaLinkResolver(siteExtractor = extractor).resolve(server.url("/watch").toString(), DownloadQuality.P720)!!
            assertTrue(invoked)
            assertEquals("https://cdn.example/player?id=1", result.url)
        } finally { server.shutdown() }
    }

    @Test fun emptyOrUnsupportedPageReturnsNoInventedMedia() = fixture { server, resolver ->
        for (html in listOf("", "<title>No media</title><p>A normal page</p>")) {
            server.enqueue(MockResponse().setBody(html))
            assertNull(resolver.resolve(server.url("/page").toString()))
        }
    }

    @Test fun oversizedChunkedResponseIsRejectedEvenWithoutContentLength() = fixture { server, resolver ->
        server.enqueue(MockResponse().setChunkedBody("x".repeat(8 * 1024 * 1024 + 1), 64 * 1024))
        assertNull(resolver.resolve(server.url("/huge-page").toString()))
    }
    @Test fun kvsDownloadAnchorResolvesPlayable4kVariant() = fixture { server, resolver ->
        server.enqueue(MockResponse().setBody("""
            <html><body>
              <a href="/get_file/1/clip_720p.mp4/?download=true">MP4 720p</a>
              <a href="/get_file/1/clip_4k60fps.mp4/?download=true">MP4 2160p</a>
            </body></html>
        """.trimIndent()))
        val result = resolver.resolve(server.url("/video/4642490/example/").toString(), DownloadQuality.P2160)!!
        assertEquals(server.url("/get_file/1/clip_4k60fps.mp4/?download=true").toString(), result.url)
        assertEquals(2160, result.detectedHeight)
        assertEquals("video/mp4", result.mimeType)
    }


}
