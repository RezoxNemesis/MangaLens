package com.mangalens.orez

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OrezPublicVideoDiscoveryTest {
    private fun row(title: String, href: String, snippet: String = "Creator public video") = "<div class='result'><a class='result__a' href='$href'>$title</a><div class='result__snippet'>$snippet</div></div>"
    private val source = "https://html.duckduckgo.com/html/?q=fixture"
    private val video = OrezVideoResult("Creator video", "https://www.youtube.com/watch?v=abcdefghijk", null, "Creator", null, null, "")
    @Test fun exactPageIdentityCanonicalizesWithoutStreamOrUploadClaims() {
        val parsed = OrezPublicVideoDiscovery.parse(row("Carryminati latest", "https://youtu.be/abcdefghijk"), source, "carryminati videos latest").single()
        assertEquals(video.url, parsed.url);assertNull(parsed.uploadDate);assertNull(parsed.durationSeconds);assertNull(parsed.thumbnail)
    }
    @Test fun liteSourceCarriesItsAdjacentActualSnippet() {
        val html = "<table><tr><td><a class='result-link' href='https://www.youtube.com/watch/abcdefghijk'>Public video</a></td></tr><tr><td class='result-snippet'>Carryminati creator clip</td></tr></table>"
        assertEquals("Carryminati creator clip", OrezPublicVideoDiscovery.parse(html,"https://lite.duckduckgo.com/lite/?q=fixture","carryminati videos").single().description)
    }
    @Test fun challengeAndUnrelatedGenericResultsNeverBecomeSuccess() {
        assertTrue(OrezPublicVideoDiscovery.parse("<form id='challenge-form'></form>" + row("Carryminati",video.url),source,"carryminati videos").isEmpty())
        assertTrue(OrezPublicVideoDiscovery.parse(row("Sabrina music",video.url,"Official music video"),source,"carryminati videos latest").isEmpty())
    }
    @Test fun channelsCredentialsFragmentsAndArbitraryLandingPagesCannotBecomePlayCards() {
        listOf("https://www.youtube.com/@creator","https://www.youtube.com/watch/server.html?v=abcdefghijk","https://user:secret@www.youtube.com/watch?v=abcdefghijk","https://www.youtube.com/watch?v=abcdefghijk#command","https://example.org/watch/abcdefghijk").forEach {
            assertTrue(it,OrezPublicVideoDiscovery.parse(row("Creator",it),source,"creator video").isEmpty())
        }
    }
    @Test fun duplicateYoutubeIdentityIsReturnedOnlyOnce() {
        val html = row("Creator A",video.url)+row("Creator B","https://youtu.be/abcdefghijk")
        assertEquals(1,OrezPublicVideoDiscovery.parse(html,source,"creator videos").size)
        assertNull(OrezVideoDiscoveryLinks.youtubePage("https://youtube.com/watch?v=abcdefghijk&v=zyxwvutsrqp"))
    }
    @Test fun indexRedirectIsUnwrappedOnlyToBoundedExactYoutubeVideo() {
        val html = row("Creator", "https://duckduckgo.com/l/?uddg=https%3A%2F%2Fyoutube.com%2Fwatch%3Fv%3Dabcdefghijk")
        assertEquals(video.url,OrezPublicVideoDiscovery.parse(html,source,"creator videos").single().url)
        assertTrue(OrezPublicVideoDiscovery.parse(html,"https://attacker.example/","creator videos").isEmpty())
    }
    @Test fun successfulNativeDiscoveryAvoidsTheFallbackRequest() = runBlocking {
        var fallbacks=0
        assertEquals(listOf(video),OrezVideoDiscoveryFlow.discover("creator",{listOf(video)},{fallbacks++;emptyList()}))
        assertEquals(0,fallbacks)
    }
    @Test fun nativeFailureOrUnusableChannelStillUsesIndependentSourceDiscovery() = runBlocking {
        assertEquals(listOf(video),OrezVideoDiscoveryFlow.discover("creator",{throw java.io.IOException("Provider unavailable")},{listOf(video)}))
        assertEquals(listOf(video),OrezVideoDiscoveryFlow.discover("creator",{listOf(video.copy(url="https://youtube.com/@creator"))},{listOf(video)}))
    }
    @Test fun explicitCancellationNeverStartsAnotherProvider() = runBlocking {
        var fallbacks=0
        try { OrezVideoDiscoveryFlow.discover("creator",{throw CancellationException("Stopped")},{fallbacks++;listOf(video)});fail("Cancelled request must remain cancelled") }
        catch (_:CancellationException) { assertEquals(0,fallbacks) }
    }
    @Test fun nativeCodecCannotClaimAnArbitraryWatchPathOrDuplicateYoutubeIdentity() {
        listOf("https://example.org/watch/server.html", "https://youtube.com/watch?v=abcdefghijk&v=zyxwvutsrqp", "https://vimeo.com/123456").forEach { url ->
            assertTrue(OrezVideoResultCodec.parseSearch("""{"entries":[{"id":"abcdefghijk","title":"Creator","url":"$url"}]}""").isEmpty())
        }
        assertEquals(video.url, OrezVideoResultCodec.parseSearch("""{"entries":[{"id":"abcdefghijk","title":"Creator","url":"abcdefghijk"}]}""").single().url)
    }
    @Test fun manualSearchActionIsBoundedLiteralTextAndDistinctFromSourcesOrPlay() {
        val query = "Carryminati & latest 日本語"
        val serialized = OrezVideoSearchActionCodec.encode(query)
        assertEquals(query, OrezVideoSearchActionCodec.fromMessage("Discovery unavailable" + serialized))
        assertFalse(serialized.contains("Sources:"));assertTrue(OrezVideoResultCodec.fromMessage(serialized).isEmpty())
        assertNull(OrezVideoSearchActionCodec.fromMessage(serialized+serialized))
        assertEquals("", OrezVideoSearchActionCodec.encode("x".repeat(257)))
    }
    @Test fun queryAndSourceBoundsRejectUntrustedPublication() {
        assertFalse(OrezPublicVideoDiscovery.validQuery("x".repeat(257)));assertFalse(OrezPublicVideoDiscovery.validQuery("x\u0000"))
        assertTrue(OrezPublicVideoDiscovery.parse(row("Creator",video.url),source,"videos latest").isEmpty())
        assertTrue(OrezPublicVideoDiscovery.parse("a".repeat(1_500_001),source,"creator").isEmpty())
    }
}
