package com.mangalens.ui.video

import org.junit.Assert.*
import org.junit.Test

class MediaRequestContextTest {
    private val media = "https://cdn.example.com/private/master.m3u8?token=1"

    @Test fun capturedCookiesAreConfinedToTheirExactRequest() {
        val context = MediaRequestContext(media, headers = mapOf("Cookie" to "session=private"))
        assertEquals("session=private", context.headersFor(media, null)["Cookie"])
        for (other in listOf("https://other.example.com/master.m3u8", "https://cdn.example.com/public/segment.ts",
            "http://cdn.example.com/private/master.m3u8?token=1", "$media&other=2")) {
            assertNull(context.headersFor(other, null)["Cookie"])
        }
    }

    @Test fun browserJarSelectsSegmentCookiesAndOverridesCapturedCookie() {
        val context = MediaRequestContext(media, headers = mapOf("Cookie" to "old=1"))
        assertEquals("fresh=2", context.headersFor(media, "fresh=2")["Cookie"])
        assertEquals("cdn=3", context.headersFor("https://segments.example.com/a.ts", "cdn=3")["Cookie"])
    }

    @Test fun sanitizesHeaderNamesAndValuesAndDerivesOriginWithPort() {
        val input = mutableMapOf("user-agent" to "Fixture agent", "Accept" to "video/*",
            "COOKIE" to "bad\r\nInjected: value", "Authorization" to "secret", "Origin" to "https://wrong.example")
        val context = MediaRequestContext(media, "https://page.example:8443/watch", input)
        input["user-agent"] = "changed"
        val headers = context.headersFor(media, null)
        assertEquals("Fixture agent", headers["User-Agent"])
        assertEquals("video/*", headers["Accept"])
        assertEquals("https://page.example:8443", headers["Origin"])
        assertEquals("https://page.example:8443/watch", headers["Referer"])
        assertNull(headers["Cookie"])
        assertNull(headers["Authorization"])
    }

    @Test fun privateReferrerIsNotSentOnHttpsDowngrade() {
        val context = MediaRequestContext(media, "https://page.example/watch?private=1")
        val headers = context.headersFor("http://cdn.example/a.ts", null)
        assertNull(headers["Referer"])
        assertNull(headers["Origin"])
        assertTrue(context.headersFor("file:///private", null).isEmpty())
    }

    @Test fun credentialedAndMalformedReferrersAreRejected() {
        for (page in listOf("https://user:password@page.example/watch", "httpsbad://page.example", "https://page.example/\n")) {
            assertNull(MediaRequestContext(media, page).headersFor(media, null)["Referer"])
        }
    }
}
