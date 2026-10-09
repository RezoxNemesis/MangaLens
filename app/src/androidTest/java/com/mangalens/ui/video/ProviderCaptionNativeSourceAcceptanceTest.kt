package com.mangalens.ui.video

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModelProvider
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.mangalens.MainActivity
import com.mangalens.download.*
import com.mangalens.orez.agent.*
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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
import java.util.concurrent.TimeUnit

/** Real HTTPS captions, parser, translator, AtomicFile, native UI and source fence; fixture speech is not ASR. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class ProviderCaptionNativeSourceAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    @Test fun fetchedProviderTrackRendersOnItsAcceptedVideoAndRejectsAnOlderSameUrlResolution() = runBlocking {
        val video = instrumentation.context.assets.open("original-media/video-h264-720.mp4").use { it.readBytes() }
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTrust = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTrust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .sslSocketFactory(clientTrust.sslSocketFactory(), clientTrust.trustManager).build()
        val captions = "WEBVTT\n\n00:00.500 --> 00:02.000\nDo not open the door.\n\n00:03.000 --> 00:05.000\nWe need to leave now.\n"
        MockWebServer().use { media -> MockWebServer().use { text ->
            media.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val response = MockResponse().setHeader("Content-Type", "video/mp4").setHeader("ETag", "\"caption-video-v1\"")
                        .setHeader("Content-Length", video.size).setHeader("Accept-Ranges", "bytes")
                    if (request.method == "HEAD") return response
                    val match = Regex("bytes=(\\d+)-(\\d*)").matchEntire(request.getHeader("Range").orEmpty())
                    val first = match?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val last = match?.groupValues?.get(2)?.toIntOrNull()?.coerceAtMost(video.lastIndex) ?: video.lastIndex
                    if (first !in video.indices || last < first) return MockResponse().setResponseCode(416)
                    if (match != null) response.setResponseCode(206).setHeader("Content-Range", "bytes $first-$last/${video.size}")
                    return response.setBody(Buffer().write(video, first, last - first + 1))
                }
            }
            text.useHttps(serverTrust.sslSocketFactory(), false)
            text.enqueue(MockResponse().setHeader("Content-Type", "text/vtt").setBody(captions))
            media.start(); text.start()
            val sourceUrl = media.url("/video.mp4").toString()
            val track = ProviderCaptionTrack(text.url("/original.vtt").toString(), "en", ProviderCaptionKind.MANUAL, ProviderCaptionFormat.VTT)
            val inventory = ProviderCaptionInventory(text.url("/watch").toString(), "fixture-video", "en", "en", 8000, listOf(track))
            val accepted = mutableStateOf(UUID.randomUUID().toString().replace("-", ""))
            val initialResolution = accepted.value
            val source = SubtitleMediaSource(sourceUrl, cacheKey = inventory.sourcePageUrl, providerCaptions = inventory, sourceResolutionId = initialResolution)
            val native = SubtitleGenerationStore.shared(context)
            val task = native.start(SubtitleInputs.capture(context, source), SubtitleGenerationConfig(sourceLanguage = "en")
                .withTarget(SubtitleTargetOptions().capture()), force = true)
            val translator = SubtitleCueTranslator(context)
            try {
                assertTrue(ProviderCaptionProcessor(native, task, checkOwner = { check(native.current(task.id, task.generation)) },
                    fetch = { captured, language, saved -> ProviderCaptionFetcher(client, { null }).fetch(captured, language, saved) },
                    translate = translator::translate).process())
            } finally { translator.close() }
            val complete = requireNotNull(native.exportVerified(task.id, task.generation))
            assertFalse(complete.audioComplete); assertNull(complete.config.modelSha256)
            assertEquals(5000L, complete.processedMs); assertEquals(8000L, complete.durationMs)
            val request = requireNotNull(text.takeRequest(5, TimeUnit.SECONDS))
            assertEquals("/original.vtt", request.path); assertNull(request.getHeader("Authorization"))
            assertEquals(SubtitleFormats.srt(complete.cues), File(requireNotNull(complete.srtPath)).readText())
            assertEquals(SubtitleFormats.vtt(complete.cues), File(requireNotNull(complete.vttPath)).readText())
            val descriptor = OrezMediaSelection(sourceUrl, source.cacheKey, source.label, resolutionId = initialResolution,
                expectedDurationUs = 8_000_000, providerCaptions = inventory)
            OrezSubtitleTools.verifyCompleted(OrezSubtitleNativeEvidence.receipt(complete, descriptor.sourceId,
                File(context.filesDir, "subtitle_jobs"), descriptor))
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { MaterialTheme {
                    NativeVideoPlayer(url = sourceUrl, onBack = {}, translationEnabled = true, sourcePageUrl = source.cacheKey,
                        resolutionId = accepted.value, providerCaptions = inventory)
                } } }
                lateinit var vm: LocalVideoPlayerViewModel
                scenario.onActivity { vm = ViewModelProvider(it)[LocalVideoPlayerViewModel::class.java] }
                await { onMainValue { vm.player.playbackState == Player.STATE_READY && vm.player.duration >= 7500 } }
                val firstRevision = onMainValue { vm.sourceRevision }
                onMain {
                    vm.player.pause(); vm.player.seekTo(1100); vm.player.setPlaybackSpeed(1.5f)
                    assertTrue("Verified provider track did not attach to its actual native player", vm.selectGeneratedCaption(complete))
                    assertEquals("Do not open the door.", vm.speech.state.value.cues.first().text)
                    assertFalse(vm.player.playWhenReady); assertEquals(1.5f, vm.player.playbackParameters.speed)
                }
                assertTrue("The genuine provider cue did not paint on the actual native player",
                    UiDevice.getInstance(instrumentation).wait(Until.hasObject(By.text("Do not open the door.")), 15_000))
                onMain { accepted.value = UUID.randomUUID().toString().replace("-", "") }
                await { onMainValue { vm.sourceRevision > firstRevision } }
                onMain {
                    assertFalse("An old caption receipt attached after the same URL acquired a different accepted video resolution", vm.selectGeneratedCaption(complete))
                    assertTrue(vm.speech.state.value.cues.isEmpty())
                }
            }
        } }
    }
    private fun await(condition: () -> Boolean) {
        val until = android.os.SystemClock.elapsedRealtime() + 15_000
        while (!condition()) { if (android.os.SystemClock.elapsedRealtime() >= until) fail("Accepted native caption source did not settle in its bounded wait")
            Thread.sleep(25) }
    }
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun <T> onMainValue(block: () -> T): T {
        var result: T? = null; onMain { result = block() }; @Suppress("UNCHECKED_CAST") return result as T
    }
}
