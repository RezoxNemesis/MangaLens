package com.mangalens.download

import com.mangalens.ui.video.MediaRequestContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ScopedDownloadHeadersTest {
    @Test fun segmentCookiesComeFromJarForActualUrlOnly() {
        val server = MockWebServer().apply { start() }
        val manifest = server.url("/private/manifest.m3u8").toString()
        val protectedSegment = server.url("/private/segment.ts").toString()
        val publicSegment = server.url("/public/segment.ts").toString()
        val lookups = mutableListOf<String>()
        val client = OkHttpClient.Builder().addNetworkInterceptor(scopedDownloadHeaders(
            MediaRequestContext(manifest, server.url("/watch").toString(), mapOf("Cookie" to "captured=manifest-only"))
        ) { url ->
            lookups += url
            if (url == protectedSegment) "jar=private-path" else null
        }).build()
        try {
            for (url in listOf(protectedSegment, publicSegment)) {
                server.enqueue(MockResponse().setBody("segment"))
                client.newCall(Request.Builder().url(url).header("Cookie", "stale=must-not-leak").build()).execute().close()
            }
            assertEquals("jar=private-path", server.takeRequest().getHeader("Cookie"))
            assertNull(server.takeRequest().getHeader("Cookie"))
            assertEquals(listOf(protectedSegment, publicSegment), lookups)
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }

    @Test fun redirectsAndAdaptiveSegmentsNeverReceiveCapturedCookie() {
        val first = MockWebServer().apply { start() }
        val second = MockWebServer().apply { start() }
        val start = first.url("/protected.mp4").toString()
        val page = first.url("/watch").toString()
        val client = OkHttpClient.Builder().addNetworkInterceptor(scopedDownloadHeaders(
            MediaRequestContext(start, page, mapOf("Cookie" to "session=private"))
        )).build()
        try {
            first.enqueue(MockResponse().setResponseCode(302).setHeader("Location", second.url("/file.mp4")))
            second.enqueue(MockResponse().setBody("video"))
            client.newCall(Request.Builder().url(start).build()).execute().close()
            val initial = first.takeRequest()
            val redirected = second.takeRequest()
            assertEquals("session=private", initial.getHeader("Cookie"))
            assertNull(redirected.getHeader("Cookie"))
            assertEquals(page, redirected.getHeader("Referer"))
            second.enqueue(MockResponse().setBody("segment"))
            client.newCall(Request.Builder().url(second.url("/segment.ts")).build()).execute().close()
            assertNull(second.takeRequest().getHeader("Cookie"))
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            first.shutdown(); second.shutdown()
        }
    }
}
