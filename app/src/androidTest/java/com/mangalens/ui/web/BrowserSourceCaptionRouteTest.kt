package com.mangalens.ui.web

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.mangalens.MainActivity
import com.mangalens.clickSettledUi
import com.mangalens.ui.video.SniffedMedia
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Real browser/WebView/HTTP routing. This proves neither provider cue retrieval nor ASR. */
@RunWith(AndroidJUnit4::class)
class BrowserSourceCaptionRouteTest {
    @Test fun sourceCaptionActionPrecedesAudioControlsAndRoutesWithoutModelConsentOrCapture() = fixture { f ->
        f.openSourceDialog()
        val action = f.node(By.text(SOURCE_ACTION))
        val audio = f.node(By.text("Live English audio subtitles"))
        assertTrue("The source route must precede the actual audio-model controls", action.visibleBounds.top < audio.visibleBounds.top)
        f.node(By.textContains("Install a multilingual speech model"))
        assertFalse("The fixture has no installed Whisper model", File(f.directory, "speech/whisper.bin").exists())
        clickSettledUi(f.device, By.text(SOURCE_ACTION), 10_000)

        f.await("The real current-page media was not delivered") { f.callbacks.size == 1 }
        val (media, page) = f.callbacks.single()
        assertEquals(f.firstUrl, page)
        assertEquals(f.mediaUrl, media.url)
        assertEquals(f.firstUrl, media.headers["Referer"])
        assertTrue(media.headers["User-Agent"].orEmpty().isNotBlank())
        // A generic HTTP page has no verified provider inventory; this is an actionable handoff.
        assertNull(media.providerCaptions)
        assertTrue("The route must resolve the actual page, not invent a media callback", f.firstRequests.get() >= 2)
        f.assertNoConsentOrCapture()
    }

    @Test fun navigatingWhileActualPageResolutionWaitsCannotDeliverTheOldMedia() = fixture { f ->
        f.openSourceDialog()
        f.blockNextFirst.set(true)
        clickSettledUi(f.device, By.text(SOURCE_ACTION), 10_000)
        assertTrue("The actual resolver never requested the source page", f.resolutionEntered.await(10, TimeUnit.SECONDS))
        try {
            runBlocking { withTimeout(10_000) {
                f.session.submit { store -> store.navigate(store.snapshot().activeTabId, f.otherUrl) }.await()
            } }
            f.node(By.text("Caption route other page"))
            f.await("The new current page did not become the durable browser selection") {
                f.session.state.value?.activeTab?.url == f.otherUrl &&
                    f.session.state.value?.history?.any { it.url == f.otherUrl && it.title == "Caption route other page" } == true
            }
        } finally { f.resolutionRelease.countDown() }
        assertTrue("The bounded resolver response did not return", f.resolutionReturned.await(10, TimeUnit.SECONDS))
        f.assertQuietCallbackWindow(2_000)
        assertEquals(f.otherUrl, f.session.state.value!!.activeTab.url)
        // Prove that rejection did not strand the current page's actionable route.
        f.openSourceDialog()
        clickSettledUi(f.device, By.text(SOURCE_ACTION), 10_000)
        f.await("The newly accepted page cannot route after an old resolution was rejected") { f.callbacks.size == 1 }
        assertEquals(f.otherUrl, f.callbacks.single().second)
        assertEquals(f.mediaUrl, f.callbacks.single().first.url)
        f.assertNoConsentOrCapture()
    }

    private fun fixture(verify: (Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        assertFalse("Finish an active user-approved capture before running this isolated routing test", WebAudioCaptureService.active.value)
        val video = instrumentation.context.assets.open("original-media/video-h264-720.mp4").use { it.readBytes() }
        val idle = Configurator.getInstance().waitForIdleTimeout
        Configurator.getInstance().setWaitForIdleTimeout(100)
        val directory = File(app.cacheDir, "source-caption-route-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = BrowserWorkspaceSession(AtomicBrowserWorkspaceIo(File(directory, "browser.json")), scope)
        val firstRequests = AtomicInteger()
        val blockNext = AtomicBoolean()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        try { MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path?.substringBefore('?')
                    if (path == "/fixture.mp4") {
                        val response = MockResponse().setHeader("Content-Type", "video/mp4")
                            .setHeader("Content-Length", video.size).setHeader("Accept-Ranges", "bytes")
                        if (request.method == "HEAD") return response
                        val range = Regex("bytes=(\\d+)-(\\d*)").matchEntire(request.getHeader("Range").orEmpty())
                        val first = range?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        val last = range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(video.lastIndex) ?: video.lastIndex
                        if (first !in video.indices || last < first) return MockResponse().setResponseCode(416)
                        if (range != null) response.setResponseCode(206).setHeader("Content-Range", "bytes $first-$last/${video.size}")
                        return response.setBody(Buffer().write(video, first, last - first + 1))
                    }
                    if (path !in setOf("/first", "/other")) return MockResponse().setResponseCode(204)
                    val held = path == "/first" && blockNext.compareAndSet(true, false)
                    if (path == "/first") firstRequests.incrementAndGet()
                    if (held) {
                        entered.countDown()
                        if (!release.await(20, TimeUnit.SECONDS)) return MockResponse().setResponseCode(408)
                    }
                    val title = if (path == "/first") "Caption route first page" else "Caption route other page"
                    val response = MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody("""
                        <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>$title</title></head>
                        <body style="padding:110px 16px;font:22px sans-serif"><h1>$title</h1>
                        <video controls preload="metadata" width="280" src="/fixture.mp4"></video></body></html>
                    """.trimIndent())
                    if (held) returned.countDown()
                    return response
                }
            }
            server.start()
            try {
                val first = server.url("/first").toString()
                val other = server.url("/other").toString()
                val media = server.url("/fixture.mp4").toString()
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    val registry = NoConsentRegistry()
                    lateinit var context: RoutingContext
                    val callbacks = ConcurrentLinkedQueue<Pair<SniffedMedia, String>>()
                    val status = WebAudioCaptureService.status.value
                    val permission = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    scenario.onActivity { activity ->
                        context = RoutingContext(activity, directory)
                        activity.setContent {
                            CompositionLocalProvider(LocalContext provides context,
                                LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                                    override val activityResultRegistry = registry
                                }) { MaterialTheme {
                                BrowserWorkspaceScreen(session, first, translationEnabled = false, adBlockEnabled = true,
                                    onOpenVideo = { result, page -> callbacks.add(result to page) })
                            } }
                        }
                    }
                    val f = Fixture(directory, session, callbacks, context, registry, status, permission,
                        first, other, media, firstRequests, blockNext, entered, release, returned)
                    try {
                        f.node(By.text("Caption route first page"))
                        f.await("The actual initial page never reached browser history") {
                            session.state.value?.history?.any { it.url == first && it.title == "Caption route first page" } == true
                        }
                        verify(f)
                    } finally { release.countDown(); scenario.onActivity { it.setContent {} } }
                }
            } finally { release.countDown() }
        } } finally {
            release.countDown()
            scope.cancel()
            directory.deleteRecursively()
            Configurator.getInstance().setWaitForIdleTimeout(idle)
        }
    }

    /** Model isolation is a real empty private directory; service calls retain platform behavior. */
    private class RoutingContext(base: Context, private val directory: File) : ContextWrapper(base) {
        val captureStarts = AtomicInteger()
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = directory
        override fun startService(service: Intent): ComponentName? {
            record(service); return super.startService(service)
        }
        override fun startForegroundService(service: Intent): ComponentName? {
            record(service); return super.startForegroundService(service)
        }
        private fun record(intent: Intent) {
            if (intent.component?.className == WebAudioCaptureService::class.java.name) captureStarts.incrementAndGet()
        }
    }

    private class NoConsentRegistry : ActivityResultRegistry() {
        val requests = AtomicInteger()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) {
            requests.incrementAndGet()
            throw AssertionError("Source-caption routing requested Android permission, capture or a document picker")
        }
    }

    private class Fixture(val directory: File, val session: BrowserWorkspaceSession,
        val callbacks: ConcurrentLinkedQueue<Pair<SniffedMedia, String>>, val context: RoutingContext,
        val registry: NoConsentRegistry, val initialCaptureStatus: String, val initialPermission: Int,
        val firstUrl: String, val otherUrl: String, val mediaUrl: String, val firstRequests: AtomicInteger,
        val blockNextFirst: AtomicBoolean, val resolutionEntered: CountDownLatch,
        val resolutionRelease: CountDownLatch, val resolutionReturned: CountDownLatch) {
        val device: UiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        fun openSourceDialog() {
            device.click(device.displayWidth / 2, device.displayHeight / 3)
            revealEnglishCcDock()
            clickSettledUi(device, By.text("English CC"), 10_000)
            node(By.text(SOURCE_ACTION))
        }
        private fun revealEnglishCcDock() {
            repeat(5) {
                device.findObject(By.text("English CC"))?.takeIf {
                    it.visibleBounds.width() >= 24 && it.visibleBounds.height() > 0
                }?.let { return }
                val anchor = listOf("Translate", "Open in Video", "Download", "Manga", "Site data")
                    .firstNotNullOfOrNull { label -> device.findObject(By.text(label))?.takeIf {
                        it.visibleBounds.width() > 0 && it.visibleBounds.height() > 0
                    } } ?: throw AssertionError("The actual lower browser dock is not visible")
                device.swipe(device.displayWidth * 9 / 10, anchor.visibleBounds.centerY(),
                    device.displayWidth / 10, anchor.visibleBounds.centerY(), 25)
            }
            assertNotNull("The actual lower dock could not reveal English CC", device.findObject(By.text("English CC")))
        }
        fun node(selector: BySelector): UiObject2 {
            var found: UiObject2? = null
            await("Missing actual browser control: $selector") { device.findObject(selector).also { found = it } != null }
            return found!!
        }
        fun await(message: String, predicate: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + 15_000
            while (!predicate() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(25)
            assertTrue(message, predicate())
        }
        fun assertQuietCallbackWindow(duration: Long) {
            val until = SystemClock.elapsedRealtime() + duration
            do {
                assertTrue("A stale page resolution opened media after navigation", callbacks.isEmpty())
                assertNoConsentOrCapture()
                SystemClock.sleep(25)
            } while (SystemClock.elapsedRealtime() < until)
        }
        fun assertNoConsentOrCapture() {
            assertEquals("Routing must not launch Android consent", 0, registry.requests.get())
            assertEquals("Routing must not start the playback capture service", 0, context.captureStarts.get())
            assertFalse(WebAudioCaptureService.active.value)
            assertEquals(initialCaptureStatus, WebAudioCaptureService.status.value)
            assertEquals("Microphone permission must remain unchanged", initialPermission,
                context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
        }
    }

    companion object { private const val SOURCE_ACTION = "Use source captions in Video" }
}
