package com.mangalens.ui.web

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebViewClient
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import com.mangalens.BuildConfig
import com.mangalens.MainActivity
import com.mangalens.core.web.SafeWebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Opt-in root-cause experiment. Run each exact method alone: a MAIN crash aborts the process.
 * Product pair changes only ComposeView reuse/fresh ownership. Bare pair is a native control,
 * not a substitute browser/route test. No caption, resolver or navigation pass is inferred.
 */
@RunWith(AndroidJUnit4::class)
class BrowserCompositionHostDiagnosticTest {
    @Test fun actualBrowserInReusedMainActivityComposeView() = fixture(Host.REUSED, Content.PRODUCT)
    @Test fun actualBrowserInFreshMainActivityComposeView() = fixture(Host.FRESH, Content.PRODUCT)
    @Test fun bareAndroidViewInReusedMainActivityComposeView() = fixture(Host.REUSED, Content.BARE)
    @Test fun bareAndroidViewInFreshMainActivityComposeView() = fixture(Host.FRESH, Content.BARE)

    private enum class Host { REUSED, FRESH }
    private enum class Content { PRODUCT, BARE }

    private fun fixture(host: Host, content: Content) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        assertFalse("Finish an active user-approved capture before this isolated diagnostic", WebAudioCaptureService.active.value)
        val video = instrumentation.context.assets.open("original-media/video-h264-720.mp4").use { it.readBytes() }
        val idle = Configurator.getInstance().waitForIdleTimeout
        Configurator.getInstance().setWaitForIdleTimeout(100)
        val directory = File(app.cacheDir, "browser-host-diagnostic-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = BrowserWorkspaceSession(AtomicBrowserWorkspaceIo(File(directory, "browser.json")), scope)
        val requests = AtomicInteger()
        val callbacks = AtomicInteger()
        val label = host.name.lowercase() + "-" + content.name.lowercase()
        val buffer = BrowserLifecycleTraceBuffer()
        val started = SystemClock.uptimeMillis()
        val subscription = BrowserLifecycleDiagnostics.install { event ->
            buffer.append(event, SystemClock.uptimeMillis(), Looper.myLooper() == Looper.getMainLooper())?.let { row ->
                Log.i(TAG, row.json(started).put("host", host.name).put("content", content.name).toString())
            }
        }
        var completed = false
        try {
            BrowserLifecycleDiagnostics.mark(BrowserLifecyclePhase.TEST_BEGIN)
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        return when (request.path?.substringBefore('?')) {
                            "/fixture.mp4" -> {
                                val response = MockResponse().setHeader("Content-Type", "video/mp4")
                                    .setHeader("Content-Length", video.size).setHeader("Accept-Ranges", "bytes")
                                if (request.method == "HEAD") response else {
                                    val range = Regex("bytes=(\\d+)-(\\d*)").matchEntire(request.getHeader("Range").orEmpty())
                                    val first = range?.groupValues?.get(1)?.toIntOrNull() ?: 0
                                    val last = range?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(video.lastIndex) ?: video.lastIndex
                                    if (first !in video.indices || last < first) MockResponse().setResponseCode(416)
                                    else {
                                        if (range != null) response.setResponseCode(206).setHeader("Content-Range", "bytes $first-$last/${video.size}")
                                        response.setBody(Buffer().write(video, first, last - first + 1))
                                    }
                                }
                            }
                            "/first" -> {
                                requests.incrementAndGet()
                                MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody("""
                                    <!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><title>Caption route first page</title></head>
                                    <body style="padding:110px 16px;font:22px sans-serif"><h1>Caption route first page</h1>
                                    <video controls preload="metadata" width="280" src="/fixture.mp4"></video></body></html>
                                """.trimIndent())
                            }
                            else -> MockResponse().setResponseCode(204)
                        }
                    }
                }
                server.start()
                val first = server.url("/first").toString()
                BrowserLifecycleDiagnostics.mark(BrowserLifecyclePhase.ACTIVITY_LAUNCH_BEGIN)
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    BrowserLifecycleDiagnostics.mark(BrowserLifecyclePhase.ACTIVITY_LAUNCHED)
                    val registry = NoConsentRegistry()
                    val initialPermission = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    val initialStatus = WebAudioCaptureService.status.value
                    lateinit var context: RoutingContext
                    BrowserLifecycleDiagnostics.mark(BrowserLifecyclePhase.MOUNT_BEGIN)
                    scenario.onActivity { activity ->
                        context = RoutingContext(activity, directory)
                        val mounted: @Composable () -> Unit = {
                            CompositionLocalProvider(LocalContext provides context,
                                LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                                    override val activityResultRegistry = registry
                                }) {
                                MaterialTheme {
                                    if (content == Content.PRODUCT) {
                                        BrowserWorkspaceScreen(session, first, translationEnabled = false, adBlockEnabled = true,
                                            onOpenVideo = { _, _ -> callbacks.incrementAndGet() })
                                    } else {
                                        Box(Modifier.fillMaxSize()) {
                                            AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                                                createBrowserLifecycleWebView(ctx).apply {
                                                    SafeWebView.configure(this)
                                                    webViewClient = WebViewClient()
                                                    loadUrl(first)
                                                }
                                            }, onRelease = { view ->
                                                browserLifecycleViewEvent(BrowserLifecyclePhase.VIEW_RELEASE, view)
                                                view.stopLoading()
                                                view.destroy()
                                            })
                                        }
                                    }
                                }
                            }
                        }
                        // Keep the parent measure contract identical to MainActivity's
                        // original ComposeView; only the host instance changes in FRESH.
                        val originalHost = activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                        check(originalHost is ComposeView) { "MainActivity did not mount its expected ComposeView" }
                        val hostLayoutParams = requireNotNull(originalHost.layoutParams)
                        Log.i(TAG, JSONObject().put("phase", "HOST_LAYOUT_PARAMS")
                            .put("host", host.name).put("content", content.name)
                            .put("width", hostLayoutParams.width).put("height", hostLayoutParams.height).toString())
                        when (host) {
                            Host.REUSED -> activity.setContent(content = mounted)
                            Host.FRESH -> {
                                val fresh = ComposeView(activity)
                                fresh.setContent(mounted)
                                activity.setContentView(fresh, hostLayoutParams)
                            }
                        }
                    }
                    BrowserLifecycleDiagnostics.mark(BrowserLifecyclePhase.MOUNT_END)
                    try {
                        BrowserLifecycleDiagnostics.mark(BrowserLifecyclePhase.INITIAL_DOCUMENT_WAIT_BEGIN)
                        val device = UiDevice.getInstance(instrumentation)
                        await("The real WebView document did not appear") { device.findObject(By.text("Caption route first page")) != null }
                        BrowserLifecycleDiagnostics.mark(BrowserLifecyclePhase.INITIAL_DOCUMENT_WAIT_END)
                        assertTrue("The controlled server did not receive a real page request", requests.get() >= 1)
                        if (content == Content.PRODUCT) {
                            BrowserLifecycleDiagnostics.mark(BrowserLifecyclePhase.HISTORY_WAIT_BEGIN)
                            await("The actual product page did not commit browser history") {
                                session.state.value?.history?.any { it.url == first && it.title == "Caption route first page" } == true
                            }
                            BrowserLifecycleDiagnostics.mark(BrowserLifecyclePhase.HISTORY_WAIT_END)
                        }
                        assertEquals("Diagnostic must not deliver a media callback", 0, callbacks.get())
                        assertEquals("Diagnostic must not launch Android consent", 0, registry.requests.get())
                        assertEquals("Diagnostic must not start playback capture", 0, context.captureStarts.get())
                        assertFalse(WebAudioCaptureService.active.value)
                        assertEquals(initialStatus, WebAudioCaptureService.status.value)
                        assertEquals(initialPermission, context.checkSelfPermission(Manifest.permission.RECORD_AUDIO))
                        completed = true
                    } finally {
                        BrowserLifecycleDiagnostics.mark(BrowserLifecyclePhase.FIXTURE_FINALLY_BEGIN)
                        BrowserLifecycleDiagnostics.mark(BrowserLifecyclePhase.CONTENT_CLEAR_REQUEST_BEGIN)
                        scenario.onActivity {
                            BrowserLifecycleDiagnostics.mark(BrowserLifecyclePhase.CONTENT_CLEAR_MAIN_BEGIN)
                            it.setContent {}
                            BrowserLifecycleDiagnostics.mark(BrowserLifecyclePhase.CONTENT_CLEAR_MAIN_END)
                        }
                        BrowserLifecycleDiagnostics.mark(BrowserLifecyclePhase.CONTENT_CLEAR_REQUEST_END)
                    }
                }
                BrowserLifecycleDiagnostics.mark(BrowserLifecyclePhase.SCENARIO_SCOPE_END)
            }
        } finally {
            BrowserLifecycleDiagnostics.mark(BrowserLifecyclePhase.OUTER_FINALLY_BEGIN)
            scope.cancel()
            directory.deleteRecursively()
            Configurator.getInstance().setWaitForIdleTimeout(idle)
            BrowserLifecycleDiagnostics.mark(BrowserLifecyclePhase.OUTER_FINALLY_END)
            subscription.close()
            val snapshot = buffer.finish()
            runCatching {
                val out = File(app.getExternalFilesDir(null), "qa/browser-host-diagnostic/$label").apply { check(mkdirs() || isDirectory) }
                val rows = JSONArray()
                snapshot.rows.forEach { rows.put(it.json(started)) }
                File(out, UUID.randomUUID().toString() + ".json").writeText(JSONObject()
                    .put("scope", "controlled_browser_composition_host_diagnostic")
                    .put("source_sha", BuildConfig.SOURCE_SHA).put("channel", BuildConfig.BUILD_CHANNEL)
                    .put("host", host.name).put("content", content.name)
                    .put("diagnostic_checks_completed", completed).put("caption_or_retirement_pass_claim", false)
                    .put("dropped_events", snapshot.dropped).put("events", rows).toString(2))
            }.onFailure { Log.w(TAG, "Generated host diagnostic artifact was not saved") }
        }
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15_000
        while (!predicate() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(25)
        assertTrue(message, predicate())
    }

    /** Deliberately retains the existing route fixture's context/model/service behavior. */
    private class RoutingContext(base: Context, private val directory: File) : ContextWrapper(base) {
        val captureStarts = AtomicInteger()
        override fun getApplicationContext(): Context = this
        override fun registerComponentCallbacks(callback: android.content.ComponentCallbacks) =
            baseContext.applicationContext.registerComponentCallbacks(callback)
        override fun unregisterComponentCallbacks(callback: android.content.ComponentCallbacks) =
            baseContext.applicationContext.unregisterComponentCallbacks(callback)
        override fun getFilesDir(): File = directory
        override fun startService(service: Intent): ComponentName? { record(service); return super.startService(service) }
        override fun startForegroundService(service: Intent): ComponentName? { record(service); return super.startForegroundService(service) }
        private fun record(intent: Intent) {
            if (intent.component?.className == WebAudioCaptureService::class.java.name) captureStarts.incrementAndGet()
        }
    }

    private class NoConsentRegistry : ActivityResultRegistry() {
        val requests = AtomicInteger()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) {
            requests.incrementAndGet()
            throw AssertionError("Browser host diagnostic requested Android permission, capture or a document picker")
        }
    }

    private companion object {
        const val TAG = "MangaLensBrowserHost"
        fun BrowserLifecycleTraceRow.json(started: Long): JSONObject = JSONObject()
            .put("sequence", sequence).put("uptime_ms", uptimeMillis)
            .put("elapsed_ms", (uptimeMillis - started).coerceAtLeast(0)).put("main_thread", mainThread)
            .put("phase", event.phase.name).put("view_ordinal", event.viewOrdinal)
            .put("attached", event.attached ?: JSONObject.NULL).put("width", event.width ?: JSONObject.NULL)
            .put("height", event.height ?: JSONObject.NULL).put("revision", event.revision ?: JSONObject.NULL)
    }
}
