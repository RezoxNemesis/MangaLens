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

    @Test fun shortChunkedPageSupportsImagesAndAdaptiveStreams() = fixture { server, resolver ->
        server.enqueue(MockResponse().setChunkedBody("<img src='/cover.png'><source src='/stream.m3u8'>", 7))
        val result = resolver.resolve(server.url("/chapter").toString())!!
        assertEquals(server.url("/stream.m3u8").toString(), result.url)
        assertEquals("application/x-mpegURL", result.mimeType)
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
}
