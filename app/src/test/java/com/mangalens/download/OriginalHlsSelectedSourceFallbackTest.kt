package com.mangalens.download

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Authored UNRUN: real resolver boundary with fake extractor/interceptor, no external HTTP/JNI. */
class OriginalHlsSelectedSourceFallbackTest {
    private fun genericClient(calls: AtomicInteger) = OkHttpClient.Builder().addInterceptor { chain ->
        calls.incrementAndGet()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("synthetic")
            .body("<video src=\"https://fixture.invalid/preview-480p.mp4\"></video>".toResponseBody()).build()
    }.build()
    @Test fun realSelectedHlsFailureCannotFallBackToDifferentHtmlPreviewHeightOrTuple() {
        for (kind in listOf(MediaSourceFailureKind.UNSUPPORTED_TRANSPORT, MediaSourceFailureKind.SOURCE_ACCESS_DENIED,
            MediaSourceFailureKind.NETWORK_TLS_FAILURE, MediaSourceFailureKind.TRANSPORT_RESTART_REQUIRED)) {
            val calls = AtomicInteger()
            val selected = OriginalHlsSelectedSourceException(MediaSourceFailure(kind, "Fixed synthetic selected-source failure"))
            val resolver = MediaLinkResolver(genericClient(calls), SiteMediaExtractor { _, _ -> throw selected })
            val error = runCatching { resolver.resolve("https://fixture.invalid/watch/original", DownloadQuality.BEST) }.exceptionOrNull()
            assertSame(selected, error); assertEquals(0, calls.get())
            assertEquals(kind, MediaSourceFailure.from(error!!).kind)
        }
    }
    @Test fun ordinaryStaleExtractorStillUsesExistingObservedHtmlFallback() {
        val calls = AtomicInteger()
        val resolver = MediaLinkResolver(genericClient(calls), SiteMediaExtractor { _, _ -> throw IOException("synthetic stale extractor") })
        val media = resolver.resolve("https://fixture.invalid/watch/original", DownloadQuality.BEST)
        assertEquals(1, calls.get()); assertEquals("https://fixture.invalid/preview-480p.mp4", media!!.url)
    }
    @Test fun cancellationNeverStartsGenericFallbackOrChangesSelectedSource() {
        val calls = AtomicInteger(); val canceled = java.util.concurrent.CancellationException("synthetic canceled")
        val resolver = MediaLinkResolver(genericClient(calls), SiteMediaExtractor { _, _ -> throw canceled })
        val error = runCatching { resolver.resolve("https://fixture.invalid/watch/original") }.exceptionOrNull()!!
        assertTrue(error is java.util.concurrent.CancellationException)
        assertEquals(canceled.message, error.message)
        // Coroutine stack recovery can copy the exception with the original as its cause.
        assertSame(canceled, if (error === canceled) error else error.cause)
        assertEquals(0, calls.get())
    }
}
