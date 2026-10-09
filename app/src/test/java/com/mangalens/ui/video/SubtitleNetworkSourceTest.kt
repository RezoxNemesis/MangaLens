package com.mangalens.ui.video

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class SubtitleNetworkSourceTest {
    @Test fun cachedIdentityComesFromBoundedVideoBytesRatherThanAStaleHeadResponse() {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                    if (request.method == "HEAD") MockResponse().setHeader("ETag", "\"old\"").setHeader("Content-Length", 16)
                    else MockResponse().setResponseCode(206).setHeader("ETag", "\"actual\"")
                        .setHeader("Content-Range", "bytes 0-0/16").setBody("0")
            }
            SubtitleNetworkSource(SubtitleMediaSource(server.url("/video").toString())).use {
                assertEquals("\"actual\"|16", it.validator())
            }
            val bytes = server.takeRequest()
            assertEquals("GET", bytes.method); assertEquals("bytes=0-0", bytes.getHeader("Range"))
        }
    }
    @Test fun redirectedHeadNeverCarriesCapturedCredentialsOrPrivateDowngradeReferrer() {
        MockWebServer().use { origin -> MockWebServer().use { target ->
            val targetUrl = target.url("/collect").newBuilder().host("127.0.0.1").build().toString()
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", targetUrl))
            target.enqueue(MockResponse().setResponseCode(206).setHeader("ETag", "\"version\"")
                .setHeader("Content-Range", "bytes 0-0/16").setBody("0"))
            val source = SubtitleMediaSource(origin.url("/private").toString(), mapOf("Cookie" to "session=original", "Authorization" to "private-token",
                "Referer" to "https://private.invalid/watch?secret=1"))
            SubtitleNetworkSource(source).use { it.validator() }
            assertEquals("session=original", origin.takeRequest().getHeader("Cookie"))
            val redirected = target.takeRequest()
            assertNull(redirected.getHeader("Cookie")); assertNull(redirected.getHeader("Authorization")); assertNull(redirected.getHeader("Referer"))
        } }
    }
    @Test fun redirectedExtractorRangeReadsUseTheSameCredentialScope() {
        MockWebServer().use { origin -> MockWebServer().use { target ->
            val targetUrl = target.url("/collect").newBuilder().host("127.0.0.1").build().toString()
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", targetUrl))
            target.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-15/16").setBody("0123456789abcdef"))
            val source = SubtitleMediaSource(origin.url("/private").toString(), mapOf("Cookie" to "session=original", "Authorization" to "private-token",
                "Referer" to "https://private.invalid/watch?secret=1"))
            val data = ByteArray(8)
            SubtitleNetworkSource(source).use { assertEquals(8, it.readAt(0, data, 0, data.size)) }
            assertEquals("01234567", String(data))
            assertEquals("session=original", origin.takeRequest().getHeader("Cookie"))
            val redirected = target.takeRequest()
            assertNull(redirected.getHeader("Cookie")); assertNull(redirected.getHeader("Authorization")); assertNull(redirected.getHeader("Referer"))
        } }
    }
    @Test fun exactSourceReadRetainsAllowedCookieAndReusesOnlyItsBoundedRangeCache() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-15/16").setBody("0123456789abcdef"))
            SubtitleNetworkSource(SubtitleMediaSource(server.url("/private").toString(), mapOf("Cookie" to "session=original"))).use { reader ->
                val first = ByteArray(8); val second = ByteArray(4)
                assertEquals(8, reader.readAt(0, first, 0, first.size)); assertEquals(4, reader.readAt(12, second, 0, second.size))
                assertEquals("01234567", String(first)); assertEquals("cdef", String(second))
                assertEquals(-1, reader.readAt(16, first, 0, 1))
                assertEquals(1, server.requestCount)
            }
            assertEquals("session=original", server.takeRequest().getHeader("Cookie"))
        }
    }
    @Test fun sourceIgnoringRangesCannotTurnSeekingIntoAnUnboundedDownload() {
        MockWebServer().use { server ->
            val body = "x".repeat(600_000)
            repeat(2) { server.enqueue(MockResponse().setBody(body)) }
            SubtitleNetworkSource(SubtitleMediaSource(server.url("/no-range").toString())).use { reader ->
                val buffer = ByteArray(8)
                assertEquals(8, reader.readAt(0, buffer, 0, buffer.size))
                try { reader.readAt(550_000, buffer, 0, buffer.size); fail("Accepted a server ignoring seek position") }
                catch (expected: java.io.IOException) { assertTrue(expected.message!!.contains("bounded seeking")) }
            }
        }
    }
    @Test fun everyDecodedRangeIsBoundToItsCapturedStrongValidator() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-15/16")
                .setHeader("ETag", "\"changed\"").setBody("0123456789abcdef"))
            SubtitleNetworkSource(SubtitleMediaSource(server.url("/video").toString()), expectedEtag = "\"captured\"", expectedSize = 16).use { reader ->
                try { reader.readAt(0, ByteArray(8), 0, 8); fail("Decoded a different representation under the captured identity") }
                catch (_: SubtitleNetworkChanged) { }
            }
            assertEquals("\"captured\"", server.takeRequest().getHeader("If-Match"))
        }
    }
    @Test fun matchingRangesRemainBoundAfterReaderRestartAndValidatorLossIsNotAChange() {
        MockWebServer().use { server ->
            repeat(2) { server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-15/16")
                .setHeader("ETag", "\"captured\"").setBody("0123456789abcdef")) }
            server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 0-15/16").setBody("0123456789abcdef"))
            repeat(2) {
                SubtitleNetworkSource(SubtitleMediaSource(server.url("/video").toString()), expectedEtag = "\"captured\"", expectedSize = 16).use { reader ->
                    val bytes = ByteArray(8); assertEquals(8, reader.readAt(0, bytes, 0, 8)); assertEquals("01234567", String(bytes))
                }
                assertEquals("\"captured\"", server.takeRequest().getHeader("If-Match"))
            }
            SubtitleNetworkSource(SubtitleMediaSource(server.url("/video").toString()), expectedEtag = "\"captured\"", expectedSize = 16).use { reader ->
                try { reader.readAt(0, ByteArray(8), 0, 8); fail("Read an unverified range") }
                catch (_: SubtitleNetworkUnverified) { }
            }
        }
    }
    @Test fun capturedGetLengthCannotBeReplacedByAStaleHeadBeforeExtractorReads() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Length", 1).setHeader("ETag", "\"old\""))
            SubtitleNetworkSource(SubtitleMediaSource(server.url("/video").toString()), expectedEtag = "\"captured\"", expectedSize = 16).use {
                assertEquals(16L, it.size())
            }
            assertEquals(0, server.requestCount)
        }
    }
    @Test fun laterRangeChangesCannotMixRepresentationsAfterAValidFirstRange() {
        MockWebServer().use { server ->
            val first = "x".repeat(512 * 1024); val total = first.length + 8
            server.enqueue(MockResponse().setResponseCode(206).setHeader("ETag", "\"captured\"")
                .setHeader("Content-Range", "bytes 0-${first.lastIndex}/$total").setBody(first))
            server.enqueue(MockResponse().setResponseCode(206).setHeader("ETag", "\"changed\"")
                .setHeader("Content-Range", "bytes ${first.length}-${total - 1}/$total").setBody("changed!"))
            SubtitleNetworkSource(SubtitleMediaSource(server.url("/video").toString()), expectedEtag = "\"captured\"", expectedSize = total.toLong()).use {
                assertEquals(8, it.readAt(0, ByteArray(8), 0, 8))
                try { it.readAt(first.length.toLong(), ByteArray(8), 0, 8); fail("Combined captured and changed video ranges") }
                catch (_: SubtitleNetworkChanged) { }
            }
            repeat(2) { assertEquals("\"captured\"", server.takeRequest().getHeader("If-Match")) }
        }
    }
    @Test fun changedUnsatisfiableRangeCannotSilentlyShortenTheCapturedVideo() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(416).setHeader("Content-Range", "bytes */8"))
            SubtitleNetworkSource(SubtitleMediaSource(server.url("/video").toString()), expectedEtag = "\"captured\"", expectedSize = 16).use {
                try { it.readAt(8, ByteArray(8), 0, 8); fail("Adopted another source's shorter EOF") }
                catch (_: SubtitleNetworkChanged) { }
            }
        }
    }
    @Test fun redirectedResourcesCannotShareProofOnlyBecauseTheirEtagsAndSizesMatch() {
        MockWebServer().use { origin -> MockWebServer().use { target ->
            val source = SubtitleMediaSource(origin.url("/video").toString())
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/A")))
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/B")))
            repeat(2) { target.enqueue(MockResponse().setResponseCode(206).setHeader("ETag", "\"one\"")
                .setHeader("Content-Range", "bytes 0-0/16").setBody(it.toString())) }
            val a = SubtitleNetworkSource(source).use { it.proof()!!.fingerprint }
            val b = SubtitleNetworkSource(source).use { it.proof()!!.fingerprint }
            assertNotEquals("Strong ETags identify a representation of one resource", a, b)
        } }
    }
    @Test fun rangesRejectAChangedRedirectResourceEvenWhenItsEtagAndSizeMatch() {
        MockWebServer().use { origin -> MockWebServer().use { target ->
            origin.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target.url("/B")))
            target.enqueue(MockResponse().setResponseCode(206).setHeader("ETag", "\"one\"")
                .setHeader("Content-Range", "bytes 0-15/16").setBody("0123456789abcdef"))
            SubtitleNetworkSource(SubtitleMediaSource(origin.url("/video").toString()), expectedEtag = "\"one\"", expectedSize = 16,
                expectedUrl = target.url("/A").toString()).use {
                try { it.readAt(0, ByteArray(8), 0, 8); fail("Read another resource under the original ETag proof") }
                catch (_: SubtitleNetworkChanged) { }
            }
        } }
    }
    @Test fun capturedConditionalTagIsSentOnlyToTheFinalResourceAndNotItsRedirectAlias() {
        MockWebServer().use { origin -> MockWebServer().use { target ->
            val finalUrl = target.url("/media").toString()
            origin.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                    if (request.getHeader("If-Match") != null) MockResponse().setResponseCode(412)
                    else MockResponse().setResponseCode(302).setHeader("Location", finalUrl)
            }
            target.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                    if (request.getHeader("If-Match") != "\"media\"") MockResponse().setResponseCode(412)
                    else MockResponse().setResponseCode(206).setHeader("ETag", "\"media\"")
                        .setHeader("Content-Range", "bytes 0-15/16").setBody("0123456789abcdef")
            }
            SubtitleNetworkSource(SubtitleMediaSource(origin.url("/alias").toString()), expectedEtag = "\"media\"", expectedSize = 16,
                expectedUrl = finalUrl).use { assertEquals(8, it.readAt(0, ByteArray(8), 0, 8)) }
            assertNull(origin.takeRequest().getHeader("If-Match"))
            assertEquals("\"media\"", target.takeRequest().getHeader("If-Match"))
        } }
    }
}
