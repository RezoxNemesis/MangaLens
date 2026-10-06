package com.mangalens

import android.net.Uri
import android.webkit.CookieManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.ui.video.MediaPlaybackDataSource
import com.mangalens.ui.video.MediaRequestContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class MediaPlaybackHeadersTest {
    private fun fixture(block: (MockWebServer, OkHttpClient) -> Unit) {
        val certificate = HeldCertificate.Builder().commonName("localhost")
            .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
        val client = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build()
        try { block(server, client) } finally {
            server.shutdown()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    private fun read(factory: androidx.media3.datasource.DataSource.Factory, url: String) {
        val source = factory.createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(Uri.parse(url))
                .setHttpRequestHeaders(mapOf("Authorization" to "should-never-leave", "Cookie" to "unscoped=1")).build())
            assertTrue(source.read(ByteArray(8), 0, 8) > 0)
        } finally { source.close() }
    }

    @Test fun redirectsRecomputeCookiesInsteadOfForwardingCapturedSession() = fixture { server, client ->
        val media = server.url("/master.m3u8").toString()
        val redirect = server.url("/segment.ts").newBuilder().host("127.0.0.1").build().toString()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", redirect))
        server.enqueue(MockResponse().setBody("fixture"))
        val context = MediaRequestContext(media, "https://page.example:8443/watch", mapOf("Cookie" to "private=1"))
        read(MediaPlaybackDataSource.factory(context, client) { null }, media)
        val first = server.takeRequest(5, TimeUnit.SECONDS)!!
        val second = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("private=1", first.getHeader("Cookie"))
        assertNull(second.getHeader("Cookie"))
        assertNull(first.getHeader("Authorization"))
        assertNull(second.getHeader("Authorization"))
        assertEquals("https://page.example:8443", second.getHeader("Origin"))
    }

    @Test fun eachSegmentUsesItsOwnBrowserCookieAndSessionsRemainIndependent() = fixture { server, client ->
        val master = server.url("/master.m3u8").toString()
        val segment = server.url("/segment.ts").newBuilder().host("127.0.0.1").build().toString()
        val old = MediaPlaybackDataSource.factory(MediaRequestContext(master, headers = mapOf("Cookie" to "old=1")), client) { null }
        val fresh = MediaPlaybackDataSource.factory(MediaRequestContext(master, headers = mapOf("Cookie" to "new=2")), client) { url ->
            if (url == segment) "segment=3" else null
        }
        repeat(3) { server.enqueue(MockResponse().setBody("fixture")) }
        read(fresh, master)
        read(old, master)
        read(fresh, segment)
        assertEquals("new=2", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Cookie"))
        assertEquals("old=1", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Cookie"))
        assertEquals("segment=3", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Cookie"))
    }

    @Test fun productionBrowserJarHonoursCookiePathOnSegmentRequests() = fixture { server, client ->
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val media = server.url("/private/master.m3u8").toString()
        val publicSegment = server.url("/public/segment.ts").toString()
        val cookieName = "mangalens_qa_${System.nanoTime()}"
        val jar = CookieManager.getInstance()
        val installed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            jar.setCookie(media, "$cookieName=private; Path=/private; Secure") { installed.countDown() }
        }
        assertTrue(installed.await(5, TimeUnit.SECONDS))
        try {
            repeat(2) { server.enqueue(MockResponse().setBody("fixture")) }
            val factory = MediaPlaybackDataSource.factory(MediaRequestContext(media), client)
            read(factory, media)
            read(factory, publicSegment)
            assertTrue(server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Cookie").orEmpty().contains("$cookieName=private"))
            assertFalse(server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Cookie").orEmpty().contains(cookieName))
        } finally {
            instrumentation.runOnMainSync { jar.setCookie(media, "$cookieName=; Path=/private; Max-Age=0; Secure") }
        }
    }
}
