package com.mangalens

import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.download.DownloadDatabase
import com.mangalens.download.DownloadEntity
import com.mangalens.download.DownloadQuality
import com.mangalens.download.DownloadRequestContextStore
import com.mangalens.download.DownloadState
import com.mangalens.download.MediaDownloadManager
import com.mangalens.download.MediaLinkResolver
import com.mangalens.download.MediaSourceException
import com.mangalens.download.MediaSourceFailure
import com.mangalens.download.YtDlpSiteMediaExtractor
import com.mangalens.ui.video.isPlayableRefresh
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

/** Real supplied providers. Failed access fails the method; recovery assertions live separately. */
@RunWith(AndroidJUnit4::class)
class ProviderSourceAcceptanceTest {
    @Test fun suppliedYoutube1080NativePlaybackDownloadAndSavedFileReplay() =
        verify(ProviderFixture.YOUTUBE, DownloadQuality.P1080)

    @Test fun suppliedYoutubeBestNativePlaybackDownloadAndSavedFileReplay() =
        verify(ProviderFixture.YOUTUBE, DownloadQuality.BEST)

    @Test fun suppliedInstagramBestNativePlaybackDownloadAndSavedFileReplay() =
        verify(ProviderFixture.INSTAGRAM, DownloadQuality.BEST)

    private fun verify(fixture: ProviderFixture, quality: DownloadQuality) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "${fixture.name.lowercase()}-${quality.name.lowercase()}-media"
        val evidence = ProviderSourceEvidence(context, name, fixture)
        evidence.requireOptIn()
        val scope = "real native source resolution, app playback, complete production download, track/PCM audit and saved-file app replay"
        evidence.finish("RUNNING", scope)
        val expectedSha = InstrumentationRegistry.getArguments().getString("expected_source_sha")
        if (expectedSha != null) assertEquals("Provider APK belongs to another source candidate", expectedSha, BuildConfig.SOURCE_SHA)
        val manager = MediaDownloadManager(context)
        val dao = DownloadDatabase.get(context).downloads()
        var requestId: String? = null
        var completed: DownloadEntity? = null
        var nativeFailure: MediaSourceFailure? = null
        var ui: ProviderUiHarness? = null
        fun <T> attempt(operation: String, block: () -> T): T? = try { block() } catch (failure: Throwable) {
            val safe = evidence.failure(operation, failure)
            if (operation == "native_resolution") nativeFailure = safe
            null
        }
        try {
            val offers = attempt("native_format_inventory") { ProviderNativeFormatsProbe.offeredFormats(context, fixture) }
            offers?.let { rows -> evidence.verified("native_format_inventory", JSONObject()
                .put("advertised_formats", JSONArray().apply { rows.forEach { row -> put(JSONObject()
                    .put("height", row.height).put("width", row.width).put("extension", row.extension)
                    .put("separate_video", row.separateVideo)) } })
                .put("advertised_max_height", rows.maxOfOrNull { it.height } ?: JSONObject.NULL)
                .put("advertised_is_not_download_verified", true).put("public_metadata_probe_no_auth_cookies_supplied", true)) }
            val resolved = attempt("native_resolution") { runBlocking {
                MediaLinkResolver(siteExtractor = YtDlpSiteMediaExtractor(context, allowSeparateStreams = true), timeoutMs = 45_000L)
                    .resolveCancellable(fixture.source, quality)?.takeIf(::isPlayableRefresh)
                    ?: throw IOException("No playable provider stream was resolved")
            } }
            resolved?.let { evidence.verified("native_resolution", JSONObject().put("requested_quality", quality.name)
                .put("selected_height", it.detectedHeight ?: JSONObject.NULL)
                .put("has_separate_audio", it.audioUrl != null).put("expected_duration_us", it.expectedDurationUs ?: JSONObject.NULL)) }

            attempt("online_app_playback") {
                ui = ProviderUiHarness(evidence.directory)
                ui!!.openSource(fixture)
                val state = ui!!.awaitResolved()
                if (state.videoUrl == null) {
                    val known = nativeFailure
                    if (known != null) check(retainsSafeSourceReason(known, state.error)) { "App lost the native source failure reason" }
                    throw MediaSourceException(known ?: MediaSourceFailure.from(IOException("No native player stream")), null)
                }
                val measurements = ui!!.probeActualPlayer()
                ui!!.capture("online-native-tail")
                evidence.verified("online_app_playback", measurements)
            }

            // Independently attempt the real transfer even if the earlier metadata/play request failed.
            attempt("production_download_enqueue") {
                val ownedId = "qa-${fixture.name.lowercase()}-${quality.name.lowercase()}-${UUID.randomUUID()}"
                requestId = runBlocking { manager.enqueue(fixture.source, quality = quality, requestId = ownedId) }
                evidence.verified("production_download_enqueue", JSONObject().put("requested_quality", quality.name))
            }
            requestId?.let { id ->
                completed = attempt("complete_download") {
                    val budget = InstrumentationRegistry.getArguments().getString("provider_transfer_timeout_ms")
                        ?.toLongOrNull()?.coerceIn(30_000L, 1_800_000L) ?: 600_000L
                    val deadline = SystemClock.elapsedRealtime() + budget
                    while (true) {
                        val item = runBlocking { dao.get(id) } ?: throw IOException("Owned provider download record disappeared")
                        if (item.state == DownloadState.FAILED) throw IOException(item.error ?: "Provider transfer failed")
                        if (item.state == DownloadState.CANCELLED) throw IOException("Provider transfer was cancelled before verification")
                        if (item.state == DownloadState.COMPLETED) {
                            check(item.bytesDownloaded > 0) { "Completed provider download contains no bytes" }
                            evidence.verified("complete_download", JSONObject().put("bytes", item.bytesDownloaded)
                                .put("reported_height", item.actualHeight ?: JSONObject.NULL).put("adaptive_cache", item.isAdaptive)
                                .put("state", item.state.name))
                            return@attempt item
                        }
                        if (SystemClock.elapsedRealtime() >= deadline) throw java.net.SocketTimeoutException("Provider transfer verification deadline")
                        SystemClock.sleep(250)
                    }
                    @Suppress("UNREACHABLE_CODE") error("Download verification loop did not terminate")
                }
            }
            completed?.let { item ->
                val destination = item.destination
                if (item.isAdaptive || destination == null) {
                    evidence.unavailable("saved_file_audit_and_import",
                        "The transfer uses adaptive app cache; this saved-file driver does not verify cache-only replay/export. No file import or full sample audit is claimed.")
                } else {
                    val uri = Uri.parse(destination)
                    val saved = runBlocking { DownloadRequestContextStore(context).readMedia(item.id) }
                    val audit = attempt("saved_track_and_pcm_audit") { ProviderMediaAudit.inspect(context, uri, saved?.expectedDurationUs) }
                    audit?.let { measurements ->
                        evidence.verified("saved_track_and_pcm_audit", measurements)
                        attempt("download_quality") {
                            val height = measurements.getInt("video_height")
                            assertEquals("Published bytes differ from the actual saved media", item.bytesDownloaded, measurements.getLong("file_bytes"))
                            if (quality == DownloadQuality.P1080) assertEquals("Supplied YouTube 1080p was not produced", 1080, height)
                            val offeredHeight = offers?.maxOfOrNull { it.height }
                            if (quality == DownloadQuality.BEST && offeredHeight != null && height < offeredHeight) {
                                throw AssertionError("Produced BEST output is below an advertised representation; higher-format accessibility remains unverified")
                            }
                            evidence.verified("download_quality", JSONObject().put("actual_height", height)
                                .put("requested_quality", quality.name).put("advertised_max_height", offeredHeight ?: JSONObject.NULL))
                        }
                        attempt("saved_file_app_import_and_replay") {
                            if (ui == null) ui = ProviderUiHarness(evidence.directory)
                            ui!!.importSavedVideo(uri)
                            val replay = ui!!.probeActualPlayer()
                            ui!!.capture("saved-native-tail")
                            evidence.verified("saved_file_app_import_and_replay", replay.put("source", "published local content URI"))
                        }
                    }
                }
            }
        } catch (failure: Throwable) {
            evidence.failure("probe_driver", failure)
        } finally {
            requestId?.takeIf { completed == null }?.let { id -> runCatching { runBlocking { manager.cancel(id) } } }
            runCatching { ui?.close() }.exceptionOrNull()?.let { evidence.failure("ui_cleanup", it) }
            evidence.finish(if (evidence.failures == 0) "MEDIA_OPERATIONS_VERIFIED" else "FAILED_OR_UNVERIFIED", scope)
        }
        assertTrue("Real provider media acceptance failed or remains unverified; inspect private provider outputs.json. No download/playback PASS is claimed.", evidence.failures == 0)
    }
}
