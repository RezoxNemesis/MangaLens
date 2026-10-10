package com.mangalens

import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.ui.reader.ReaderHudDiagnostics
import com.mangalens.ui.reader.ReaderHudFixture
import com.mangalens.ui.reader.ReaderHudTraceBuffer
import com.mangalens.ui.reader.ReaderHudTraceRow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import java.io.File
import java.util.UUID

/** Explicit instrumentation -e listener opt-in; existing test files and actions stay unchanged. */
class ReaderHudTraceRunListener : RunListener() {
    private data class FixtureCase(val label: String, val fixture: ReaderHudFixture)
    private data class ActiveTrace(val description: Description, val case: FixtureCase,
        val buffer: ReaderHudTraceBuffer, val subscription: AutoCloseable,
        val startedUptime: Long, var failureObserved: Boolean = false)
    private var active: ActiveTrace? = null

    override fun testStarted(description: Description) {
        val selected = CASES[description.className + "#" + description.methodName] ?: return
        if (active != null) return // Parallel fixture runs are not admitted by this single-reader probe.
        val buffer = ReaderHudTraceBuffer()
        val subscription = ReaderHudDiagnostics.install(setOf(selected.fixture)) { fixture, event ->
            buffer.append(fixture, event, SystemClock.uptimeMillis())
        }
        active = ActiveTrace(description, selected, buffer, subscription, SystemClock.uptimeMillis())
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
        val trace = active ?: return
        active = null
        trace.subscription.close()
        val snapshot = trace.buffer.finish()
        // The observer is retired before final IO; no filesystem work runs in reader callbacks.
        runCatching {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = File(context.getExternalFilesDir(null), "qa/reader-hud-trace/${trace.case.label}")
            check(directory.mkdirs() || directory.isDirectory)
            val rows = JSONArray()
            snapshot.rows.forEach { rows.put(it.json(trace.startedUptime)) }
            val result = JSONObject()
                .put("scope", "generated_reader_fixture_scalar_diagnostics")
                .put("source_sha", BuildConfig.SOURCE_SHA)
                .put("channel", BuildConfig.BUILD_CHANNEL)
                .put("api", Build.VERSION.SDK_INT)
                .put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
                .put("fixture_case", trace.case.label)
                .put("junit_failure_or_assumption_observed", trace.failureObserved)
                .put("run_ended_before_test_finished", runEndedBeforeTestFinished)
                .put("test_pass_claim", false)
                .put("screenshot_boundaries_recorded", false)
                .put("draw_observation", "existing_content_draw_callback; not a compositor presentation receipt")
                .put("timer_delay_ms", 4000)
                .put("events", rows).put("dropped_events", snapshot.dropped)
            val target = AtomicFile(File(directory, UUID.randomUUID().toString() + ".json"))
            val stream = target.startWrite()
            try { stream.write(result.toString(2).toByteArray(Charsets.UTF_8)); target.finishWrite(stream) }
            catch (failure: Throwable) { target.failWrite(stream); throw failure }
        }.onFailure { Log.w("MangaLensHudTrace", "Generated reader scalar trace was not saved") }
    }

    private fun ReaderHudTraceRow.json(startedUptime: Long): JSONObject {
        val state = event.snapshot
        return JSONObject().put("sequence", sequence).put("uptime_ms", uptimeMillis)
            .put("elapsed_ms", (uptimeMillis - startedUptime).coerceAtLeast(0))
            .put("fixture", fixture.name).put("phase", event.phase.name).put("writer", event.writer.name)
            .put("reader_instance", state.readerInstance).put("mode", state.mode.name).put("logical_page", state.logicalPage)
            .put("physical_page", state.physicalPage).put("physical_offset", state.physicalOffset).put("request", state.request)
            .put("restoring", state.restoring).put("geometry_ready", state.geometryReady)
            .put("tools_open", state.toolsOpen).put("hud_visible", state.hudVisible)
            .put("translation_active", state.translationActive)
            .put("scale", state.scale.finite()).put("pan_x", state.panX.finite()).put("pan_y", state.panY.finite())
            .put("previous_hud_visible", event.previousHudVisible ?: JSONObject.NULL)
            .put("previous_tools_open", event.previousToolsOpen ?: JSONObject.NULL)
            .put("timer_sequence", event.timerSequence)
            .put("zoom_delta", event.zoomDelta.finite()).put("pan_delta_x", event.panDeltaX.finite())
            .put("pan_delta_y", event.panDeltaY.finite())
            .put("component", event.component.name).put("animation_current", event.animationCurrent.name)
            .put("animation_target", event.animationTarget.name)
            .put("animation_running", event.animationRunning ?: JSONObject.NULL)
    }

    private fun Float?.finite(): Any = if (this != null && isFinite()) toDouble() else JSONObject.NULL

    private companion object {
        // Exact methods from existing generated fixtures; never serialize a raw Description.
        val CASES = mapOf(
            "com.mangalens.ReaderModesTest#sameChapterSwitchesBetweenVerticalLtrAndRtlWithoutLosingPageCount" to
                FixtureCase("mode-switches", ReaderHudFixture.MODE),
            "com.mangalens.ReaderDocumentPagesTest#twoImportedPagesWithOneOriginalDocumentUriRenderAndNavigateIndependently" to
                FixtureCase("document-pages", ReaderHudFixture.DOCUMENT),
            "com.mangalens.ReaderRestorationSmokeTest#reopenedLetteringRendersWithoutPatchesAndReaderControlsRetainCompletedFiles" to
                FixtureCase("restoration", ReaderHudFixture.RESTORATION),
            "com.mangalens.ReaderRestorationSmokeTest#unreadableCleanedPngFallsBackToSourceWithoutDrawingStoredTextOnSourceGlyphs" to
                FixtureCase("invalid-cleaned-surface", ReaderHudFixture.RESTORATION),
            "com.mangalens.ReaderRestorationSmokeTest#roundedDownsampledSurfaceKeepsMarkedLetteringRectanglesAlignedInVerticalAndPagedModes" to
                FixtureCase("rounded-geometry", ReaderHudFixture.GEOMETRY),
            "com.mangalens.ReaderSourceRecoveryTest#aNonemptyUnreadableSavedOriginalOffersRetryWithoutDeletingTheOtherPage" to
                FixtureCase("source-repair", ReaderHudFixture.SOURCE_REPAIR),
            "com.mangalens.ReaderSourceRecoveryTest#aBoundsReadableRasterFailureOffersRetryAndShowsTheReacquiredPixels" to
                FixtureCase("native-raster-repair", ReaderHudFixture.RASTER_REPAIR),
            "com.mangalens.ReaderSourceRecoveryTest#aRecomputedCrcPngWithBrokenZlibOffersRetryAndShowsTheReacquiredPixels" to
                FixtureCase("png-raster-repair", ReaderHudFixture.RASTER_REPAIR)
        )
    }
}
