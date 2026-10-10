package com.mangalens

import android.content.Intent
import android.os.SystemClock
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.core.adblock.*
import com.mangalens.core.web.SafeWebView
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Authored UNRUN real-client mode controls. Loopback evidence does not qualify public-provider ad coverage. */
@RunWith(AndroidJUnit4::class)
class BrowserProtectionModeInstrumentedTest {
    @Test fun strictAddsExplicitAdRulesAndAllowRemovesBothNativeAndDomFiltering() {
        val server = MockWebServer(); val explicit = AtomicInteger(); val standard = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path?.substringBefore('?')) {
                "/fixture" -> MockResponse().setHeader("Content-Type", "text/html").setBody(HTML)
                "/adrequest/slot.js" -> { explicit.incrementAndGet(); MockResponse().setBody("window.strictSource=true;") }
                "/ads/slot.js" -> { standard.incrementAndGet(); MockResponse().setBody("window.standardSource=true;") }
                else -> MockResponse().setResponseCode(204)
            }
        }
        server.start()
        val urls = listOf("standard", "strict", "allow").map { server.url("/fixture?mode=$it").toString() }
        val mode = AtomicReference(AdBlockMode.STANDARD)
        val client = AdBlockWebViewClient(AdBlockEngine(), { true }, { mode.get() })
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java)).use { scenario ->
                lateinit var web: WebView
                scenario.onActivity { activity ->
                    web = WebView(activity); SafeWebView.configure(web); web.webViewClient = client
                    activity.addContentView(web, ViewGroup.LayoutParams(-1, -1))
                    client.prepareForNavigation(web, urls[0]); web.loadUrl(urls[0])
                }
                try {
                    val first = waitSnapshot(web) { it.optBoolean("strictSource") && it.optString("standardAd") == "none" }
                    assertNotEquals("none", first.getString("strictAd")); assertEquals("none", first.getString("bothAd")); assertProtected(first)
                    val beforeExplicit = explicit.get()
                    mode.set(AdBlockMode.STRICT)
                    onMain { client.prepareForNavigation(web, urls[1]); web.loadUrl(urls[1]) }
                    val strict = waitSnapshot(web) { it.optString("strictAd") == "none" && it.optBoolean("strict") }
                    assertEquals("none", strict.getString("bothAd")); assertFalse(strict.optBoolean("strictSource")); assertEquals(beforeExplicit, explicit.get()); assertProtected(strict)
                    onMain { web.evaluateJavascript("window.detachedAd=document.getElementById('strictAd');detachedAd.parentNode.removeChild(detachedAd);", null) }
                    mode.set(AdBlockMode.ALLOW)
                    onMain {
                        client.prepareForNavigation(web, urls[1]); assertFalse(client.hasDocumentStartScript)
                        web.evaluateJavascript("document.body.appendChild(window.detachedAd);", null)
                    }
                    val reinserted = waitSnapshot(web) { !it.optBoolean("guard") && it.optString("strictAd") != "none" }
                    assertNotEquals("none", reinserted.getString("strictAd")); assertProtected(reinserted)
                    onMain { client.prepareForNavigation(web, urls[2]); assertFalse(client.hasDocumentStartScript); web.loadUrl(urls[2]) }
                    val allowed = waitSnapshot(web) { it.optBoolean("standardSource") && it.optBoolean("strictSource") && !it.optBoolean("guard") }
                    assertNotEquals("none", allowed.getString("bothAd")); assertNotEquals("none", allowed.getString("standardAd")); assertNotEquals("none", allowed.getString("strictAd"))
                    assertTrue(standard.get() > 0); assertTrue(explicit.get() > beforeExplicit); assertProtected(allowed)
                } finally { scenario.onActivity { client.clearScriptRegistration(); web.stopLoading(); (web.parent as? ViewGroup)?.removeView(web); web.destroy() } }
            }
        } finally { server.shutdown() }
    }
    private fun assertProtected(snapshot: JSONObject) {
        for (key in listOf("story", "player", "consent")) assertNotEquals(key, "none", snapshot.getString(key))
    }
    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(action)
    private fun waitSnapshot(web: WebView, predicate: (JSONObject) -> Boolean): JSONObject {
        val until = SystemClock.elapsedRealtime() + 15_000; var last = JSONObject()
        while (SystemClock.elapsedRealtime() < until) {
            val latch = CountDownLatch(1); val result = AtomicReference<String?>()
            onMain { web.evaluateJavascript(SNAPSHOT) { result.set(it); latch.countDown() } }
            if (latch.await(500, TimeUnit.MILLISECONDS)) {
                val snapshot = runCatching { JSONObject(JSONTokener(result.get().orEmpty()).nextValue() as String) }.getOrNull()
                if (snapshot != null) { last = snapshot; if (predicate(snapshot)) return snapshot }
            }
            SystemClock.sleep(50)
        }
        throw AssertionError("Controlled protection mode outcome unavailable: $last")
    }
    companion object {
        private val HTML = """<!doctype html><html><head><title>Protection mode fixture</title>
            <script src='/ads/slot.js'></script><script src='/adrequest/slot.js'></script></head><body>
            <div id='standardAd' class='ad-container'>Known advertising slot</div>
            <div id='strictAd' data-ad-unit='test'>Explicit ad unit</div>
            <div id='bothAd' class='ad-container' data-ad-unit='test'><img alt='ad DOM sentinel'>An existing Standard ad stays hidden in Strict.</div>
            <div id='story' data-ad-unit='not-authority'><img alt='original story art' src='data:image/png;base64,iVBORw0KGgo='>Actual story art must remain available</div>
            <div id='player' class='ad-container html5-video-player'><video></video></div>
            <div id='consent' class='ad-container' role='dialog'><form><input type='password'></form></div>
            </body></html>"""
        private val SNAPSHOT = """JSON.stringify({guard:!!window.__mangalensAdGuardV2,strict:!!(window.__mangalensAdGuardV2&&window.__mangalensAdGuardV2.protectionStrict),strictSource:!!window.strictSource,standardSource:!!window.standardSource,standardAd:getComputedStyle(document.getElementById('standardAd')).display,strictAd:getComputedStyle(document.getElementById('strictAd')).display,bothAd:getComputedStyle(document.getElementById('bothAd')).display,story:getComputedStyle(document.getElementById('story')).display,player:getComputedStyle(document.getElementById('player')).display,consent:getComputedStyle(document.getElementById('consent')).display})"""
    }
}
