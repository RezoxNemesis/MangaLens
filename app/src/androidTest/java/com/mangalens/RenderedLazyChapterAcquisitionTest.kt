package com.mangalens

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.acquisition.RenderedBrowserAcquirer
import com.mangalens.core.adblock.AdBlockEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Authored only: production WebView extraction against controlled lazy DOM mutations. */
@RunWith(AndroidJUnit4::class)
class RenderedLazyChapterAcquisitionTest {
    @Test fun scrollingCollectsLaterLazyNodesInDocumentOrderInsteadOfStoppingAtTheFirstImage() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.dispatcher = dispatcher(CountDownLatch(1)); server.start()
            val url = "http://127.0.0.1:${server.port}/chapter"
            val acquirer = RenderedBrowserAcquirer(InstrumentationRegistry.getInstrumentation().targetContext, AdBlockEngine()) { false }
            val result = withTimeout(25000) { acquirer.discoverWithCookie(url, 20000, null) }
            assertEquals((1..4).map { "http://127.0.0.1:${server.port}/page-$it.png" }, result.imageUrls)
            assertTrue(result.observations > 1); assertTrue(result.observations <= 24)
            assertFalse(result.discoveryLimited); assertNull(result.navigationError)
        }
    }
    @Test fun retiredCallerCannotReceiveALaterDocumentResult() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val firstImage = CountDownLatch(1); server.dispatcher = dispatcher(firstImage); server.start()
            val current = AtomicBoolean(true)
            val acquirer = RenderedBrowserAcquirer(InstrumentationRegistry.getInstrumentation().targetContext, AdBlockEngine()) { false }
            val work = async(Dispatchers.Main) { acquirer.discoverWithCookie("http://127.0.0.1:${server.port}/chapter", 20000, null, current::get) }
            try {
                withContext(Dispatchers.IO) { assertTrue(firstImage.await(10, TimeUnit.SECONDS)) }
                current.set(false)
                try { withTimeout(15000) { work.await() }; fail("A retired selection received acquisition results") }
                catch (_: CancellationException) { assertTrue(work.isCancelled) }
            } finally { current.set(false); work.cancel(); work.join() }
        }
    }
    private fun dispatcher(firstImage: CountDownLatch) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.path == "/chapter") return MockResponse().setHeader("Content-Type", "text/html").setBody("""
                <!doctype html><meta name="viewport" content="width=device-width,initial-scale=1">
                <body style="margin:0"><div class="reader-area" id="reader"></div><script>
                var count=0;
                function add(){var image=document.createElement('img');image.src='/page-'+(++count)+'.png';
                  image.style.display='block';image.style.width='32px';image.style.height=(window.innerHeight*1.5)+'px';
                  document.getElementById('reader').appendChild(image);}
                add();window.addEventListener('scroll',function(){if(count<4)add();});
                </script></body>
            """.trimIndent())
            if (request.path?.startsWith("/page-") == true) firstImage.countDown()
            // Discovery uses actual DOM source nodes, not image decoding or network request order.
            return MockResponse().setResponseCode(404)
        }
    }
}
