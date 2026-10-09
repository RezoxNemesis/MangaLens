package com.mangalens

import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.download.BundledYtDlpRuntime
import com.mangalens.download.MediaProcessGuard
import com.mangalens.download.MediaResolutionRunner
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Actual native runtime probe. Phase evidence never contains URLs, request data or environment values. */
@RunWith(AndroidJUnit4::class)
class SiteExtractorRuntimeTest {
    @Test fun bundledSiteExtractorExecutesInInstalledAndroidApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val started = SystemClock.elapsedRealtime()
        val phases = mutableListOf<Pair<String, Long>>()
        val worker = AtomicReference<Thread?>()
        var passed = false
        var failureClass: String? = null
        var primaryFailure: Throwable? = null
        fun phase(name: String) {
            synchronized(phases) { phases += name to (SystemClock.elapsedRealtime() - started) }
        }
        try {
            phase("apk_resource_validation")
            val packaged = context.resources.openRawResource(R.raw.ytdlp).use { it.readBytes() }
            assertEquals("APK resource size", BundledYtDlpRuntime.SIZE_BYTES, packaged.size.toLong())
            assertEquals("APK resource checksum", BundledYtDlpRuntime.SHA256, sha256(packaged))
            phase("queued_for_native_runtime")
            val result = runBlocking {
                MediaResolutionRunner.run(20_000L) { session ->
                    worker.set(Thread.currentThread())
                    phase("initializing_bundled_runtime")
                    BundledYtDlpRuntime.initialize(context, session::checkActive)
                    phase("runtime_initialized")
                    val id = "qa-extractor-version-${UUID.randomUUID()}"
                    val request = YoutubeDLRequest("https://example.com/").apply { addOption("--version") }
                    val guard = MediaProcessGuard(session, id, session.remainingMillis(), YoutubeDL::destroyProcessById)
                    try {
                        session.checkActive()
                        phase("native_execute")
                        YoutubeDL.execute(request, processId = id, callback = null).also { phase("native_execute_returned") }
                    } finally { guard.close() }
                }
            }
            assertEquals("Native runtime must execute the APK-pinned version", BundledYtDlpRuntime.VERSION, result.out.trim())
            phase("installed_archive_validation")
            val installed = BundledYtDlpRuntime.installedFile(context)
            assertEquals("Activated private archive size", BundledYtDlpRuntime.SIZE_BYTES, installed.length())
            assertEquals("Activated private archive checksum", BundledYtDlpRuntime.SHA256, sha256(installed.readBytes()))
            phase("verified")
            passed = true
        } catch (failure: Throwable) {
            failureClass = failure.javaClass.name
            primaryFailure = failure
            throw failure
        } finally {
            try {
                val evidence = JSONObject()
                    .put("source_sha", BuildConfig.SOURCE_SHA)
                    .put("build_channel", BuildConfig.BUILD_CHANNEL)
                    .put("apk_version", BuildConfig.VERSION_NAME)
                    .put("native_timeout_ms", 20_000L)
                    .put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                    .put("status", if (passed) "PASS" else "FAIL")
                    .put("expected_runtime_version", BundledYtDlpRuntime.VERSION)
                    .put("failure_class", failureClass ?: JSONObject.NULL)
                val snapshot = synchronized(phases) { phases.toList() }
                evidence.put("last_phase", snapshot.lastOrNull()?.first ?: "not_started")
                evidence.put("phases", JSONArray().apply {
                    snapshot.forEach { (name, elapsed) -> put(JSONObject().put("phase", name).put("elapsed_ms", elapsed)) }
                })
                if (!passed) worker.get()?.let { thread ->
                    evidence.put("worker_state", thread.state.name)
                    evidence.put("worker_stack", JSONArray().apply {
                        // Capture only class/method/line, never request objects, args or local values.
                        thread.stackTrace.take(40).forEach { frame ->
                            put(JSONObject().put("class", frame.className).put("method", frame.methodName).put("line", frame.lineNumber))
                        }
                    })
                }
                val destination = File(context.filesDir, "mangalens-qa/native-startup/outputs.json")
                check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
                val file = AtomicFile(destination)
                val output = file.startWrite()
                try {
                    output.write(evidence.toString(2).toByteArray(Charsets.UTF_8))
                    file.finishWrite(output)
                } catch (failure: Throwable) {
                    file.failWrite(output)
                    throw failure
                }
            } catch (diagnosticFailure: Throwable) {
                // Preserve the native failure when evidence storage also fails.
                if (primaryFailure == null) throw diagnosticFailure
                primaryFailure.addSuppressed(diagnosticFailure)
            }
        }
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
