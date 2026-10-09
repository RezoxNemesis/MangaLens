package com.mangalens

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import com.mangalens.ui.reader.ReaderNavigationDiagnostics
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Only generated mode-qa state and fixed driver labels; no reader text, paths or URLs. */
internal class ReaderModesTrace(private val context: Context) : AutoCloseable {
    private val started = SystemClock.elapsedRealtime()
    private val rows = JSONArray()
    private var dropped = 0
    private val observer = ReaderNavigationDiagnostics.install { event ->
        if (event.chapterId == "mode-qa") record(event.action, mapOf(
            "logical_mode" to event.position.mode, "logical_page" to event.position.page,
            "request" to event.position.request, "restoring" to event.position.restoring,
            "physical_mode" to event.physicalMode, "physical_page" to event.physicalPage,
            "physical_offset" to event.physicalOffset, "geometry_checked" to event.geometryChecked,
            "tools_open" to event.controls, "hud_visible" to event.hudVisible,
            "expected_request" to event.expectedRequest, "target_page" to event.targetPage))
    }

    @Synchronized fun record(action: String, values: Map<String, Any?> = emptyMap()) {
        if (rows.length() >= 256) { dropped++; return }
        rows.put(JSONObject().put("sequence", rows.length() + 1).put("elapsed_ms", SystemClock.elapsedRealtime() - started)
            .put("action", action).put("values", JSONObject(values)))
    }

    override fun close() {
        observer.close()
        val directory = File(context.getExternalFilesDir(null), "qa/core-smoke/reader-modes")
        check(directory.mkdirs() || directory.isDirectory)
        val target = AtomicFile(File(directory, "reader-navigation.json"))
        val result = synchronized(this) { JSONObject().put("scope", "synthetic_reader_mode_qa")
            .put("source_sha", BuildConfig.SOURCE_SHA).put("channel", BuildConfig.BUILD_CHANNEL)
            .put("events", rows).put("dropped_events", dropped).toString(2).toByteArray(Charsets.UTF_8) }
        val output = target.startWrite()
        try { output.write(result); target.finishWrite(output) }
        catch (failure: Throwable) { target.failWrite(output); throw failure }
    }
}
