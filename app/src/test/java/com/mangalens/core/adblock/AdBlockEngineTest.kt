package com.mangalens.core.adblock

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class AdBlockEngineTest {

    private lateinit var adBlockEngine: AdBlockEngine

    @Before
    fun setUp() {
        adBlockEngine = AdBlockEngine()
    }

    @Test
    fun testAdDomainIsBlocked() {
        val response = adBlockEngine.shouldBlockRequest(
            "https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"
        )
        assertNotNull("Ad domain should be blocked", response)
    }

    @Test
    fun testValidMediaStreamIsNotBlocked() {
        val response = adBlockEngine.shouldBlockRequest(
            "https://cdn.example.com/video/stream.m3u8"
        )
        assertNull("Media stream should NOT be blocked", response)
    }

    @Test
    fun testValidImageIsNotBlocked() {
        val response = adBlockEngine.shouldBlockRequest(
            "https://cdn.example.com/manga/chapter1/page01.webp"
        )
        assertNull("Image candidate should NOT be blocked", response)
    }

    @Test
    fun testNestedAdSubdomainIsBlocked() {
        val response = adBlockEngine.shouldBlockRequest(
            "https://sub.example.popads.net/script.js"
        )
        assertNotNull("Nested ad subdomain should be blocked", response)
    }

    @Test
    fun testMediaQueryStringIsNotBlocked() {
        val response = adBlockEngine.shouldBlockRequest(
            "https://cdn.example.com/page.webp?token=abc123"
        )
        assertNull("Media URL with query parameters should NOT be blocked", response)
    }

    @Test
    fun testMediaOnBlockedHostIsAlwaysAllowed() {
        val response = adBlockEngine.shouldBlockRequest(
            "https://cdn.doubleclick.net/manga/page01.jpg?token=abc123"
        )
        assertNull("Valid media must always pass through", response)
    }

    @Test
    fun testUnrelatedDomainIsAllowed() {
        val response = adBlockEngine.shouldBlockRequest(
            "https://reader.example.com/chapter/27"
        )
        assertNull("Unrelated navigation should be allowed", response)
    }
}
