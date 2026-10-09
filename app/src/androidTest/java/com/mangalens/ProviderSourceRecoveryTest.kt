package com.mangalens

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.mangalens.core.model.ContentType
import com.mangalens.download.MediaLinkResolver
import com.mangalens.download.MediaSourceFailure
import com.mangalens.download.YtDlpSiteMediaExtractor
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

/** A passing recovery test verifies controls, not provider media/download availability. */
@RunWith(AndroidJUnit4::class)
class ProviderSourceRecoveryTest {
    @Test fun youtubeResolvingCancelBackAndOpenSourceUseActualUi() = resolvingControls(ProviderFixture.YOUTUBE)
    @Test fun instagramResolvingCancelBackAndOpenSourceUseActualUi() = resolvingControls(ProviderFixture.INSTAGRAM)
    @Test fun youtubeActualSourceFailureRetainsReasonAndCanRetryAfterWebReturn() = actualFailureControls(ProviderFixture.YOUTUBE)
    @Test fun instagramActualSourceFailureRetainsReasonAndCanRetryAfterWebReturn() = actualFailureControls(ProviderFixture.INSTAGRAM)

    private fun resolvingControls(fixture: ProviderFixture) {
        val name = "${fixture.name.lowercase()}-resolving-recovery"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val evidence = ProviderSourceEvidence(context, name, fixture)
        evidence.requireOptIn()
        val scope = "actual supplied-source resolving Cancel, Back and Open source controls only; media access is not accepted"
        evidence.finish("RUNNING", scope)
        val ui = ProviderUiHarness(evidence.directory)
        try {
            ui.verifyHomeOracleRejectsLibrary()
            evidence.verified("home_route_oracle", JSONObject().put("library_with_home_icon_rejected", true))
            ui.openSource(fixture)
            ui.node(By.text("Cancel detection").pkg(context.packageName))
            ui.node(By.text("Back").pkg(context.packageName))
            ui.node(By.text("Open source page").pkg(context.packageName))
            val cancelStarted = SystemClock.elapsedRealtime()
            ui.tap("Cancel detection")
            ui.waitFor("Cancel did not stop the current ingestion state", 2_000) { !ui.state().loading }
            val cancelMs = SystemClock.elapsedRealtime() - cancelStarted
            assertTrue("Cancel waited for the native process instead of returning", cancelMs < 2_000)
            SystemClock.sleep(500)
            assertTrue("A cancelled source request later published playback", ui.state().videoUrl == null)
            ui.node(By.text("Retry detection").pkg(context.packageName))
            ui.capture("cancelled-source")
            evidence.verified("resolving_cancel", JSONObject().put("ui_return_ms", cancelMs)
                .put("late_playback_not_published", true).put("late_result_observation_ms", 500))
            ui.tap("Back")
            ui.assertBackReturnedHome()

            ui.openSource(fixture)
            ui.node(By.text("Cancel detection").pkg(context.packageName))
            ui.tap("Back")
            ui.assertBackReturnedHome()
            assertTrue("Back left the source request loading", !ui.state().loading)
            SystemClock.sleep(500)
            ui.assertBackReturnedHome()
            evidence.verified("resolving_back", JSONObject().put("request_stopped", true).put("home_retained", true))

            ui.openSource(fixture)
            ui.node(By.text("Cancel detection").pkg(context.packageName))
            ui.tap("Open source page")
            ui.assertSourcePageOpened(fixture)
            ui.capture("original-source-web-route")
            evidence.verified("resolving_open_source", JSONObject().put("original_source_selected", true)
                .put("ingestion_stopped", true).put("provider_document_load_not_claimed", true))
        } catch (failure: Throwable) {
            evidence.failure("resolving_recovery", failure)
        } finally {
            runCatching { ui.close() }.exceptionOrNull()?.let { evidence.failure("ui_cleanup", it) }
            evidence.finish(if (evidence.failures == 0) "RECOVERY_CONTROLS_VERIFIED" else "RECOVERY_FAILED", scope)
        }
        assertTrue("Real resolving controls failed; this result does not accept provider playback/download", evidence.failures == 0)
    }

    private fun actualFailureControls(fixture: ProviderFixture) {
        val name = "${fixture.name.lowercase()}-source-error-recovery"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val evidence = ProviderSourceEvidence(context, name, fixture)
        evidence.requireOptIn()
        val scope = "actual source failure category and error Open source / Web return / Retry / Cancel / Back; no media acceptance"
        evidence.finish("RUNNING", scope)
        val nativeFailure = try {
            runBlocking {
                val resolved = MediaLinkResolver(siteExtractor = YtDlpSiteMediaExtractor(context, allowSeparateStreams = true), timeoutMs = 45_000L)
                    .resolveCancellable(fixture.source)
                if (resolved == null || !com.mangalens.ui.video.isPlayableRefresh(resolved)) throw IOException("No actual playable source was resolved")
            }
            null
        } catch (failure: Throwable) { MediaSourceFailure.from(failure) }
        if (nativeFailure == null) evidence.finish("NOT_APPLICABLE_SOURCE_RESOLVED", scope)
        assumeTrue("The real source did not produce a native failure; no injected error substitutes for this check", nativeFailure != null)
        evidence.verified("actual_native_failure_category", JSONObject().put("kind", nativeFailure!!.kind.name)
            .put("safe_user_message", nativeFailure.message).put("provider_media_access_verified", false))
        val ui = ProviderUiHarness(evidence.directory)
        try {
            ui.openSource(fixture)
            val failed = ui.awaitResolved()
            assertTrue("Source unexpectedly resolved; the error route was not exercised", failed.videoUrl == null)
            assertTrue("Native failure reason was lost in rendered fallback", retainsSafeSourceReason(nativeFailure, failed.error))
            ui.node(By.text("Retry detection").pkg(context.packageName))
            ui.node(By.text("Back").pkg(context.packageName))
            ui.node(By.text("Open source page").pkg(context.packageName))
            ui.capture("actual-source-error")
            evidence.verified("actionable_source_error", JSONObject().put("failure_kind", nativeFailure.kind.name)
                .put("reason_retained", true).put("retry_back_open_source_visible", true))

            ui.tap("Open source page")
            ui.assertSourcePageOpened(fixture)
            // Real browser Back may first traverse provider redirects/history. Never force a mock error.
            var returned = false
            repeat(4) {
                if (!returned) {
                    ui.device.pressBack()
                    returned = ui.device.wait(Until.hasObject(By.text("Retry detection").pkg(context.packageName)), 2_000)
                }
            }
            assertTrue("Web Back did not return to the original video error route", returned)
            ui.tap("Retry detection")
            ui.node(By.text("Cancel detection").pkg(context.packageName))
            val retry = ui.state()
            assertEquals("Retry used the browser mode instead of native video", ContentType.VIDEO_STREAM, retry.mode)
            assertEquals("Retry resolved a browser redirect instead of the captured original source", fixture.source, retry.url)
            assertEquals("Retry lost the original source page", fixture.source, retry.videoPageUrl)
            assertTrue("Retry never started native detection", retry.loading)
            evidence.verified("web_return_retry", JSONObject().put("source_identity_retained", true)
                .put("video_mode_restored", true).put("new_detection_started", true))
            ui.tap("Cancel detection")
            ui.waitFor("Retry cancellation did not leave loading") { !ui.state().loading }
            ui.tap("Back")
            ui.assertBackReturnedHome()
            evidence.verified("error_retry_cancel_back", JSONObject().put("home_retained", true))
        } catch (failure: Throwable) {
            evidence.failure("actual_failure_recovery", failure)
        } finally {
            runCatching { ui.close() }.exceptionOrNull()?.let { evidence.failure("ui_cleanup", it) }
            evidence.finish(if (evidence.failures == 0) "ERROR_RECOVERY_VERIFIED_SOURCE_ACCESS_FAILED" else "RECOVERY_FAILED", scope)
        }
        assertTrue("Actual source error recovery failed; a passing recovery check still means media access failed", evidence.failures == 0)
    }
}
