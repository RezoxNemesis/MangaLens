package com.mangalens

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real WebView/network/UI paths. Debug cleartext is scoped to loopback fixtures. */
@RunWith(AndroidJUnit4::class)
class WebNavigationSmokeTest {
    @Test fun addressValidationLoadingBrowserBackAndNetworkRetryUseTheRealWebRoute() = coreScreenSmoke("web-navigation") {
        val prefs = context.getSharedPreferences("mangalens_preferences", Context.MODE_PRIVATE)
        val restore = restorePreferencesAfter(prefs, "last_url", "translation_web")
        val server = MockWebServer()
        val trace = WebNavigationFixtureTrace(android.os.SystemClock::elapsedRealtime)
        val firstPageRelease = WebFirstDocumentGate(trace)
        val recoveryReady = AtomicBoolean(false)
        val firstVisits = AtomicInteger()
        val secondVisits = AtomicInteger()
        val recoveryVisits = AtomicInteger()
        val brokenImageVisits = AtomicInteger()
        fun page(title: String, body: String): MockResponse {
            val nextLink = if (title == "MangaLens fixture one") "<a href='/second'>Read chapter two</a>" else ""
            val brokenImage = if (title == "MangaLens fixture one") "<img src='/broken-image' alt='Fixture missing illustration'>" else ""
            return MockResponse().setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<!doctype html><html><head><title>$title</title><meta name='viewport' content='width=device-width,initial-scale=1'></head>" +
                    "<body style='font:24px sans-serif;padding:140px 16px 160px'><h1>$body</h1>$nextLink$brokenImage</body></html>")
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                trace.record("server_dispatch", mapOf("method" to request.method.orEmpty(), "path" to request.path.orEmpty()))
                val response = when (request.path?.substringBefore('?')) {
                    "/first" -> {
                        firstVisits.incrementAndGet()
                        if (firstPageRelease.awaitRelease()) page("MangaLens fixture one", "Fixture chapter one")
                        else MockResponse().setResponseCode(408)
                    }
                    "/second" -> { secondVisits.incrementAndGet(); page("MangaLens fixture two", "Fixture chapter two") }
                    "/recover" -> {
                        recoveryVisits.incrementAndGet()
                        if (recoveryReady.get()) page("MangaLens fixture recovered", "Fixture connection restored")
                        else MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    }
                    "/broken-image" -> { brokenImageVisits.incrementAndGet(); MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) }
                    else -> MockResponse().setResponseCode(204)
                }
                trace.record("server_prepared_response", mapOf("path" to request.path.orEmpty(), "status" to response.status,
                    "socket_policy" to response.socketPolicy.name, "recovery_ready" to recoveryReady.get().toString(),
                    "first_visits" to firstVisits.get().toString(), "second_visits" to secondVisits.get().toString(),
                    "recovery_visits" to recoveryVisits.get().toString(), "broken_image_visits" to brokenImageVisits.get().toString()))
                return response
            }
        }
        server.start()
        val probe = WebNavigationProbe(context, trace, server.url("/").toString())
        try {
            prefs.edit().putString("last_url", "").putBoolean("translation_web", false).commit()
            launchHome()
            tap(By.desc("Web browser"))
            node(By.text("Search or open a website"))
            val address = node(By.clazz("android.widget.EditText"))
            address.text = "javascript:alert('untrusted')"
            tap(By.text("Open"))
            node(By.text("Use an HTTP(S) URL or a search phrase."))
            assertTrue("Invalid address caused a fixture request", server.requestCount == 0)
            capture("unsafe-address-rejected")
            node(By.clazz("android.widget.EditText")).text = server.url("/first").toString()
            probe.record("first_open_requested")
            clickSettledUi(device, By.text("Open").pkg(context.packageName), 15_000,
                ready = {
                    device.findObject(By.clazz("android.widget.EditText").pkg(context.packageName))?.text == server.url("/first").toString() &&
                        device.findObject(By.text("Use an HTTP(S) URL or a search phrase.")) == null
                }, beforeClick = { bounds -> probe.record("first_open_admitted", "bounds" to bounds.toString()) })
            probe.record("first_open_returned")
            probe.sample("after-first-open")
            node(By.text("■"), 5_000)
            probe.record("loading_control_visible")
            firstPageRelease.captureLoading {
                probe.record("loading_capture_started")
                probe.sample("before-loading-capture")
                capture("page-loading")
                probe.record("loading_capture_finished")
            }
            probe.sample("before-first-heading-wait")
            node(By.text("Fixture chapter one"))
            probe.record("first_heading_visible")
            probe.sample("first-heading-visible")
            assertTrue("Address action never requested the first page", firstVisits.get() > 0)
            waitFor("Subresource failure was never requested") { brokenImageVisits.get() > 0 }
            assertTrue("A failed image incorrectly replaced the successful document with a load error",
                device.findObject(By.text("Page could not load. Check your connection and retry.")) == null)
            capture("first-page")
            tap(By.text("Read chapter two"))
            node(By.text("Fixture chapter two"))
            probe.record("second_heading_visible")
            probe.sample("second-heading-visible")
            assertTrue("WebView link never requested the next page", secondVisits.get() > 0)
            capture("second-page")
            device.pressBack()
            node(By.text("Fixture chapter one"))
            probe.record("browser_back_first_heading_visible")
            probe.sample("browser-back-first-heading-visible")
            capture("browser-back-restored-first-page")
            revealWebControls().click()
            node(By.clazz("android.widget.EditText")).text = server.url("/recover").toString()
            probe.record("recovery_open_requested")
            clickSettledUi(device, By.text("Open").pkg(context.packageName), 15_000,
                ready = {
                    device.findObject(By.clazz("android.widget.EditText").pkg(context.packageName))?.text == server.url("/recover").toString()
                }, beforeClick = { bounds -> probe.record("recovery_open_admitted", "bounds" to bounds.toString()) })
            probe.record("recovery_open_returned")
            probe.sample("after-recovery-open")
            node(By.text("Page could not load. Check your connection and retry."))
            probe.record("recovery_error_visible")
            probe.sample("recovery-error-visible")
            assertTrue("Network-failure fixture was never reached", recoveryVisits.get() > 0)
            android.os.SystemClock.sleep(3_200) // Longer than the browser's 2.5-second chrome timer.
            node(By.text("Page could not load. Check your connection and retry."))
            node(By.text("Retry"))
            node(By.text("Open source"))
            node(By.text("Back"))
            node(By.text("↻"))
            assertTrue("Failed navigation left an infinite loading control", device.findObject(By.text("■")) == null)
            probe.record("recovery_error_persisted")
            probe.sample("before-network-error-capture")
            capture("network-error")
            recoveryReady.set(true)
            probe.record("recovery_server_ready")
            probe.record("recovery_retry_requested")
            tap(By.text("Retry"))
            probe.record("recovery_retry_returned")
            probe.sample("before-recovery-heading-wait")
            node(By.text("Fixture connection restored"))
            probe.record("recovery_heading_visible")
            probe.sample("recovery-heading-visible")
            assertTrue("Reload did not make a new network request", recoveryVisits.get() >= 2)
            capture("reload-recovered")
            device.pressBack()
            node(By.text("Fixture chapter one"))
        } catch (failure: Throwable) {
            probe.record("test_failure", "error" to failure.toString(), "first_visits" to firstVisits.get().toString(),
                "second_visits" to secondVisits.get().toString(), "recovery_visits" to recoveryVisits.get().toString(),
                "broken_image_visits" to brokenImageVisits.get().toString(), "request_count" to server.requestCount.toString())
            probe.sample("failure")
            runCatching { recordFailure(failure) }
            throw failure
        } finally {
            firstPageRelease.release("test-finally")
            probe.stopObservations()
            try { finishActivity(); server.shutdown() }
            finally {
                runCatching { probe.close() }.onFailure { android.util.Log.e("WebNavFixture", "Could not export fixture trace", it) }
                restore()
            }
        }
    }
}
