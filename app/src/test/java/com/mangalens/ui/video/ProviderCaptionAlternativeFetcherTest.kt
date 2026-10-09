package com.mangalens.ui.video

import com.mangalens.download.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

class ProviderCaptionAlternativeFetcherTest {
    @Test fun expiredPreferredFormatStillUsesAnotherCapturedOriginalFormatBeforeAsr() = runBlocking {
        CaptionFixture().use { f ->
            val alternate = f.track.copy(url = f.track.url + "&fmt=json3", format = ProviderCaptionFormat.JSON3)
            val inventory = f.inventory.copy(tracks = listOf(f.track, alternate))
            val requests = mutableListOf<Request>()
            val calls = Call.Factory { request -> requests += request; FixtureCall(request, if (request.url.toString() == f.track.url) 410 else 200,
                """{"events":[{"tStartMs":500,"dDurationMs":1500,"segs":[{"utf8":"Do not open the door."}]}]}""") }
            val fetched = ProviderCaptionFetcher(calls, { null }).fetch(inventory, "auto")
            assertEquals(alternate, fetched.track)
            assertEquals("Do not open the door.", fetched.document.cues.single().text)
            assertEquals(listOf(f.track.url, alternate.url), requests.map { it.url.toString() })
        }
    }

    @Test fun savedExactDocumentNeverSwitchesFormatWhenItsOriginalTrackDisappears() = runBlocking {
        CaptionFixture().use { f ->
            val alternate = f.track.copy(url = f.track.url + "&fmt=json3", format = ProviderCaptionFormat.JSON3)
            val inventory = f.inventory.copy(tracks = listOf(f.track, alternate))
            val source = f.source.copy(source = f.source.source.copy(providerCaptions = inventory))
            val store = f.store(); val task = store.start(source, f.config)
            val saved = providerReceipt(task, f.track, f.document)
            val requests = mutableListOf<Request>()
            val calls = Call.Factory { request -> requests += request; FixtureCall(request, 404, "gone") }
            try { ProviderCaptionFetcher(calls, { null }).fetch(inventory, "auto", saved); fail("Saved document adopted an alternate original track") }
            catch (_: ProviderCaptionUnavailable) { }
            assertEquals(listOf(f.track.url), requests.map { it.url.toString() })
        }
    }
    private class FixtureCall(private val input: Request, private val code: Int, private val text: String) : Call {
        private var cancelled = false
        override fun request() = input
        override fun execute() = Response.Builder().request(input).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(text.toResponseBody()).build()
        override fun enqueue(callback: Callback) = error("Tracked production fetcher uses execute")
        override fun cancel() { cancelled = true }
        override fun isExecuted() = false
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout()
        override fun clone(): Call = FixtureCall(input, code, text)
    }
}
