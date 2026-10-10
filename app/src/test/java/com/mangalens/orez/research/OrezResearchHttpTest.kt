package com.mangalens.orez.research

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.net.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory

/** Real HTTP/TLS body/headers; fixture sockets route public names to one controlled local server. */
class OrezResearchHttpTest {
    internal fun fixture(test: suspend (MockWebServer, OrezResearchTransport, AtomicInteger) -> Unit) = runBlocking {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("html.duckduckgo.com")
            .addSubjectAlternativeName("en.wikipedia.org").addSubjectAlternativeName("source.test")
            .addSubjectAlternativeName("second.test").build()
        val server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        server.start()
        val connections = AtomicInteger()
        val route = object : SocketFactory() {
            override fun createSocket(): Socket = object : Socket() {
                override fun connect(endpoint: SocketAddress, timeout: Int) {
                    connections.incrementAndGet()
                    super.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), server.port), timeout)
                }
            }
            override fun createSocket(host: String, port: Int): Socket = error("Unused fixture socket method")
            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = error("Unused fixture socket method")
            override fun createSocket(host: InetAddress, port: Int): Socket = error("Unused fixture socket method")
            override fun createSocket(host: InetAddress, port: Int, localHost: InetAddress, localPort: Int): Socket = error("Unused fixture socket method")
        }
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().socketFactory(route).sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .dns(OrezResearchPublicNetworkPolicy.guardedDns(object : Dns {
                override fun lookup(hostname: String) = listOf(InetAddress.getByName("8.8.8.8"))
            }))
            .proxy(Proxy.NO_PROXY).readTimeout(10, TimeUnit.SECONDS).callTimeout(14, TimeUnit.SECONDS).build()
        try { test(server, OrezResearchTransport.fixture(client) { 1000L }, connections) }
        finally { client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); server.shutdown() }
    }
    private fun response(body: String, mime: String = "text/html") = MockResponse().setHeader("Content-Type", mime).setBody(body)
    private fun primary(vararg urls: String) = response(urls.joinToString("") { "<div class='result'><a class='result__a' href='$it'>Android guide</a></div>" })
    @Test fun actualProviderAndSourceCapturesRetainBoundedBytesUrlsDatesAndQuery() = fixture { server, transport, _ ->
        server.enqueue(primary("https://source.test/article"))
        val article = "<nav>upload cookies</nav><p>Android downloads remain available offline after applications save them to local storage.</p>"
        server.enqueue(response(article).setHeader("Last-Modified", "Wed, 07 Oct 2026 12:00:00 GMT"))
        val request = OrezResearchRequest.parseExplicit("Research \"Android offline downloads\"")!!
        val evidence = OrezResearchHost(transport, { 1000L }).research(request) { true }!!
        val source = evidence.citations.single()
        assertEquals("Android guide", source.title)
        assertTrue(source.excerpt.contains("available offline")); assertFalse(source.excerpt.contains("upload cookies"))
        assertEquals("https://source.test/article", source.source.finalUrl)
        assertEquals(researchSha256(article.toByteArray()), source.source.bodySha256)
        assertEquals(article.toByteArray().size, source.source.capturedBytes)
        assertEquals("Wed, 07 Oct 2026 12:00:00 GMT", source.source.lastModified)
        assertEquals(evidence, OrezResearchEvidenceCodec.receipt(OrezResearchEvidenceCodec.outputs("request", evidence), "request", request))
        val query = server.takeRequest().requestUrl!!.queryParameter("q")
        assertEquals(request.query, query); assertEquals("/article", server.takeRequest().path)
        assertFalse(evidence.metadataCompletion().contains(source.excerpt))
    }
    @Test fun unreadablePrimarySourcesStillTryAnotherProviderAndOpenItsActualArticle() = fixture { server, transport, _ ->
        server.enqueue(primary("https://source.test/challenge")); server.enqueue(response("<form>Log in complete verification</form>"))
        server.enqueue(response("""{"pages":[{"key":"Android","title":"Android","excerpt":"provider snippet differs from article"}]}""", "application/json"))
        server.enqueue(response("<p>Android is an operating system used by phones, tablets and other portable computers worldwide.</p>"))
        val evidence = OrezResearchHost(transport, { 1000L }).research(OrezResearchRequest("Android operating system", ResearchFreshnessRequest.CURRENT_REQUESTED)) { true }!!
        assertEquals(listOf("duckduckgo-html", "wikipedia-rest"), evidence.providers.map { it.providerId })
        assertEquals("wikipedia-rest", evidence.citations.single().providerId)
        assertFalse(evidence.citations.single().excerpt.contains("provider snippet"))
        assertEquals(4, server.requestCount)
        assertTrue(OrezResearchEvidenceCodec.encode(evidence).contains("UNVERIFIED"))
    }
    @Test fun realRedirectChainRecordsEachResponseAndRejectsPrivateLocationBeforeConnection() = fixture { server, transport, connections ->
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://second.test/final"))
        server.enqueue(response("A real final source with readable text."))
        val capture = transport.get("https://source.test/alias", ResearchOperationBudget()) { true }.capture
        assertEquals(listOf(302, 200), capture.hops.map { it.status })
        assertEquals("https://second.test/final", capture.finalUrl)
        val count = connections.get()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1/private"))
        assertTrue(runCatching { transport.get("https://source.test/deny", ResearchOperationBudget()) { true } }.isFailure)
        assertEquals(count, connections.get()) // existing public connection reused, no private socket
        assertEquals(3, server.requestCount)
    }
    @Test fun privateLiteralIsDeniedWithZeroActualSocketsOrRequests() = fixture { server, transport, connections ->
        assertTrue(runCatching { transport.get("http://127.0.0.1/private", ResearchOperationBudget()) { true } }.isFailure)
        assertEquals(0, connections.get()); assertEquals(0, server.requestCount)
    }
    @Test fun cancellationClosesAnActualHeaderStallAndNeverStartsFallback() = fixture { server, transport, _ ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        coroutineScope {
            val job = launch(Dispatchers.IO) { OrezResearchHost(transport, { 1000L }).research(OrezResearchRequest("Android offline", ResearchFreshnessRequest.NOT_SPECIFIED)) { true } }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            withTimeout(2500) { job.cancelAndJoin() }
            assertTrue(job.isCancelled); assertEquals(1, server.requestCount)
        }
    }
    @Test fun ownershipRetirementCancelsTheHeldActualCallBeforeAnyEvidenceReturns() = fixture { server, transport, _ ->
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val owned = java.util.concurrent.atomic.AtomicBoolean(true)
        var published = false
        coroutineScope {
            val job = launch(Dispatchers.IO) { OrezResearchHost(transport, { 1000L }).research(OrezResearchRequest("Android offline", ResearchFreshnessRequest.NOT_SPECIFIED)) { owned.get() }; published = true }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)); owned.set(false)
            withTimeout(2500) { job.join() }
            assertTrue(job.isCancelled); assertFalse(published); assertEquals(1, server.requestCount)
        }
    }
    @Test fun cappedBodyReportsAnExactPrefixDigestAndNeverCallsItAWholePage() = fixture { server, transport, _ ->
        val body = "a".repeat(1_500_001)
        server.enqueue(response(body))
        val document = transport.get("https://source.test/large", ResearchOperationBudget()) { true }
        assertEquals(1_500_000, document.capture.capturedBytes); assertTrue(document.capture.bodyTruncated)
        assertEquals(researchSha256(body.take(1_500_000).toByteArray()), document.capture.bodySha256)
    }
}
