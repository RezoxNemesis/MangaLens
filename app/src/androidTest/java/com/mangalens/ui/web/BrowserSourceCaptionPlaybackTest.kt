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
import com.mangalens.download.ProviderCaptionFormat
import com.mangalens.download.ProviderCaptionInventory
import com.mangalens.download.ProviderCaptionKind
import com.mangalens.download.ProviderCaptionTrack
import com.mangalens.oreznative.OrezNativeEngine
import com.mangalens.ui.video.ProviderCaptionFetcher
import com.mangalens.ui.video.ProviderCaptionUnavailable
import com.mangalens.ui.video.SubtitleGenerationJobs
import com.mangalens.ui.video.SubtitleGenerationStatus
import com.mangalens.ui.video.SubtitleGenerationStore
import com.mangalens.ui.video.SubtitleGenerationTask
import com.mangalens.ui.video.SubtitlePipeline
import com.mangalens.ui.video.SubtitleOutputMode
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
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real HTTPS page/video, default WebView/OkHttp trust, production worker/parser/text translator,
 * durable task and visible browser cues. This makes no ASR, live-site or translation-style claim.
 * The fixture CA is trusted only by the debug localhost domain configuration. No TLS bypass.
 */
@RunWith(AndroidJUnit4::class)
class BrowserSourceCaptionPlaybackTest {
    @Test fun committedSourceCuesFollowRealSeekPauseAndSpeedAndRetireOnSameElementReplacement() = fixture { f ->
        f.startHere()
        val complete = f.completed(f.firstUrl)
        f.assertProviderProof(complete, f.firstUrl)
        f.control("Fixture first cue")
        f.node(By.textContains("Fixture paused at 1100 ms"))
        f.node(By.text(FIRST))
        f.assertCueFor(FIRST, 350)
        f.control("Fixture cue gap")
        f.node(By.textContains("Fixture paused at 2500 ms"))
        f.assertNoCaptionFor(350)
        f.control("Fixture second cue")
        f.node(By.textContains("Fixture paused at 3800 ms, speed 1.5"))
        f.node(By.text(SECOND))
        f.assertCueFor(SECOND, 350)
        assertNull(f.device.findObject(By.text(FIRST)))
        f.control("Fixture play at 1.5")
        f.await("The real HTMLVideoElement clock did not advance at the selected playback rate") {
            f.clock().let { it != null && it.first >= 3800 && it.second == "playing" && it.third == "1.5" }
        }
        f.node(By.text(SECOND))
        f.control("Fixture second cue")
        f.node(By.textContains("Fixture paused at 3800 ms, speed 1.5"))
        f.node(By.text(SECOND))

        // The fixture mutates clip.src and calls load(): it never replaces the video element.
        f.control("Fixture replace video source")
        f.node(By.textContains("Fixture replacement paused at 1100 ms"))
        f.await("Old browser cues survived the same element's accepted media replacement") {
            f.device.findObject(By.text(FIRST)) == null && f.device.findObject(By.text(SECOND)) == null
        }
        f.assertNoCaptionFor(1_000)
        assertEquals(f.firstUrl, f.session.state.value!!.activeTab.url)
        f.assertNoAudioOrModelActivation()
    }

    @Test fun navigationDuringProductionCaptionFetchRetiresExactTaskAndAllowsTheNewPage() = fixture { f ->
        retiredFetch(f, newTab = false)
    }

    @Test fun switchingActualBrowserTabDuringProductionCaptionFetchRetiresTheOldTabTask() = fixture { f ->
        retiredFetch(f, newTab = true)
    }

    @Test fun trustedFixtureCaCannotAuthorizeTheWrongHttpsHostname() = rejectedTls("wrong-host.pem")

    @Test fun aMatchingHostnameCannotAuthorizeAnUntrustedHttpsIssuer() = rejectedTls("untrusted.pem")

    @Test fun defaultCaptionFetcherRejectsBadTlsBeforeAnyDocumentOrTaskCanBePublished() {
        fun inventory(f: Fixture) = ProviderCaptionInventory(f.firstUrl, videoId = null,
            selectedAudioLanguage = "en", originalLanguage = "en",
            expectedDurationMs = 6_000L, tracks = listOf(ProviderCaptionTrack(
                f.firstUrl.substringBeforeLast('/') + "/first.vtt", "en", ProviderCaptionKind.MANUAL,
                ProviderCaptionFormat.VTT)))
        fixture { f ->
            val fetched = runBlocking { withTimeout(25_000) { ProviderCaptionFetcher().fetch(inventory(f), "en") } }
            assertEquals(2, fetched.document.cues.size)
            assertEquals(FIRST, fetched.document.cues.first().text)
            assertTrue("Default production fetcher did not reach the genuine HTTPS caption body", f.captionRequests.get() > 0)
            assertTrue("A transport-only fetch published a subtitle task", f.tasks().isEmpty())
            f.assertNoAudioOrModelActivation()
        }
        listOf("wrong-host.pem", "untrusted.pem").forEach { asset ->
            fixture(asset, expectReady = false) { f ->
                f.node(By.textContains("Secure connection failed."))
                assertThrows(ProviderCaptionUnavailable::class.java) {
                    runBlocking { withTimeout(25_000) { ProviderCaptionFetcher().fetch(inventory(f), "en") } }
                }
                assertEquals("A rejected TLS peer reached caption HTTP", 0, f.captionRequests.get())
                assertTrue("A rejected TLS peer published a subtitle task", f.tasks().isEmpty())
                f.assertNoAudioOrModelActivation()
            }
        }
    }

    private fun retiredFetch(f: Fixture, newTab: Boolean) {
        f.blockNextCaption.set(true)
        f.startHere()
        assertTrue("The actual production caption fetch did not enter the controlled response",
            f.captionEntered.await(15, TimeUnit.SECONDS))
        val captured = f.task(f.firstUrl)
        assertEquals(SubtitleGenerationStatus.RUNNING, captured.status)
        assertTrue(captured.source.source.captionDocumentOnly)
        assertNull(captured.config.modelSha256)
        val oldTab = f.session.state.value!!.activeTabId
        try {
            runBlocking { withTimeout(10_000) {
                f.session.submit { store ->
                    if (newTab) store.newTab(f.otherUrl) else store.navigate(store.snapshot().activeTabId, f.otherUrl)
                }.await()
            } }
            f.node(By.text("Caption fixture other page"))
            f.await("The new source was not the actual durable browser selection") {
                f.session.state.value?.activeTab?.url == f.otherUrl
            }
            if (newTab) assertNotEquals(oldTab, f.session.state.value!!.activeTabId)
            else assertEquals(oldTab, f.session.state.value!!.activeTabId)
        } finally { f.captionRelease.countDown() }
        // The worker may cancel HTTP before the response can be consumed; dispatch still retires.
        assertTrue("The controlled server response did not retire", f.captionReturned.await(10, TimeUnit.SECONDS))
        f.await("The exact old source generation stayed active after browser retirement") {
            f.native.get(captured.id)?.let { it.generation == captured.generation && it.status == SubtitleGenerationStatus.CANCELLED } == true
        }
        f.assertNoCaptionFor(1_000)
        val old = requireNotNull(f.native.get(captured.id))
        assertEquals(captured.generation, old.generation)
        assertNull("A retired delayed response committed a provider receipt", old.providerCaptionReceipt)
        assertTrue("A retired delayed response committed visible translation cues", old.cues.isEmpty())

        // A healthy new-source action is a positive control for the previous negative assertion.
        f.startHere()
        val current = f.completed(f.otherUrl)
        f.assertProviderProof(current, f.otherUrl)
        assertNotEquals(captured.id, current.id)
        assertNotEquals(captured.source.source.sourceResolutionId, current.source.source.sourceResolutionId)
        f.control("Fixture first cue")
        f.node(By.textContains("Fixture paused at 1100 ms"))
        f.node(By.text(OTHER_FIRST))
        f.assertCueFor(OTHER_FIRST, 350)
        assertNull(f.device.findObject(By.text(FIRST)))
        assertNull(f.device.findObject(By.text(SECOND)))
        f.assertNoAudioOrModelActivation()
    }

    private fun rejectedTls(asset: String) = fixture(asset, expectReady = false) { f ->
        f.node(By.textContains("Secure connection failed."))
        assertNull("Rejected HTTPS content became a rendered source document",
            f.device.findObject(By.text("Caption fixture first page")))
        assertTrue("Rejected HTTPS content created a subtitle task", f.tasks().isEmpty())
        assertEquals("Rejected HTTPS reached the production caption HTTP path", 0, f.captionRequests.get())
        assertFalse("An invalid source entered committed browser history",
            f.session.state.value!!.history.any { it.url == f.firstUrl })
        f.assertNoAudioOrModelActivation()
    }

    private fun fixture(tlsAsset: String = "localhost.pem", expectReady: Boolean = true, verify: (Fixture) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        assertFalse("Run routing acceptance without an existing approved capture", WebAudioCaptureService.active.value)
        val video = instrumentation.context.assets.open("original-media/video-h264-720.mp4").use { it.readBytes() }
        val certificate = HeldCertificate.decode(instrumentation.context.assets
            .open("provider-caption-fixture/$tlsAsset").bufferedReader().use { it.readText() })
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val namespace = UUID.randomUUID().toString().replace("-", "")
        val directory = File(app.cacheDir, "browser-source-caption-$namespace").apply { check(mkdirs()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = BrowserWorkspaceSession(AtomicBrowserWorkspaceIo(File(directory, "browser.json")), scope)
        val native = SubtitleGenerationStore.shared(app)
        assertTrue("Subtitle history must have room for two isolated acceptance tasks", native.states.value.size <= 30)
        val requests = AtomicInteger()
        val blockNext = AtomicBoolean()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val returned = CountDownLatch(1)
        val oldIdle = Configurator.getInstance().waitForIdleTimeout
        Configurator.getInstance().setWaitForIdleTimeout(100)
        var instance: Fixture? = null
        try { MockWebServer().use { server ->
            server.useHttps(tls.sslSocketFactory(), false)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val file = request.path?.substringBefore('?')?.substringAfterLast('/')
                    if (file == "first.vtt" || file == "other.vtt") {
                        // Native WebView track loading cannot stand in for the actual production worker.
                        val worker = request.getHeader("User-Agent").orEmpty().contains("MangaLens/13")
                        if (worker) requests.incrementAndGet()
                        val held = worker && file == "first.vtt" && blockNext.compareAndSet(true, false)
                        if (held) {
                            entered.countDown()
                            if (!release.await(20, TimeUnit.SECONDS)) { returned.countDown(); return MockResponse().setResponseCode(408) }
                        }
                        val first = if (file == "first.vtt") FIRST else OTHER_FIRST
                        val second = if (file == "first.vtt") SECOND else OTHER_SECOND
                        val response = MockResponse().setHeader("Content-Type", "text/vtt").setHeader("Cache-Control", "no-store")
                            .setBody("WEBVTT\n\n00:00.500 --> 00:02.000\n$first\n\n00:03.000 --> 00:05.000\n$second\n")
                        if (held) returned.countDown()
                        return response
                    }
                    if (file == "first.mp4" || file == "other.mp4") return videoResponse(request, video, "$namespace-$file")
                    if (file != "first" && file != "other") return MockResponse().setResponseCode(204)
                    return MockResponse().setHeader("Content-Type", "text/html; charset=utf-8")
                        .setHeader("Cache-Control", "no-store").setBody(page(file == "other"))
                }
            }
            server.start()
            val first = server.url("/$namespace/first").toString()
            val other = server.url("/$namespace/other").toString()
            val firstMedia = server.url("/$namespace/first.mp4").toString()
            val otherMedia = server.url("/$namespace/other.mp4").toString()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val registry = NoConsentRegistry()
                lateinit var context: CaptureObservingContext
                val captureStatus = WebAudioCaptureService.status.value
                val permission = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                val modelPath = OrezNativeEngine.sharedModelPath
                val selectedModelTier = app.getSharedPreferences("orez_model", Context.MODE_PRIVATE).getString("selected_tier", null)
                scenario.onActivity { activity ->
                    context = CaptureObservingContext(activity)
                    activity.setContent { CompositionLocalProvider(LocalContext provides context,
                        LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                            override val activityResultRegistry = registry
                        }) { MaterialTheme {
                        BrowserWorkspaceScreen(session, first, translationEnabled = false, adBlockEnabled = true,
                            targetLanguage = "en", onOpenVideo = { _, _ -> throw AssertionError("In-place captions unexpectedly opened Video") })
                    } } }
                }
                val f = Fixture(app, session, native, context, registry, first, other, firstMedia, otherMedia,
                    blockNext, entered, release, returned, requests, captureStatus, permission, modelPath, selectedModelTier)
                instance = f
                try {
                    if (expectReady) {
                        f.node(By.text("Caption fixture first page"))
                        f.await("The HTTPS fixture did not become a committed page with normal trust") {
                            session.state.value?.history?.any { it.url == first && it.title == "Caption fixture first page" } == true
                        }
                    }
                    verify(f)
                } finally { release.countDown(); scenario.onActivity { it.setContent {} } }
            }
        } } finally {
            release.countDown()
            // Only this unique fixture's exact id/generation is touched; user task history is retained.
            try {
                instance?.let { f -> runBlocking { withTimeout(10_000) {
                    for (task in f.tasks()) if (task.status !in setOf(SubtitleGenerationStatus.COMPLETED, SubtitleGenerationStatus.CANCELLED))
                        SubtitleGenerationJobs.cancel(app, task.id, task.generation)
                } } }
            } finally {
                scope.cancel(); directory.deleteRecursively()
                Configurator.getInstance().setWaitForIdleTimeout(oldIdle)
            }
        }
    }

    private class CaptureObservingContext(base: Context) : ContextWrapper(base) {
        val starts = AtomicInteger()
        override fun getApplicationContext(): Context = this
        override fun registerComponentCallbacks(callback: android.content.ComponentCallbacks) =
            baseContext.applicationContext.registerComponentCallbacks(callback)
        override fun unregisterComponentCallbacks(callback: android.content.ComponentCallbacks) =
            baseContext.applicationContext.unregisterComponentCallbacks(callback)
        override fun startService(service: Intent): ComponentName? { record(service); return super.startService(service) }
        override fun startForegroundService(service: Intent): ComponentName? { record(service); return super.startForegroundService(service) }
        private fun record(intent: Intent) {
            if (intent.component?.className == WebAudioCaptureService::class.java.name) starts.incrementAndGet()
        }
    }
    private class NoConsentRegistry : ActivityResultRegistry() {
        val requests = AtomicInteger()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            requests.incrementAndGet()
            throw AssertionError("In-place source captions requested microphone, capture or document consent")
        }
    }
    private class Fixture(val app: Context, val session: BrowserWorkspaceSession, val native: SubtitleGenerationStore,
        val context: CaptureObservingContext, val registry: NoConsentRegistry, val firstUrl: String, val otherUrl: String,
        val firstMediaUrl: String, val otherMediaUrl: String, val blockNextCaption: AtomicBoolean,
        val captionEntered: CountDownLatch, val captionRelease: CountDownLatch, val captionReturned: CountDownLatch,
        val captionRequests: AtomicInteger, val initialCaptureStatus: String, val initialPermission: Int,
        val initialModelPath: String?, val initialSelectedModelTier: String?) {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        fun tasks(): List<SubtitleGenerationTask> = native.states.value.filter {
            it.source.source.providerCaptions?.sourcePageUrl in setOf(firstUrl, otherUrl)
        }
        fun task(page: String): SubtitleGenerationTask {
            var value: SubtitleGenerationTask? = null
            await("No actual shared worker task exists for the accepted browser source") {
                tasks().singleOrNull { it.source.source.providerCaptions?.sourcePageUrl == page }.also { value = it } != null
            }
            return value!!
        }
        fun completed(page: String): SubtitleGenerationTask {
            var value: SubtitleGenerationTask? = null
            await("The production provider-caption worker did not commit a verified complete result", 45_000) {
                tasks().singleOrNull { it.source.source.providerCaptions?.sourcePageUrl == page }?.let {
                    native.exportVerified(it.id, it.generation)
                }.also { value = it } != null
            }
            return value!!
        }
        fun assertProviderProof(task: SubtitleGenerationTask, page: String) {
            assertEquals(SubtitleGenerationStatus.COMPLETED, task.status)
            assertEquals(page, task.source.source.providerCaptions!!.sourcePageUrl)
            // Caption-only jobs bind the accepted document; media/blob URLs remain browser identity.
            assertEquals(page, task.source.source.uri)
            assertTrue(task.source.source.captionDocumentOnly)
            assertTrue(task.source.source.sourceResolutionId!!.matches(Regex("[a-f0-9]{32}")))
            assertNull(task.ownerRequestId)
            assertEquals(SubtitlePipeline.SOURCE_TRANSLATION, task.config.pipeline)
            assertEquals("en", task.config.sourceLanguage); assertEquals("en", task.config.targetLanguage)
            assertEquals(SubtitleOutputMode.TRANSLATED, task.config.outputMode)
            assertNull(task.config.modelSha256); assertFalse(task.config.localRefinement)
            assertFalse(task.audioComplete); assertFalse(task.validationPending); assertFalse(task.pcmValidationRequired)
            assertEquals(2, task.providerCaptionReceipt!!.cueCount)
            assertEquals(task.id, task.providerCaptionReceipt!!.taskId)
            assertEquals(task.generation, task.providerCaptionReceipt!!.generation)
            assertEquals(2, task.windows.sumOf { it.translations.size })
            assertEquals(2, task.cues.size)
            assertTrue(task.windows.all { it.pcmSha256.isEmpty() && it.providerCueSha256 != null })
            assertTrue("Production caption HTTP never ran", captionRequests.get() > 0)
        }
        fun startHere() {
            device.click(device.displayWidth / 2, device.displayHeight / 3)
            revealDock("English CC")
            clickSettledUi(device, By.text("English CC"), 10_000)
            node(By.text("Use source captions here"))
            // Choose through the real UI so a retained user preference cannot change this fixture.
            clickSettledUi(device, By.text("Caption language and style"), 10_000)
            clickSettledUi(device, By.text("English"), 10_000)
            val dual = node(By.desc("Browser source caption dual output"))
            if (dual.isChecked) clickSettledUi(device, By.desc("Browser source caption dual output"), 10_000)
            await("The actual caption output choice did not settle to translated-only") {
                device.findObject(By.desc("Browser source caption dual output"))?.isChecked == false
            }
            clickSettledUi(device, By.text("Hide caption options"), 10_000)
            clickSettledUi(device, By.text("Use source captions here"), 10_000)
        }
        private fun revealDock(label: String) {
            repeat(5) {
                device.findObject(By.text(label))?.takeIf { it.visibleBounds.width() > 0 }?.let { return }
                val anchor = listOf("Translate", "Open in Video", "Download", "Manga", "Site data")
                    .firstNotNullOfOrNull { device.findObject(By.text(it))?.takeIf { node ->
                        node.visibleBounds.width() > 0 && node.visibleBounds.height() > 0
                    } }
                    ?: throw AssertionError("The actual lower browser dock is not visible")
                val y = anchor.visibleBounds.centerY()
                device.swipe(device.displayWidth * 9 / 10, y, device.displayWidth / 10, y, 25)
            }
            assertNotNull("The real lower dock could not reveal $label", device.findObject(By.text(label)))
        }
        fun control(label: String) { node(By.text(label)); clickSettledUi(device, By.text(label), 10_000) }
        fun node(selector: BySelector): UiObject2 {
            var value: UiObject2? = null
            await("Missing actual browser/fixture control: $selector") {
                device.findObject(selector)?.takeIf { it.visibleBounds.width() > 0 && it.visibleBounds.height() > 0 }.also { value = it } != null
            }
            return value!!
        }
        fun clock(): Triple<Long, String, String>? {
            val text = device.findObject(By.textStartsWith("Fixture playing at "))?.text ?: return null
            val match = Regex("Fixture (playing) at (\\d+) ms, speed ([0-9.]+)").matchEntire(text) ?: return null
            return Triple(match.groupValues[2].toLong(), match.groupValues[1], match.groupValues[3])
        }
        fun await(message: String, timeout: Long = 15_000, predicate: () -> Boolean) {
            val until = SystemClock.elapsedRealtime() + timeout
            while (!predicate() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(25)
            assertTrue(message, predicate())
        }
        fun assertCueFor(text: String, duration: Long) = observeFor(duration) {
            val overlay = device.findObject(By.desc("Source captions for current browser video"))
            assertNotNull("The production browser caption overlay is absent", overlay)
            val cue = overlay?.findObject(By.text(text)) ?: overlay?.takeIf { it.text == text }
            assertNotNull("A paused actual video lost its genuine app-overlay cue", cue)
            assertTrue("The source cue is outside its actual caption overlay", overlay!!.visibleBounds.contains(cue!!.visibleBounds))
        }
        fun assertNoCaptionFor(duration: Long) = observeFor(duration) {
            assertNull("A retired/gap source retained its app caption overlay",
                device.findObject(By.desc("Source captions for current browser video")))
            for (cue in listOf(FIRST, SECOND, OTHER_FIRST, OTHER_SECOND))
                assertNull("A retired/gap source kept a visible cue", device.findObject(By.text(cue)))
            assertNoAudioOrModelActivation()
        }
        private fun observeFor(duration: Long, assertion: () -> Unit) {
            val until = SystemClock.elapsedRealtime() + duration
            do { assertion(); SystemClock.sleep(25) } while (SystemClock.elapsedRealtime() < until)
        }
        fun assertNoAudioOrModelActivation() {
            assertEquals(0, registry.requests.get()); assertEquals(0, context.starts.get())
            assertFalse(WebAudioCaptureService.active.value)
            assertEquals(initialCaptureStatus, WebAudioCaptureService.status.value)
            assertEquals(initialPermission, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
            assertEquals(initialModelPath, OrezNativeEngine.sharedModelPath)
            assertEquals(initialSelectedModelTier, app.getSharedPreferences("orez_model", Context.MODE_PRIVATE).getString("selected_tier", null))
        }
    }

    companion object {
        private const val FIRST = "Do not open the door."
        private const val SECOND = "We need to leave now."
        private const val OTHER_FIRST = "The new page is ready."
        private const val OTHER_SECOND = "Stay with the new source."
        private fun videoResponse(request: RecordedRequest, video: ByteArray, etag: String): MockResponse {
            val result = MockResponse().setHeader("Content-Type", "video/mp4").setHeader("ETag", "\"$etag\"")
                .setHeader("Content-Length", video.size).setHeader("Accept-Ranges", "bytes")
            if (request.method == "HEAD") return result
            val range = Regex("bytes=(\\d+)-(\\d*)").matchEntire(request.getHeader("Range").orEmpty())
            val first = range?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val last = range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(video.lastIndex) ?: video.lastIndex
            if (first !in video.indices || last < first) return MockResponse().setResponseCode(416)
            if (range != null) result.setResponseCode(206).setHeader("Content-Range", "bytes $first-$last/${video.size}")
            return result.setBody(Buffer().write(video, first, last - first + 1))
        }
        private fun page(other: Boolean): String {
            val title = "Caption fixture ${if (other) "other" else "first"} page"
            val stem = if (other) "other" else "first"
            return """
                <!doctype html><html lang="en"><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>$title</title></head>
                <body style="padding:84px 10px 200px;font:16px sans-serif"><h1 style="font-size:18px">$title</h1>
                <video id="clip" lang="en" controls muted playsinline preload="auto" width="280" height="158" src="$stem.mp4">
                  <track kind="subtitles" srclang="en" label="Original English" src="$stem.vtt">
                </video><div style="max-width:340px">
                <button onclick="pauseAt(1.1,1)">Fixture first cue</button><button onclick="pauseAt(2.5,1)">Fixture cue gap</button>
                <button onclick="pauseAt(3.8,1.5)">Fixture second cue</button><button onclick="playAt()">Fixture play at 1.5</button>
                <button onclick="replaceSource()">Fixture replace video source</button></div>
                <p id="clock">Fixture waiting for media</p><script>
                const clip=document.getElementById('clip'), status=document.getElementById('clock');
                let replacement=false;
                function report(){status.textContent='Fixture '+(replacement?'replacement ':'')+(clip.paused?'paused':'playing')+' at '+Math.round(clip.currentTime*1000)+' ms, speed '+clip.playbackRate;}
                function pauseAt(time,speed){clip.pause();clip.playbackRate=speed;clip.currentTime=time;}
                function playAt(){clip.pause();clip.currentTime=3.5;clip.playbackRate=1.5;clip.play();}
                function replaceSource(){replacement=true;clip.pause();clip.src='other.mp4';clip.load();clip.addEventListener('loadedmetadata',function(){clip.pause();clip.currentTime=1.1;},{once:true});}
                ['seeked','pause','play','ratechange','loadedmetadata'].forEach(event=>clip.addEventListener(event,report));
                setInterval(report,100);
                </script></body></html>
            """.trimIndent()
        }
    }
}
