package com.mangalens.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class OrezLiveSearchConnectorTest {
    private fun fixture(test: suspend (MockWebServer, OrezLiveSearchConnector) -> Unit) = runBlocking {
        val server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                val local = server.url(original.url.encodedPath + "?" + original.url.encodedQuery.orEmpty())
                chain.proceed(original.newBuilder().url(local).build())
            }.build()
        try { test(server, OrezLiveSearchConnector(client)) }
        finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }

    @Test fun verificationPageUsesLabelledWikipediaFallbackWithRealSourceIdentity() = fixture { server, search ->
        server.enqueue(MockResponse().setResponseCode(202).setBody("<html>Complete verification</html>"))
        server.enqueue(MockResponse().setBody("""{"pages":[{"key":"Android_(operating_system)","title":"Android","excerpt":"Android is an <span>operating system</span>."}]}"""))
        val answer = search.search("Android operating system")
        assertEquals("wikipedia", answer.provider)
        assertEquals("Android is an operating system.", answer.results.single().snippet)
        assertTrue(answer.results.single().url.startsWith("https://en.wikipedia.org/wiki/Android_"))
        assertTrue(answer.summary.contains("does not verify current"))
        assertTrue(answer.summary.contains("CC BY-SA"))
        server.takeRequest()
        val fallbackRequest = server.takeRequest()
        assertEquals("/w/rest.php/v1/search/page", fallbackRequest.requestUrl!!.encodedPath)
        assertEquals("Android operating system", fallbackRequest.requestUrl!!.queryParameter("q"))
    }

    @Test fun primaryResultIsEnrichedWithoutContactingFallback() = fixture { server, search ->
        server.enqueue(MockResponse().setBody("<div class='result'><a class='result__a' href='https://example.org/article'>Android guide</a><div class='result__snippet'>Snippet</div></div>"))
        server.enqueue(MockResponse().setBody("<p>Android documentation explains how applications store offline downloads safely.</p>"))
        val answer = search.search("Android downloads")
        assertEquals("web", answer.provider)
        assertEquals("https://example.org/article", answer.results.single().url)
        assertTrue(answer.results.single().snippet.contains("offline downloads"))
        assertEquals(2, server.requestCount)
    }

    @Test fun malformedFallbackProducesAnHonestUnavailableAnswer() = fixture { server, search ->
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("<html>Not JSON</html>"))
        val answer = search.search("public question")
        assertTrue(answer.results.isEmpty())
        assertTrue(answer.summary.contains("did not return readable public results"))
    }

    @Test fun cancellingSearchClosesStalledSocketAndDoesNotStartFallback() = fixture { server, search ->
        coroutineScope {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val job = launch(Dispatchers.IO) { search.search("cancel this search") }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            withTimeout(5_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
            assertEquals(1, server.requestCount)
        }
    }
}
