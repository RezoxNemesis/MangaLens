package com.mangalens

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.core.app.ActivityOptionsCompat
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.mangalens.ui.web.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.mockwebserver.Dispatcher as HttpDispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual production browser component/WebView/AtomicFile controls with isolated state.
 * The chooser registry supplies a controlled explicit platform result; this does not claim a
 * DocumentsUI/provider login/public-source/device PASS until parent runs these methods.
 */
@RunWith(AndroidJUnit4::class)
class BrowserWorkspaceControlsTest {
    @Test fun failedAddressPersistenceKeepsTheOldSessionAndRetryLoadsTheRequestedPage() = fixture { f ->
        f.page("Fixture one")
        f.io.failNext.set(true)
        f.revealChrome(); f.tap(By.text("URL")); f.openAddress(f.otherUrl)
        f.node(By.textContains("Page address could not be saved"))
        assertEquals(f.firstUrl, f.session.state.value!!.activeTab.url)
        assertEquals(f.firstUrl, BrowserWorkspaceStore(AtomicBrowserWorkspaceIo(f.file)).snapshot().activeTab.url)
        f.tap(By.text("Retry")); f.page("Fixture other")
        assertEquals(f.otherUrl, BrowserWorkspaceStore(AtomicBrowserWorkspaceIo(f.file)).snapshot().activeTab.url)
    }

    @Test fun realTabsBookmarksAndHistorySurviveColdBrowserSessionReopening() = fixture { f ->
        f.page("Fixture one")
        f.tap(By.text("Go second fixture")); f.page("Fixture two")
        f.tools(); f.tap(By.text("Bookmark this page")); f.tap(By.text("Close"))
        f.tabs(); f.tap(By.text("New tab"))
        f.openAddress(f.otherUrl); f.page("Fixture other")
        val selected = f.session.state.value!!.activeTabId
        f.waitFor("Two actual tabs did not persist") { f.session.state.value?.tabs?.size == 2 }
        f.tools(); f.tap(By.text("History")); f.node(By.text("Fixture two")); f.node(By.text("Fixture one")); f.tap(By.text("Close"))
        f.coldReopen()
        f.page("Fixture other")
        assertEquals(selected, f.session.state.value!!.activeTabId)
        assertEquals(2, f.session.state.value!!.tabs.size)
        f.tools(); f.tap(By.text("Bookmarks")); f.tap(By.text("Fixture two")); f.page("Fixture two")
        assertEquals(f.secondUrl, f.session.state.value!!.activeTab.url)
        assertEquals("Fixture two", f.session.state.value!!.bookmarks.single().title)
    }

    @Test fun actualFindAndDesktopModeUseWebViewResultsAndTheReloadedRequest() = fixture { f ->
        f.page("Fixture one")
        f.tools(); f.tap(By.text("Find on page"))
        f.node(By.desc("Find text on page")).text = "needle"
        f.tap(By.text("Find")); f.node(By.text("Match 1 of 2"))
        f.tap(By.text("Next")); f.node(By.text("Match 2 of 2")); f.tap(By.text("Close"))
        f.tools(); f.tap(By.text("Use desktop site")); f.page("Fixture one")
        f.waitFor("Desktop choice was not durable") { f.session.state.value?.activeTab?.desktop == true }
        f.waitFor("Actual browser reload did not carry the desktop user agent") {
            f.requests.any { it.path?.startsWith("/first") == true && it.getHeader("User-Agent")?.contains("X11; Linux x86_64") == true }
        }
        assertEquals(1, f.session.state.value!!.activeTab.entries.size)
    }

    @Test fun actualFileInputReceivesOnlyTheExplicitReadableDocumentResult() = fixture { f ->
        f.page("Fixture one"); f.tap(By.text("Choose fixture document"))
        f.waitFor("WebChromeClient never launched the explicit document chooser") { f.registry.pending.get() != null }
        val launched = f.registry.pending.get()!!
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, launched.second.action)
        assertTrue(launched.second.categories.orEmpty().contains(Intent.CATEGORY_OPENABLE))
        assertEquals("text/plain", launched.second.type)
        assertFalse(launched.second.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        f.deliverDocument()
        f.node(By.text("Uploaded fixture contents"))
        assertFalse("Uploads must not relax WebView file navigation", f.session.state.value!!.activeTab.url.startsWith("file:"))
    }

    @Test fun chooserResultAfterSelectingAnotherTabCannotPopulateTheNewPagesInput() = fixture { f ->
        f.page("Fixture one"); f.tap(By.text("Choose fixture document"))
        f.waitFor("Initial owned chooser request was not issued") { f.registry.pending.get() != null }
        f.tabs(); f.tap(By.text("New tab")); f.openAddress(f.otherUrl); f.page("Fixture other")
        f.deliverDocument()
        f.node(By.text("No uploaded document"))
        assertNull(f.device.findObject(By.text("Uploaded fixture contents")))
        assertEquals(f.otherUrl, f.session.state.value!!.activeTab.url)
    }

    private fun fixture(check: (Fixture) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "browser-qa-${UUID.randomUUID()}")
        val upload = File(context.filesDir, "downloads/browser-upload-${UUID.randomUUID()}.txt").apply {
            parentFile!!.mkdirs(); writeText("Uploaded fixture contents")
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.downloads", upload)
        val server = MockWebServer(); val requests = ConcurrentLinkedQueue<RecordedRequest>()
        server.dispatcher = object : HttpDispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                val title = when (request.path?.substringBefore('?')) { "/second" -> "Fixture two"; "/other" -> "Fixture other"; else -> "Fixture one" }
                return MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody("""
                    <!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"><title>$title</title></head>
                    <body style="padding:90px 16px;font-size:20px"><h1>$title</h1><p>needle one</p><p>needle two</p>
                    <a href="/second">Go second fixture</a><p><button onclick="document.getElementById('upload').click()">Choose fixture document</button></p>
                    <input id="upload" type="file" accept="text/plain" style="display:none" onchange="var f=this.files[0];if(f){var r=new FileReader();r.onload=function(){document.getElementById('contents').innerText=r.result};r.readAsText(f)}">
                    <p id="contents">No uploaded document</p></body></html>
                """.trimIndent())
            }
        }
        server.start()
        try {
        // Resolve controlled server URLs before entering Android's main callback.
        val first = server.url("/first").toString(); val second = server.url("/second").toString(); val other = server.url("/other").toString()
        val registry = ChooserRegistry()
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
            val f = Fixture(scenario, File(directory, "session.json"), registry, requests, first, second, other, uri)
            try { f.render(first); check(f) }
            finally { scenario.onActivity { it.setContent {} }; f.scope.cancel(); directory.deleteRecursively() }
        }
        } finally { server.shutdown(); upload.delete(); directory.deleteRecursively() }
    }

    private class ChooserRegistry : ActivityResultRegistry() {
        val pending = AtomicReference<Pair<Int, Intent>?>(null)
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(pending.compareAndSet(null, requestCode to contract.createIntent(context, input))) { "A second platform chooser replaced the first" }
        }
    }

    private class RetryableBrowserIo(private val delegate: BrowserWorkspaceIo) : BrowserWorkspaceIo {
        val failNext = AtomicBoolean(false)
        override fun read(maxBytes: Int): ByteArray? = delegate.read(maxBytes)
        override fun write(bytes: ByteArray) {
            if (failNext.getAndSet(false)) throw IOException("Controlled browser write failure")
            delegate.write(bytes)
        }
    }

    private class Fixture(
        val scenario: ActivityScenario<MainActivity>, val file: File, val registry: ChooserRegistry,
        val requests: ConcurrentLinkedQueue<RecordedRequest>, val firstUrl: String, val secondUrl: String,
        val otherUrl: String, val document: android.net.Uri
    ) {
        val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        var io = RetryableBrowserIo(AtomicBrowserWorkspaceIo(file))
        var session = BrowserWorkspaceSession(io, scope)
        fun render(url: String) = scenario.onActivity { activity ->
            activity.setContent { key(session) {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                    override val activityResultRegistry = registry
                }) { MaterialTheme { BrowserWorkspaceScreen(session, url, translationEnabled = false, adBlockEnabled = true) } }
            } }
        }
        fun coldReopen() {
            scope.cancel(); scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            io = RetryableBrowserIo(AtomicBrowserWorkspaceIo(file))
            session = BrowserWorkspaceSession(io, scope)
            render("")
        }
        fun node(selector: BySelector): UiObject2 {
            var found: UiObject2? = null
            waitFor("Missing actual browser control $selector") { device.findObject(selector).also { found = it } != null }
            return found!!
        }
        fun tap(selector: BySelector) { node(selector).click() }
        fun page(title: String) { node(By.text(title)); waitFor("Accepted main page did not reach durable history") { session.state.value?.history?.any { it.title == title } == true } }
        fun revealChrome() { device.click(device.displayWidth / 2, device.displayHeight / 3) }
        fun tools() { revealChrome(); tap(By.desc("Browser tools")) }
        fun tabs() { revealChrome(); tap(By.desc("Browser tabs")) }
        fun openAddress(url: String) {
            node(By.desc("Website URL or search")).text = url
            tap(By.text("Open"))
        }
        fun deliverDocument() {
            val pending = registry.pending.getAndSet(null) ?: error("Missing chooser")
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                registry.dispatchResult(pending.first, Activity.RESULT_OK, Intent().setData(document).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
        }
        fun waitFor(message: String, timeout: Long = 12_000, predicate: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + timeout
            while (SystemClock.elapsedRealtime() < until) {
                if (predicate()) return
                SystemClock.sleep(40)
            }
            assertTrue(message, predicate())
        }
    }
}
