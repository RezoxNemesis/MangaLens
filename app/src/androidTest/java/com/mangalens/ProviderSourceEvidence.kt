package com.mangalens

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.download.BundledYtDlpRuntime
import com.mangalens.download.MediaSourceFailure
import com.mangalens.download.MediaSourceFailureKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.CancellationException
import java.util.UUID

internal enum class ProviderFixture(val sourceId: String, val source: String) {
    YOUTUBE("RzasqVwpLOA", "https://youtu.be/RzasqVwpLOA?si=vn3VhAdIZKeUXgZE"),
    INSTAGRAM("DeODl9XI0ex", "https://www.instagram.com/reel/DeODl9XI0ex/?dlrf=MTljZnpjY21lbGh2Mw==")
}

internal fun retainsSafeSourceReason(expected: MediaSourceFailure, message: String?): Boolean =
    message == expected.message || (expected.kind == MediaSourceFailureKind.TIMEOUT &&
        message == "Video detection timed out. Retry or open the source page in Web.")

/** Explicit source identity and fixed failure text; never exports media URLs/headers/native output. */
internal class ProviderSourceEvidence(context: Context, val name: String, fixture: ProviderFixture) {
    val directory = File(context.filesDir, "mangalens-qa/providers/$name/${System.currentTimeMillis()}-${UUID.randomUUID()}")
    private val destination = File(directory, "outputs.json")
    private val started = SystemClock.elapsedRealtime()
    private val operations = JSONArray()
    private val document = JSONObject()
        .put("test", name).put("provider", fixture.name).put("source_id", fixture.sourceId)
        .put("source_sha", BuildConfig.SOURCE_SHA).put("apk_version", BuildConfig.VERSION_NAME)
        .put("build_channel", BuildConfig.BUILD_CHANNEL)
        .put("api", android.os.Build.VERSION.SDK_INT)
        .put("abi", android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
        .put("pinned_extractor_version", BundledYtDlpRuntime.VERSION)
        .put("status", "NOT_RUN").put("operations", operations)
        .put("subtitle_quality", "NOT_EVALUATED_BY_THIS_MEDIA_PROBE")
    var failures = 0
        private set

    fun requireOptIn() {
        val enabled = InstrumentationRegistry.getArguments().getString("provider_acceptance") == "true"
        if (!enabled) finish("NOT_RUN", "Requires explicit provider_acceptance=true")
        assumeTrue("Real provider probes require provider_acceptance=true; skipped is not acceptance", enabled)
    }

    fun verified(operation: String, measurements: JSONObject = JSONObject()) {
        operations.put(JSONObject().put("operation", operation).put("status", "VERIFIED")
            .put("measurements", measurements).put("elapsed_ms", SystemClock.elapsedRealtime() - started))
        save()
    }

    fun unavailable(operation: String, reason: String) {
        failures++
        operations.put(JSONObject().put("operation", operation).put("status", "NOT_VERIFIED")
            .put("reason", reason).put("elapsed_ms", SystemClock.elapsedRealtime() - started))
        save()
    }

    fun failure(operation: String, failure: Throwable): MediaSourceFailure? {
        failures++
        val safe = try { MediaSourceFailure.from(failure) } catch (_: CancellationException) { null }
            catch (_: InterruptedException) { null }
        val status = when (safe?.kind) {
            MediaSourceFailureKind.PROVIDER_VERIFICATION_REQUIRED,
            MediaSourceFailureKind.PROVIDER_AUDIENCE_RESTRICTED,
            MediaSourceFailureKind.PROVIDER_LOGIN_REQUIRED,
            MediaSourceFailureKind.PROVIDER_UNAVAILABLE -> "BLOCKED_PROVIDER"
            MediaSourceFailureKind.NETWORK_PROXY_BLOCKED -> "BLOCKED_NETWORK_PROXY"
            MediaSourceFailureKind.NETWORK_TLS_FAILURE -> "FAIL_NETWORK_TLS"
            MediaSourceFailureKind.NETWORK_CONNECTION_FAILED -> "FAIL_NETWORK_CONNECTION"
            MediaSourceFailureKind.TIMEOUT -> "FAIL_TIMEOUT"
            MediaSourceFailureKind.SOURCE_ACCESS_DENIED -> "FAIL_SOURCE_ACCESS_UNDETERMINED"
            null -> "REQUEST_CANCELLED"
            else -> "FAIL_APP_OR_SOURCE_UNDETERMINED"
        }
        val trace = ProviderFailureDiagnostics.capture(failure)
        val locations = JSONArray().apply { trace.forEach { node ->
            put(JSONObject().put("type", node.type).put("relation", node.relation)
                .put("parent", node.parent ?: JSONObject.NULL).put("frames", JSONArray().apply {
                    node.frames.forEach { frame ->
                        put(JSONObject().put("class", frame.owner).put("method", frame.method)
                            .put("line", frame.line ?: JSONObject.NULL))
                    }
                }))
        } }
        operations.put(JSONObject().put("operation", operation).put("status", status)
            .put("failure_kind", safe?.kind?.name ?: "REQUEST_CANCELLED")
            .put("user_message", safe?.message ?: "Request cancelled before acceptance could be verified.")
            .put("failure_class", trace.firstOrNull()?.type ?: "other.Throwable")
            .put("failure_trace", locations)
            .put("elapsed_ms", SystemClock.elapsedRealtime() - started))
        save()
        return safe
    }

    fun finish(status: String, scope: String) {
        document.put("status", status).put("scope", scope)
            .put("failed_operations", failures).put("elapsed_ms", SystemClock.elapsedRealtime() - started)
        save()
    }

    private fun save() {
        check(destination.parentFile!!.isDirectory || destination.parentFile!!.mkdirs())
        val file = AtomicFile(destination)
        val stream = file.startWrite()
        try {
            stream.write(document.toString(2).toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (failure: Throwable) { file.failWrite(stream); throw failure }
    }
}
