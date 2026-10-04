package com.mangalens

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.mangalens.ui.web.WebAudioCaptureService
import com.mangalens.ui.video.VideoSpeechEngine
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File

/** Real Android consent + own-UID playback capture; optional supplied sample stays local. */
@RunWith(AndroidJUnit4::class)
class WebPlaybackCaptureTest {
    @Test fun androidConsentedPlaybackCaptureProducesPcmWithoutMicrophone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val path = InstrumentationRegistry.getArguments().getString("sample_video")
        assumeTrue("Stage sample_video to run capture acceptance", path != null && android.os.Build.VERSION.SDK_INT >= 29)
        val app = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        device.executeShellCommand("pm grant com.mangalens android.permission.RECORD_AUDIO")
        val pcm = CountDownLatch(1)
        val model = InstrumentationRegistry.getArguments().getString("whisper_model_path")
        val speechScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val speech = model?.let { VideoSpeechEngine(app, speechScope) }
        if (speech != null) {
            runBlocking { speech.importModel(Uri.fromFile(File(model!!))) }
            assertTrue(speech.state.value.status, speech.state.value.ready)
            speech.setEnabled(true)
        }
        WebAudioCaptureService.windowSeconds = { 6 }
        WebAudioCaptureService.sink = { samples, start ->
            assertTrue(start >= 0)
            if (samples.size == 96000 && samples.any { kotlin.math.abs(it) > .01f }) pcm.countDown()
            speech?.submitPcm16k(samples, start)
        }
        try {
            app.startActivity(Intent().setClassName(app, "com.mangalens.qa.PlaybackCaptureConsentActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // Android 15 offers app-only or entire-screen capture; neither captures screen pixels here.
            val spinner = device.wait(Until.findObject(By.clazz("android.widget.Spinner")), 15000)
            if (spinner != null) {
                spinner.click()
                device.wait(Until.findObject(By.textContains("Entire screen")), 3000)?.click()
            }
            val startButton = device.wait(Until.findObject(By.res("android", "button1")), 10000)
            assertNotNull("Android capture consent was not shown", startButton)
            startButton!!.click()
            val deadline = System.currentTimeMillis() + 20000
            while (!WebAudioCaptureService.active.value && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue(WebAudioCaptureService.status.value, WebAudioCaptureService.active.value)
            app.startActivity(Intent().setClassName(app, "com.mangalens.qa.WebPlaybackFixtureActivity")
                .putExtra("sample_video", path).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val webDeadline = System.currentTimeMillis() + 30000
            while (!com.mangalens.qa.WebPlaybackFixtureActivity.ready && System.currentTimeMillis() < webDeadline) Thread.sleep(100)
            assertTrue("Web fixture did not load", com.mangalens.qa.WebPlaybackFixtureActivity.ready)
            device.click(device.displayWidth / 2, device.displayHeight / 2) // Explicit normal browser playback gesture.
            assertTrue("No actual playback PCM was captured: ${WebAudioCaptureService.status.value}", pcm.await(35, TimeUnit.SECONDS))
            if (speech != null) {
                val deadline = System.currentTimeMillis() + 180000
                while (speech.state.value.cues.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(250)
                assertTrue("Captured Web audio produced no subtitle cues: ${speech.state.value.status}", speech.state.value.cues.isNotEmpty())
                File(app.filesDir, "sample-Web-English.srt").writeText(speech.srt())
            }
        } finally {
            app.stopService(Intent(app, WebAudioCaptureService::class.java))
            WebAudioCaptureService.sink = null
            WebAudioCaptureService.windowSeconds = null
            runBlocking { speech?.close() }
            speechScope.cancel()
        }
    }
}
