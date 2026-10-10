package com.mangalens.download

import com.mangalens.ui.video.MediaRequestContext
import okhttp3.*
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Authored UNRUN: interceptor/owned source fixtures; no actual HTTP/DNS/TLS/native execution. */
class OriginalHlsHttpOwnershipTest {
    private class Body(private val failClose: Boolean = false) : ResponseBody() {
        var closes = 0
        private val input = object : ForwardingSource(Buffer().writeUtf8("synthetic")) {
            override fun close() { closes++; if (failClose) throw IOException("synthetic private close failure"); super.close() }
        }.buffer()
        override fun contentType(): MediaType? = null
        override fun contentLength() = 9L
        override fun source(): BufferedSource = input
    }
    private class Chain(private val initial: Request, private val respond: (Request) -> Response) : Interceptor.Chain {
        val requests = ArrayList<Request>()
        override fun request() = initial
        override fun proceed(request: Request): Response { requests += request; return respond(request) }
        override fun connection(): Connection? = null
        override fun call(): Call = OkHttpClient().newCall(initial)
        override fun connectTimeoutMillis() = 20_000
        override fun readTimeoutMillis() = 60_000
        override fun writeTimeoutMillis() = 10_000
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }
    private fun response(request: Request, body: Body, code: Int = 200, location: String? = null) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("synthetic").body(body)
        .apply { if (location != null) header("Location", location) }.build()

    @Test fun actualSourceCloseOwnsCapacityRedirectAndFailedCloseRestartFence() {
        // One ordered fixture keeps failed-close quarantine last; production quarantine is never reset.
        val anchor = "https://fixture.invalid/selected.m3u8?synthetic=anchor"
        val client = OriginalHlsPublicTransport.client(anchor, null, emptyMap()) { null }
        assertFalse(client.followRedirects); assertFalse(client.followSslRedirects)
        val interceptor = client.interceptors.single()
        val request = Request.Builder().url(anchor).build()
        val bodies = (0..3).map { Body() }
        val active = bodies.map { body -> interceptor.intercept(Chain(request) { response(it, body) }) }
        assertTrue(runCatching { interceptor.intercept(Chain(request) { response(it, Body()) }) }.exceptionOrNull() is MediaSourceException)
        active.forEach { it.body!!.source().close() }
        assertEquals(listOf(1, 1, 1, 1), bodies.map { it.closes })
        active.first().close(); assertEquals(1, bodies.first().closes)

        val redirectBody = Body()
        val redirect = Chain(request) { response(it, redirectBody, 302, "https://127.0.0.1/private?synthetic=secret") }
        assertTrue(runCatching { interceptor.intercept(redirect) }.isFailure)
        assertEquals(1, redirect.requests.size); assertEquals(1, redirectBody.closes)
        val successful = Body()
        interceptor.intercept(Chain(request) { response(it, successful) }).close()
        assertEquals(1, successful.closes)

        val failed = Body(true)
        val owned = interceptor.intercept(Chain(request) { response(it, failed) })
        val fault = runCatching { owned.close() }.exceptionOrNull()
        assertTrue(fault is IllegalStateException); assertEquals(1, failed.closes)
        assertFalse(fault!!.message.orEmpty().contains("synthetic"))
        val refused = Chain(request) { response(it, Body()) }
        assertTrue(runCatching { interceptor.intercept(refused) }.exceptionOrNull() is MediaSourceException)
        assertTrue(refused.requests.isEmpty())
    }
    @Test fun typedUnprovenReleaseKeepsFixedRestartLabelThroughResolutionAggregation() {
        val close = OriginalHlsUnprovenReleaseException(IOException("https://fixture.invalid/secret?synthetic=cookie"))
        val mapped = MediaSourceFailure.from(close)
        assertEquals(MediaSourceFailureKind.TRANSPORT_RESTART_REQUIRED, mapped.kind)
        assertTrue(mapped.message.contains("Restart MangaLens")); assertFalse(mapped.message.contains("synthetic"))
        val combined = MediaSourceException.fromFailures(listOf(javax.net.ssl.SSLException("synthetic TLS"), close))
        assertEquals(MediaSourceFailureKind.TRANSPORT_RESTART_REQUIRED, combined.failure.kind)
    }
    @Test fun ordinaryAuthenticationAndNetworkMappingsRemainFixedAndCancellationRethrows() {
        assertEquals(MediaSourceFailureKind.PROVIDER_LOGIN_REQUIRED, MediaSourceFailure.from(IOException("login required")).kind)
        assertEquals(MediaSourceFailureKind.NETWORK_TLS_FAILURE, MediaSourceFailure.from(javax.net.ssl.SSLException("synthetic")).kind)
        val cancel = java.util.concurrent.CancellationException("synthetic canceled")
        val close = OriginalHlsUnprovenReleaseException(cancel)
        assertSame(cancel, runCatching { MediaSourceFailure.from(close) }.exceptionOrNull())
    }
    @Test fun capturedCookieStaysOnOriginalPlaylistAnchorAndActualSegmentJarIsIndependent() {
        val anchor = "https://fixture.invalid/selected.m3u8?synthetic=one"
        val context = MediaRequestContext(anchor, "https://fixture.invalid/watch", mapOf("Cookie" to "synthetic=anchor"))
        assertEquals("synthetic=anchor", context.headersFor(anchor, null)["Cookie"])
        assertNull(context.headersFor("https://fixture.invalid/segment.m4s", null)["Cookie"])
        assertNull(context.headersFor("https://other.fixture.invalid/selected.m3u8?synthetic=one", null)["Cookie"])
        assertEquals("synthetic=jar", context.headersFor("https://other.fixture.invalid/segment.m4s", "synthetic=jar")["Cookie"])
    }
}
