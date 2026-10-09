package com.mangalens

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.mangalens.ui.web.WebNavigationDiagnostics
import com.mangalens.ui.web.WebNavigationObservation
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/** Observes the real WebView without replacing its clients, adding a JS bridge, or waiting on it. */
internal class WebNavigationProbe(
    private val context: Context,
    private val trace: WebNavigationFixtureTrace,
    origin: String
) {
    private val fixture = URI(origin)
    private val scope = WebNavigationFixtureScope(origin)
    private val main = Handler(Looper.getMainLooper())
    private val observationsStopped = AtomicBoolean(false)
    private val nextQuery = AtomicLong()
    private val pending = ConcurrentHashMap<String, String>()
    private val rendererProbesEnabled = InstrumentationRegistry.getArguments()
        .getString("web_fixture_renderer_probe", "true") != "false"
    private val logger = Logger.getLogger(MockWebServer::class.java.name)
    private val previousLevel = logger.level
    private val previousBrowserObserver = WebNavigationDiagnostics.observer
    private val browserObserver: (WebNavigationObservation) -> Unit = { event ->
        scope.observationValues(event)?.let { recordValues("browser_${event.action}", it) }
    }
    private val logFormatter = object : Formatter() { override fun format(record: LogRecord): String = formatMessage(record) }
    private val wire = object : java.util.logging.Handler() {
        override fun publish(record: LogRecord?) {
            if (record == null) return
            // Handler also has a nullable formatter property; name it explicitly so
            // diagnostic logging cannot select that inherited null formatter.
            val message = runCatching { this@WebNavigationProbe.logFormatter.format(record) }
                .getOrElse { record.message.orEmpty() }
            if (!message.contains("[${fixture.port}]")) return
            runCatching {
                this@WebNavigationProbe.record("server_wire_log", "level" to record.level.name, "message" to message,
                    "error_class" to record.thrown?.javaClass?.simpleName.orEmpty())
            }
        }
        override fun flush() { }
        override fun close() { }
    }

    init {
        require(fixture.scheme == "http" && fixture.host in setOf("localhost", "127.0.0.1"))
        wire.level = Level.ALL
        logger.addHandler(wire)
        logger.level = Level.FINE // MockWebServer's actual request/response writes are logged at FINE.
        WebNavigationDiagnostics.observer = browserObserver
        record("fixture_started", "origin" to origin, "renderer_probes" to rendererProbesEnabled.toString())
    }

    fun record(kind: String, vararg values: Pair<String, String>) {
        recordValues(kind, values.toMap())
    }

    private fun recordValues(kind: String, values: Map<String, String>) {
        val event = trace.record(kind, values)
        Log.d(TAG, "${event.sequence} +${event.elapsedMs}ms ${event.kind} ${event.values}")
    }

    fun sample(label: String) {
        val id = nextQuery.incrementAndGet()
        pending["snapshot:$id"] = label
        record("snapshot_requested", "label" to label, "query" to id.toString())
        main.post {
            if (observationsStopped.get()) return@post
            runCatching { observeOnMain(label, id) }.onFailure {
                pending.remove("snapshot:$id"); pending.remove("dom:$id"); pending.remove("visual:$id")
                record("snapshot_error", "label" to label, "query" to id.toString(), "error_class" to it.javaClass.simpleName)
            }
        }
    }

    /**
     * Some WebView providers omit their accessible descendants after a network-error reload.
     * Read only the exact controlled document and require its visible heading plus a completed
     * renderer frame. The caller still captures the actual screenshot and uses real UI controls.
     */
    fun awaitRenderedHeading(expectedUrl: String, expectedHeading: String, timeoutMs: Long = 15_000) {
        require(timeoutMs > 0 && scope.accepts(expectedUrl))
        val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
        val last = AtomicReference<JSONObject?>()
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            val ready = AtomicBoolean(false)
            val complete = CountDownLatch(1)
            main.post {
                if (observationsStopped.get()) { complete.countDown(); return@post }
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().firstOrNull()
                val view = activity?.window?.decorView?.let(::findWebView)
                if (view == null || !view.isAttachedToWindow || !view.isShown || view.url != expectedUrl || view.progress != 100) {
                    complete.countDown(); return@post
                }
                view.evaluateJavascript(scope.domSnapshotScript()) { result ->
                    val snapshot = runCatching {
                        (JSONTokener(result.orEmpty()).nextValue() as? String)?.let(::JSONObject)
                    }.getOrNull()
                    last.set(snapshot)
                    if (snapshot == null || !renderedWebFixtureHeading(snapshot, expectedUrl, expectedHeading)) {
                        complete.countDown()
                    } else {
                        view.postVisualStateCallback(nextQuery.incrementAndGet(), object : WebView.VisualStateCallback() {
                            override fun onComplete(requestId: Long) {
                                ready.set(!observationsStopped.get() && view.isAttachedToWindow && view.isShown && view.url == expectedUrl)
                                complete.countDown()
                            }
                        })
                    }
                }
            }
            val remaining = deadline - android.os.SystemClock.uptimeMillis()
            if (remaining <= 0) break
            complete.await(minOf(500L, remaining), TimeUnit.MILLISECONDS)
            if (ready.get() && android.os.SystemClock.uptimeMillis() < deadline) {
                record("rendered_heading_observed", "url" to expectedUrl, "heading" to expectedHeading,
                    "snapshot" to last.get().toString())
                return
            }
            android.os.SystemClock.sleep(minOf(50L, (deadline - android.os.SystemClock.uptimeMillis()).coerceAtLeast(0)))
        }
        throw AssertionError("Expected rendered fixture heading '$expectedHeading' at $expectedUrl within $timeoutMs ms: ${last.get()}")
    }

    private fun observeOnMain(label: String, id: Long) {
        val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
            .filterIsInstance<MainActivity>().firstOrNull()
        val view = activity?.window?.decorView?.let(::findWebView)
        if (view == null) {
            record("webview_missing", "label" to label, "query" to id.toString())
            pending.remove("snapshot:$id")
            return
        }
        val url = view.url.orEmpty()
        pending.remove("snapshot:$id")
        if (!scope.accepts(url)) {
            record("snapshot_skipped_non_fixture", "label" to label, "query" to id.toString())
            return
        }
        val history = view.copyBackForwardList()
        record("webview_snapshot", "label" to label, "query" to id.toString(), "url" to url,
            "progress" to view.progress.toString(), "title" to view.title.orEmpty(),
            "view_identity" to System.identityHashCode(view).toString(),
            "attached" to view.isAttachedToWindow.toString(), "shown" to view.isShown.toString(),
            "size" to "${view.width}x${view.height}", "history_index" to history.currentIndex.toString(),
            "history_urls" to (0 until history.size).map { scope.redacted(history.getItemAtIndex(it).url) }.toString(),
            "important_for_accessibility" to view.importantForAccessibility.toString())
        if (!rendererProbesEnabled) {
            record("renderer_probes_disabled", "label" to label, "query" to id.toString())
            return
        }
        pending["dom:$id"] = label
        record("dom_requested", "label" to label, "query" to id.toString(), "url" to url)
        runCatching {
            view.evaluateJavascript(scope.domSnapshotScript()) { result ->
                if (!observationsStopped.get()) {
                    // Never publish a result from a document reached after our dispatch check.
                    runCatching {
                        val decoded = JSONTokener(result.orEmpty()).nextValue() as? String
                        val snapshot = decoded?.let(::JSONObject)
                        if (snapshot?.optString("skipped") == "non-fixture" ||
                            snapshot == null || !snapshot.has("url") || !scope.accepts(snapshot.optString("url"))) {
                            record("dom_skipped_non_fixture", "label" to label, "query" to id.toString())
                        } else {
                            record("dom_result", "label" to label, "query" to id.toString(), "json" to result.orEmpty())
                        }
                    }.onFailure {
                        record("dom_result_error", "label" to label, "query" to id.toString(), "error_class" to it.javaClass.simpleName)
                    }
                }
                pending.remove("dom:$id")
            }
        }.onFailure {
            record("dom_probe_error", "label" to label, "query" to id.toString(), "error_class" to it.javaClass.simpleName)
            pending.remove("dom:$id")
        }
        pending["visual:$id"] = label
        record("visual_requested", "label" to label, "query" to id.toString(), "url" to url)
        runCatching {
            view.postVisualStateCallback(id, object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (!observationsStopped.get()) record("visual_complete", "label" to label,
                        "query" to requestId.toString(), "url_at_request" to url)
                    pending.remove("visual:$requestId")
                }
            })
        }.onFailure {
            record("visual_probe_error", "label" to label, "query" to id.toString(), "error_class" to it.javaClass.simpleName)
            pending.remove("visual:$id")
        }
    }

    fun stopObservations() {
        observationsStopped.set(true)
        main.removeCallbacksAndMessages(null)
    }

    fun close() {
        stopObservations()
        logger.removeHandler(wire)
        logger.level = previousLevel
        if (WebNavigationDiagnostics.observer === browserObserver) {
            WebNavigationDiagnostics.observer = previousBrowserObserver
        }
        record("probe_closed", "pending_queries" to pending.toSortedMap().toString())
        val events = JSONArray()
        val encodedEvents = trace.snapshot().map { event -> JSONObject().apply {
            put("sequence", event.sequence); put("elapsed_ms", event.elapsedMs); put("kind", event.kind)
            put("values", JSONObject(event.values))
        }.toString() }
        encodedEvents.forEach { events.put(JSONObject(it)) }
        val file = File(context.getExternalFilesDir(null), "qa/core-smoke/web-navigation/navigation-trace.json")
        runCatching {
            file.parentFile!!.mkdirs()
            file.writeText(JSONObject().apply { put("events", events); put("dropped_events", trace.droppedCount()) }.toString(2))
        }.onFailure { Log.e(TAG, "Could not write controlled fixture trace", it) }
        runCatching {
            for (line in webNavigationTraceStdout(encodedEvents, trace.droppedCount())) {
                InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                    putString("stream", "$line\n")
                })
            }
        }.onFailure { Log.e(TAG, "Could not stream controlled fixture trace", it) }
    }

    private fun findWebView(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findWebView(view.getChildAt(i))?.let { return it }
        return null
    }

    companion object {
        private const val TAG = "WebNavFixture"
    }
}
