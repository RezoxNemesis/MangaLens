package com.mangalens.download
import com.mangalens.ui.video.MediaRequestContext
import org.junit.Assert.*
import org.junit.Test
class ResolvedDownloadHeadersTest {
    private fun outgoing(requested: String, resolved: String, resolver: Map<String,String>, captured: Map<String,String>, browserCookie: String?=null) =
        MediaRequestContext(resolved, "https://page.example/watch", resolvedDownloadHeaders(requested,resolved,resolver,captured))
            .headersFor(resolved,browserCookie)
    @Test fun newlyResolvedCdnCannotReceiveTheEarlierDetectedCdnCookie() {
        val output=outgoing("https://page.example/watch", "https://cdn-b.example/movie.mp4", emptyMap(), mapOf("Cookie" to "cdn-a=private"))
        assertNull(output["Cookie"])
    }
    @Test fun resolvingASignedReplacementCannotReuseTheOldExactRequestCookie() {
        val output=outgoing("https://cdn.example/movie.mp4?token=old", "https://cdn.example/movie.mp4?token=new", emptyMap(), mapOf("Cookie" to "old=private"))
        assertNull(output["Cookie"])
    }
    @Test fun resolverCookiesAndAgentRemainAuthoritativeForTheirOwnNewUrl() {
        val output=outgoing("https://page.example/watch", "https://cdn-b.example/movie.mp4", mapOf("Cookie" to "cdn-b=valid", "User-Agent" to "resolver-agent"),
            mapOf("Cookie" to "cdn-a=private", "User-Agent" to "captured-agent"))
        assertEquals("cdn-b=valid",output["Cookie"]);assertEquals("resolver-agent",output["User-Agent"])
    }
    @Test fun exactDirectRequestStillKeepsItsCapturedCookieAndAgent() {
        val url="https://cdn.example/movie.mp4?token=exact"
        val output=outgoing(url,url,mapOf("User-Agent" to "resolver-agent"),mapOf("Cookie" to "exact=valid", "User-Agent" to "captured-agent"))
        assertEquals("exact=valid",output["Cookie"]);assertEquals("captured-agent",output["User-Agent"])
    }
    @Test fun browserJarForTheActualResolvedUrlStillOverridesAnyCapturedCookie() {
        val url="https://cdn.example/movie.mp4"
        assertEquals("jar=actual",outgoing(url,url,emptyMap(),mapOf("Cookie" to "captured=older"),"jar=actual")["Cookie"])
    }
    @Test fun actualContextStillDropsAnExactCookieOnASecondCdnOrAdaptiveSegment() {
        val original="https://cdn-a.example/movie.mp4"
        val context=MediaRequestContext(original,"https://page.example/watch",resolvedDownloadHeaders(original,original,emptyMap(),mapOf("Cookie" to "exact=private")))
        assertEquals("exact=private",context.headersFor(original,null)["Cookie"])
        assertNull(context.headersFor("https://cdn-b.example/movie.mp4",null)["Cookie"])
        assertNull(context.headersFor("https://cdn-a.example/segment.ts",null)["Cookie"])
    }
}
