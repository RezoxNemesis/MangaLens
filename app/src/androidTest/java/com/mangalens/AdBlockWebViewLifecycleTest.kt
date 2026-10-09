package com.mangalens

import android.content.Intent
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.core.adblock.AdBlockEngine
import com.mangalens.core.adblock.AdBlockWebViewClient
import com.mangalens.core.adblock.interceptBeforeMediaObservation
import com.mangalens.ui.video.VideoSourcePolicy
import com.mangalens.core.web.SafeWebView
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real controlled WebView tests, including the callback path used by older providers. */
@RunWith(AndroidJUnit4::class)
class AdBlockWebViewLifecycleTest {
    @Test fun nativeBlockingDecisionPrecedesMediaCandidatePublicationAndCountsOnce() = fixture(true) { f ->
        f.waitSnapshot { it.optBoolean("guard") && it.optString("adDisplay") == "none" }
        fun request(path: String) = object : WebResourceRequest {
            override fun getUrl() = f.server.url(path).toString().let(android.net.Uri::parse)
            override fun isForMainFrame() = false
            override fun isRedirect() = false
            override fun hasGesture() = false
            override fun getMethod() = "GET"
            override fun getRequestHeaders() = mapOf("Sec-Fetch-Dest" to "video")
        }
        f.mediaObservations.clear()
        val before = f.engine.statsStore.stats.value.blockedRequests
        val blocked = f.client.shouldInterceptRequest(f.web, request("/preroll/ad.mp4"))
        assertNotNull(blocked)
        // Native interception keeps its existing empty-200 response contract;
        // only the separately tested JavaScript fetch wrapper returns 204.
        assertEquals(200, blocked!!.statusCode)
        assertEquals(-1, blocked.data.read())
        blocked.data.close()
        assertTrue("Blocked response became a native media candidate", f.mediaObservations.isEmpty())
        assertEquals(before + 1, f.engine.statsStore.stats.value.blockedRequests)
        assertNull(f.client.shouldInterceptRequest(f.web, request("/media/clip.mp4?campaign=editorial")))
        assertEquals(listOf(f.server.url("/media/clip.mp4?campaign=editorial").toString()), f.mediaObservations.toList())
    }

    @Test fun documentStartWhenSupportedAndFallbackKeepThePageResponsive() = fixture(false) { f ->
        val initial = f.waitSnapshot { it.optBoolean("guard") && it.optString("adDisplay") == "none" }
        if (f.earlyInstalled) assertTrue("Supported provider did not run before the first page script", initial.optBoolean("early"))
        f.assertMediaAndConsentVisible(initial)
        f.triggerMutation()
        val settled = f.waitSnapshot { it.optBoolean("mutationComplete") && it.optString("adDisplay") == "none" }
        assertTrue("Mutation processing starved the page heartbeat", settled.optInt("heartbeat") > initial.optInt("heartbeat"))
        f.assertMediaAndConsentVisible(settled)
        assertEquals("Known ad script reached the loopback server", 0, f.adRequests.get())
        assertTrue("Native interception did not record its blocked ad", f.engine.statsStore.stats.value.blockedRequests > 0)
    }

    @Test fun olderProviderCallbackFallbackIsIdempotentAndReturnsAValidBlockedFetch() = fixture(true) { f ->
        assertFalse(f.earlyInstalled)
        f.waitSnapshot { it.optBoolean("guard") && it.optString("adDisplay") == "none" }
        f.evaluate(f.engine.getElementHidingScript())
        f.evaluate(f.engine.getElementHidingScript())
        f.triggerMutation()
        f.waitSnapshot { it.optBoolean("mutationComplete") && it.optString("adDisplay") == "none" }
        f.evaluate("""
            window.fixtureFetch=null;
            fetch('/preroll/ad.mp4').then(function(r){return r.text().then(function(t){
              window.fixtureFetch={status:r.status,text:t};
            });}).catch(function(e){window.fixtureFetch={error:e.name};});
            fetch('/media/clip.mp4?campaign=editorial').then(function(r){
              window.fixtureMediaStatus=r.status;
            }).catch(function(e){window.fixtureMediaStatus=-1;});
        """.trimIndent())
        val result = f.waitSnapshot { it.optJSONObject("fetch") != null && it.optInt("mediaStatus") != 0 }
        val fetch = result.getJSONObject("fetch")
        assertFalse(fetch.toString(), fetch.has("error"))
        assertEquals(204, fetch.getInt("status"))
        assertEquals("", fetch.getString("text"))
        assertEquals(200, result.getInt("mediaStatus"))
        assertEquals("Blocked preroll reached the server", 0, f.prerollRequests.get())
        f.assertMediaAndConsentVisible(result)
    }

    @Test fun disablingBeforeNavigationRemovesTheEarlyLeaseAndDoesNotReinstallTheGuard() = fixture(false) { f ->
        f.waitSnapshot { it.optBoolean("guard") && it.optString("adDisplay") == "none" }
        f.enabled.set(false)
        f.onMain {
            f.client.prepareForNavigation(f.web)
            assertFalse(f.client.hasDocumentStartScript)
            f.web.loadUrl(f.server.url("/fixture?disabled=1").toString())
        }
        val result = f.waitSnapshot { it.optBoolean("adScriptExecuted") && !it.optBoolean("guard") }
        assertFalse(result.optBoolean("early"))
        assertNotEquals("none", result.optString("adDisplay"))
        assertTrue(f.adRequests.get() > 0)
        f.assertMediaAndConsentVisible(result)
    }

    private fun fixture(forceFallback: Boolean, verify: (Fixture) -> Unit) {
        val server = MockWebServer()
        val adRequests = AtomicInteger(); val prerollRequests = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path?.substringBefore('?')) {
                "/fixture" -> MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(HTML)
                "/ads/ad.js" -> { adRequests.incrementAndGet(); MockResponse().setBody("window.fixtureAdExecuted=true;") }
                "/preroll/ad.mp4" -> { prerollRequests.incrementAndGet(); MockResponse().setBody("fixture ad response") }
                "/media/clip.mp4" -> MockResponse().setBody("fixture media response")
                else -> MockResponse().setResponseCode(204)
            }
        }
        server.start()
        val enabled = AtomicBoolean(true)
        val engine = AdBlockEngine()
        val mediaObservations = ConcurrentLinkedQueue<String>()
        val client = object : AdBlockWebViewClient(engine, enabled::get) {
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? =
                interceptBeforeMediaObservation({ super.shouldInterceptRequest(view, request) }) {
                    val url = request?.url?.toString()
                    if (url != null && VideoSourcePolicy.isLikelyMediaRequest(url, request.requestHeaders.orEmpty()))
                        mediaObservations.add(url)
                }
        }.also { it.allowDocumentStart = !forceFallback }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            lateinit var web: WebView
            var installed = false
            scenario.onActivity { activity ->
                web = WebView(activity)
                SafeWebView.configure(web)
                web.webViewClient = client
                activity.addContentView(web, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                client.prepareForNavigation(web)
                installed = client.hasDocumentStartScript
                web.loadUrl(server.url("/fixture").toString())
            }
            try { verify(Fixture(server, web, client, engine, enabled, installed, adRequests, prerollRequests, mediaObservations)) }
            finally {
                runCatching { scenario.onActivity {
                    client.clearScriptRegistration()
                    web.stopLoading()
                    (web.parent as? ViewGroup)?.removeView(web)
                    web.destroy()
                } }
                server.shutdown()
            }
        }
    }

    private class Fixture(
        val server: MockWebServer, val web: WebView, val client: AdBlockWebViewClient,
        val engine: AdBlockEngine, val enabled: AtomicBoolean, val earlyInstalled: Boolean,
        val adRequests: AtomicInteger, val prerollRequests: AtomicInteger,
        val mediaObservations: ConcurrentLinkedQueue<String>
    ) {
        fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
        fun evaluate(script: String) = onMain { web.evaluateJavascript(script, null) }
        fun triggerMutation() = evaluate("""
            window.fixtureMutationComplete=false;
            var ad=document.getElementById('fixtureAd');
            ad.className='ad-container changed'; ad.style.display='block';
            setTimeout(function(){window.fixtureMutationComplete=true;},120);
        """.trimIndent())

        fun waitSnapshot(predicate: (JSONObject) -> Boolean): JSONObject {
            val until = SystemClock.elapsedRealtime() + 15_000
            var last = JSONObject()
            while (SystemClock.elapsedRealtime() < until) {
                val latch = CountDownLatch(1)
                val result = AtomicReference<String?>()
                onMain { web.evaluateJavascript(SNAPSHOT) { result.set(it); latch.countDown() } }
                if (latch.await(500, TimeUnit.MILLISECONDS)) {
                    val snapshot = runCatching {
                        JSONObject(JSONTokener(result.get().orEmpty()).nextValue() as String)
                    }.getOrNull()
                    if (snapshot != null) { last = snapshot; if (predicate(snapshot)) return snapshot }
                }
                SystemClock.sleep(50)
            }
            throw AssertionError("Controlled ad-block fixture did not reach its expected state: $last")
        }

        fun assertMediaAndConsentVisible(snapshot: JSONObject) {
            assertTrue(snapshot.toString(), snapshot.optBoolean("videoConnected"))
            assertNotEquals("none", snapshot.optString("videoDisplay"))
            assertNotEquals("none", snapshot.optString("consentDisplay"))
            assertNotEquals("hidden", snapshot.optString("consentVisibility"))
        }
    }

    companion object {
        private val HTML = """
            <!doctype html><html><head><title>Controlled ad-block fixture</title>
            <script>window.fixtureEarly=!!window.__mangalensAdGuardV2; window.fixtureHeartbeat=0;
              setInterval(function(){window.fixtureHeartbeat++;},30);</script>
            <script src='/ads/ad.js'></script></head><body>
            <div id='fixtureAd' class='ad-container'>Controlled ad slot</div>
            <div id='fixtureConsent' class='login-popup' role='dialog'><form><button>Consent control</button></form></div>
            <div id='fixturePlayer' class='video-popup'><video id='fixtureVideo' controls></video><button>Playback control</button></div>
            </body></html>
        """.trimIndent()
        private val SNAPSHOT = """
            (function(){
              if(location.hostname!=='localhost' && location.hostname!=='127.0.0.1') return JSON.stringify({skipped:true});
              var ad=document.getElementById('fixtureAd'), player=document.getElementById('fixturePlayer');
              var consent=document.getElementById('fixtureConsent'), video=document.getElementById('fixtureVideo');
              return JSON.stringify({guard:!!window.__mangalensAdGuardV2,early:!!window.fixtureEarly,
                heartbeat:window.fixtureHeartbeat||0,mutationComplete:!!window.fixtureMutationComplete,
                adDisplay:ad?getComputedStyle(ad).display:null,adScriptExecuted:!!window.fixtureAdExecuted,
                consentDisplay:consent?getComputedStyle(consent).display:null,
                consentVisibility:consent?getComputedStyle(consent).visibility:null,
                videoConnected:!!(video && document.documentElement.contains(video)),
                videoDisplay:player?getComputedStyle(player).display:null,
                fetch:window.fixtureFetch||null,mediaStatus:window.fixtureMediaStatus||0});
            })();
        """.trimIndent()
    }
}
