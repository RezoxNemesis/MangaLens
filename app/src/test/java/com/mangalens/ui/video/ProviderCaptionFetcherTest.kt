package com.mangalens.ui.video

import com.mangalens.download.*
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

class ProviderCaptionFetcherTest {
    private val track = ProviderCaptionTrack("https://www.youtube.com/api/timedtext?v=abcdefghijk&lang=en", "en", ProviderCaptionKind.MANUAL, ProviderCaptionFormat.VTT)
    private val inventory = ProviderCaptionInventory("https://www.youtube.com/watch?v=abcdefghijk", "abcdefghijk", "en",null,8000,listOf(track))
    private val caption = "WEBVTT\n\n00:00.500 --> 00:02.000\nDo not open the door.\n"
    private class FakeCall(private val request: Request, private val action: () -> Response, private val stopping: () -> Unit = {}) : Call {
        @Volatile var cancelled = false
        override fun request() = request
        override fun execute() = action()
        override fun enqueue(callback: Callback) = error("The bounded tracked worker executes this call.")
        override fun cancel() { cancelled=true; stopping() }
        override fun isExecuted() = false
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout()
        override fun clone(): Call = FakeCall(request, action, stopping)
    }
    private fun response(request: Request, code: Int = 200, body: String = caption, location: String? = null): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
            .body(body.toResponseBody()).apply { location?.let { header("Location",it) } }.build()
    @Test fun exactCaptionUrlCookieIsUsedWithoutPageOrMediaCredentials() = runBlocking {
        val requests = mutableListOf<Request>(); val cookieLookups = mutableListOf<String>()
        val factory = Call.Factory { request -> requests += request; FakeCall(request, { response(request) }) }
        val result = ProviderCaptionFetcher(factory, { url -> cookieLookups += url; "caption_only=valid" }).fetch(inventory,"auto")
        assertEquals(500L, result.document.cues.single().startMs)
        assertEquals(listOf(track.url), cookieLookups)
        assertEquals("caption_only=valid", requests.single().header("Cookie"))
        assertNull(requests.single().header("Authorization"))
    }
    @Test fun approvedRedirectGetsItsOwnCookieAndAnotherVideoIsNeverRequested() = runBlocking {
        val redirected = track.url + "&fmt=vtt"
        val requests = mutableListOf<Request>()
        val factory = Call.Factory { request -> requests += request; FakeCall(request, {
            if (requests.size==1) response(request,302,location=redirected) else response(request)
        }) }
        ProviderCaptionFetcher(factory, { if (it == track.url) "first=1" else "redirect=2" }).fetch(inventory,"auto")
        assertEquals(listOf("first=1","redirect=2"),requests.map { it.header("Cookie") })
        val unsafeRequests=mutableListOf<Request>()
        val unsafe=Call.Factory { request -> unsafeRequests+=request; FakeCall(request, { response(request,302,
            location="https://www.youtube.com/api/timedtext?v=anotherVideo&lang=en") }) }
        try { ProviderCaptionFetcher(unsafe,{null}).fetch(inventory,"auto"); fail("Cross-video captions fetched") } catch (_: ProviderCaptionUnavailable) { }
        assertEquals(1,unsafeRequests.size)
    }
    @Test fun HTTPAccessDeniedAndChallengeHtmlAreUnavailableWithSanitizedErrors() = runBlocking {
        for ((code, body) in listOf(403 to "signature=private",200 to "<html>Sign in</html>")) {
            val calls=Call.Factory { request -> FakeCall(request, { response(request,code,body) }) }
            try { ProviderCaptionFetcher(calls,{null}).fetch(inventory,"auto"); fail("Access failure became captions") }
            catch (failure: ProviderCaptionUnavailable) { assertFalse(failure.message.orEmpty().contains("signature")) }
        }
    }
    @Test fun timeoutReturnsWithoutWaitingForUncooperativeTransport() = runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val factory=Call.Factory { request -> FakeCall(request, { entered.countDown(); ignoreInterrupts(release); response(request) }) }
        val lateRelease=Thread { Thread.sleep(900);release.countDown() }.apply { isDaemon=true;start() }
        val started=System.nanoTime()
        try { ProviderCaptionFetcher(factory,{null},100).fetch(inventory,"auto"); fail("No timeout") } catch (_: Exception) { }
        finally { release.countDown() }
        assertTrue("Caption timeout waited for blocking execute",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<500)
    }
    @Test fun userCancellationReturnsEvenIfTransportAndCancelCleanupBlock() = runBlocking {
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val factory=Call.Factory { request -> FakeCall(request, { entered.countDown();ignoreInterrupts(release);response(request) }, {ignoreInterrupts(release)}) }
        val result=async(Dispatchers.Default) { ProviderCaptionFetcher(factory,{null},2000).fetch(inventory,"auto") }
        assertTrue(entered.await(1,TimeUnit.SECONDS))
        Thread { Thread.sleep(900);release.countDown() }.apply {isDaemon=true;start()}
        val started=System.nanoTime()
        try { result.cancelAndJoin() } finally {release.countDown()}
        assertTrue("Caption cancellation waited for blocking native/IO cleanup",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)<500)
    }
    private fun ignoreInterrupts(release:CountDownLatch) { while(true)try {release.await();return}catch (_:InterruptedException){} }
}
