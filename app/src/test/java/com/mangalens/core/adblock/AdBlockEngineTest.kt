package com.mangalens.core.adblock

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun testMediaOnKnownAdHostIsBlocked() {
        val response = adBlockEngine.shouldBlockRequest(
            "https://cdn.doubleclick.net/preroll/ad-video.mp4?token=abc123"
        )
        assertNotNull("Known ad-network media must be blocked too", response)
    }

    @Test
    fun testOrdinaryFirstPartyMediaStillPasses() {
        val response = adBlockEngine.shouldBlockRequest(
            "https://video.example.com/media/movie.mp4?token=abc123"
        )
        assertNull("First-party media must keep playing", response)
    }

    @Test
    fun testUnrelatedDomainIsAllowed() {
        val response = adBlockEngine.shouldBlockRequest(
            "https://reader.example.com/chapter/27"
        )
        assertNull("Unrelated navigation should be allowed", response)
    }
    @Test
    fun testFirstPartyPrerollMediaIsBlocked() {
        val response = adBlockEngine.shouldBlockRequest(
            "https://video.example.com/ads/preroll/ad-01.mp4"
        )
        assertNotNull("Explicit first-party preroll paths must be blocked", response)
    }


    @Test
    fun testAdditionalProgrammaticAdNetworkIsBlocked() {
        val response = adBlockEngine.shouldBlockRequest(
            "https://ads.example.rubiconproject.com/a/api/fastlane.json"
        )
        assertNotNull("Known programmatic ad network should be blocked", response)
    }

    @Test
    fun testOrdinaryFirstPartyVideoWithAdWordInTitleStillPasses() {
        val response = adBlockEngine.shouldBlockRequest(
            "https://media.example.com/video/road-adventure-4k.mp4?token=abc"
        )
        assertNull("Normal first-party video titles must not be overblocked", response)
    }


    @Test
    fun socialVideoAdSelectorsArePresentWithoutBlockingSharedMediaCdn() {
        val script = adBlockEngine.getElementHidingScript()
        assertTrue(script.contains("ytd-display-ad-renderer"))
        assertTrue(script.contains("instagram.com"))
        assertNull(
            "YouTube video CDN must not be blanket-blocked because it also carries real playback.",
            adBlockEngine.shouldBlockRequest("https://rr1---sn.example.googlevideo.com/videoplayback?id=1&mime=video%2Fmp4")
        )
    }

}
