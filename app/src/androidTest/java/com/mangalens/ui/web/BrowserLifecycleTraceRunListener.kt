package com.mangalens.ui.web

import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import java.io.File
import java.util.UUID

/** Explicit -e listener opt-in for two existing real-WebView fixture methods only. */
class BrowserLifecycleTraceRunListener : RunListener() {
    private class Capture(val description: Description, val label: String, val started: Long) {
        val buffer = BrowserLifecycleTraceBuffer()
        lateinit var subscription: AutoCloseable
        var failureObserved = false
        private var renderRows = 0
        private var omittedRenderRows = 0

        @Synchronized fun observe(event: BrowserLifecycleObservation) {
            // Reserve half the bounded buffer for real cleanup/attachment/fixture markers.
            // This is a recorded sampling limit, not deduplication or a fabricated lifecycle cause.
            if (event.phase in RENDER_PHASES) {
                if (renderRows >= 128) {
                    if (omittedRenderRows < Int.MAX_VALUE) omittedRenderRows++
                    return
                }
                renderRows++
            }
            val row = buffer.append(event, SystemClock.uptimeMillis(), Looper.myLooper() == Looper.getMainLooper()) ?: return
            // Scalar logcat survives a MAIN crash. This opt-in logging has diagnostic overhead.
            Log.i(TAG, row.json(started).put("fixture_case", label).toString())
        }

        @Synchronized fun omittedRenderRows(): Int = omittedRenderRows
    }

    private var active: Capture? = null

    override fun testStarted(description: Description) {
        val selected = CASES[description.className + "#" + description.methodName] ?: return
        if (active != null) return
        val capture = Capture(description, selected, SystemClock.uptimeMillis())
        capture.subscription = BrowserLifecycleDiagnostics.install(capture::observe)
        active = capture
    }

    override fun testFailure(failure: Failure) {
        active?.takeIf { it.description == failure.description }?.failureObserved = true
    }

    override fun testAssumptionFailure(failure: Failure) {
        active?.takeIf { it.description == failure.description }?.failureObserved = true
    }

    override fun testFinished(description: Description) {
        if (active?.description == description) finish(false)
    }

    override fun testRunFinished(result: Result) { finish(true) }

    private fun finish(runEndedBeforeTestFinished: Boolean) {
        val capture = active ?: return
        active = null
        BrowserLifecycleDiagnostics.mark(BrowserLifecyclePhase.TEST_FINISHED)
        capture.subscription.close()
        val snapshot = capture.buffer.finish()
        // Final artifact IO happens after the observer is retired, never in a WebView callback.
        runCatching {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = File(context.getExternalFilesDir(null), "qa/browser-lifecycle/${capture.label}")
            check(directory.mkdirs() || directory.isDirectory)
            val rows = JSONArray()
            snapshot.rows.forEach { rows.put(it.json(capture.started)) }
            val result = JSONObject()
                .put("scope", "generated_browser_fixture_scalar_diagnostics")
                .put("source_sha", BuildConfig.SOURCE_SHA).put("channel", BuildConfig.BUILD_CHANNEL)
                .put("api", Build.VERSION.SDK_INT)
                .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
                .put("fixture_case", capture.label)
                .put("junit_failure_or_assumption_observed", capture.failureObserved)
                .put("run_ended_before_test_finished", runEndedBeforeTestFinished)
                .put("test_pass_claim", false)
                .put("compose_measure_observed", false)
                .put("screenshot_boundaries_recorded", false)
                .put("draw_observation", "real WebView super.onDraw callback; not a compositor presentation receipt")
                .put("crash_surviving_channel", TAG)
                .put("logging_overhead", "bounded synchronous scalar logcat only for the selected opt-in fixture")
                .put("maximum_rows", 256).put("maximum_render_rows", 128)
                .put("omitted_render_rows", capture.omittedRenderRows())
                .put("dropped_events", snapshot.dropped).put("events", rows)
            val target = AtomicFile(File(directory, UUID.randomUUID().toString() + ".json"))
            val stream = target.startWrite()
            try { stream.write(result.toString(2).toByteArray(Charsets.UTF_8)); target.finishWrite(stream) }
            catch (failure: Throwable) { target.failWrite(stream); throw failure }
        }.onFailure { Log.w(TAG, "Generated browser scalar trace was not saved") }
    }

    private companion object {
        const val TAG = "MangaLensBrowserLife"
        val CASES = mapOf(
            "com.mangalens.ui.web.BrowserSourceCaptionRouteTest#navigatingWhileActualPageResolutionWaitsCannotDeliverTheOldMedia" to "navigation-resolution",
            "com.mangalens.ui.web.BrowserSourceCaptionRouteTest#sourceCaptionActionPrecedesAudioControlsAndRoutesWithoutModelConsentOrCapture" to "caption-route"
        )
        val RENDER_PHASES = setOf(BrowserLifecyclePhase.VIEW_MEASURE_BEGIN, BrowserLifecyclePhase.VIEW_MEASURE_END,
            BrowserLifecyclePhase.VIEW_MEASURE_ABORT, BrowserLifecyclePhase.VIEW_DRAW_BEGIN,
            BrowserLifecyclePhase.VIEW_DRAW_END, BrowserLifecyclePhase.VIEW_DRAW_ABORT)

        fun BrowserLifecycleTraceRow.json(started: Long): JSONObject = JSONObject()
            .put("sequence", sequence).put("uptime_ms", uptimeMillis)
            .put("elapsed_ms", (uptimeMillis - started).coerceAtLeast(0)).put("main_thread", mainThread)
            .put("phase", event.phase.name).put("view_ordinal", event.viewOrdinal)
            .put("attached", event.attached ?: JSONObject.NULL).put("width", event.width ?: JSONObject.NULL)
            .put("height", event.height ?: JSONObject.NULL).put("width_spec_mode", event.widthSpecMode ?: JSONObject.NULL)
            .put("height_spec_mode", event.heightSpecMode ?: JSONObject.NULL).put("revision", event.revision ?: JSONObject.NULL)
    }
}
