package com.mangalens.ui.video

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.mangalens.MainActivity
import kotlinx.coroutines.*
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Optional real inference acceptance test. Fixtures stay out of the repository. */
@RunWith(AndroidJUnit4::class)
class LiveSpeechSampleTest {
    @Test fun recognizerCloseIsTerminalWithConcurrentModelReloads() = runBlocking {
        val model = InstrumentationRegistry.getArguments().getString("whisper_model_path")
        assumeTrue("Stage whisper_model_path to check native shutdown", model != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = VideoSpeechEngine(context, scope)
        try {
            engine.importModel(Uri.fromFile(File(model!!)))
            assertTrue(engine.state.value.status, engine.state.value.ready)
            engine.setEnabled(true)
            // Real native loading may already be in flight when shutdown begins.
            val reloads = List(3) { async(Dispatchers.IO) { engine.loadInstalled() } }
            withTimeout(60_000) {
                engine.close()
                reloads.awaitAll()
                engine.close()
                engine.loadInstalled()
            }
            engine.setEnabled(true)
            engine.submitPcm16k(FloatArray(16000), 0)
            assertFalse("Closed recognizer became ready again", engine.state.value.ready)
            assertFalse("Closed recognizer became enabled again", engine.state.value.enabled)
        } finally { engine.close(); scope.cancel() }
    }

    @Test fun decodedSampleProducesEnglishSubtitleFile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val video = args.getString("sample_video")
        val model = args.getString("whisper_model_path")
        assumeTrue("Stage sample_video and whisper_model_path to run real inference", video != null && model != null)
        val context = instrumentation.targetContext
        lateinit var vm: LocalVideoPlayerViewModel
        val store = ViewModelStore()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            vm = LocalVideoPlayerViewModel(context.applicationContext as Application)
            store.put("sample_speech", vm)
            activity.setContent {
                androidx.compose.material3.MaterialTheme { LocalVideoPlayerScreen(vm = vm) }
            }
        }
        try {
            runBlocking { vm.speech.importModel(Uri.fromFile(File(model!!))) }
            assertTrue(vm.speech.state.value.status, vm.speech.state.value.ready)
            instrumentation.runOnMainSync {
                vm.speech.chunkSeconds = 6
                vm.speech.setEnabled(true)
                vm.open(Uri.fromFile(File(video!!)))
            }
            val deadline = System.currentTimeMillis() + 180000
            while (System.currentTimeMillis() < deadline &&
                vm.speech.state.value.cues.none { it.endMs >= 18000 }) Thread.sleep(250)
            val state = vm.speech.state.value
            assertTrue("No audio subtitle cues: ${state.status}", state.cues.isNotEmpty())
            assertTrue("Did not transcribe the latter part of the sample: ${state.cues}", state.cues.any { it.endMs >= 18000 })
            assertTrue(state.cues.all { it.startMs >= 0 && it.endMs >= it.startMs && it.text.isNotBlank() })
            File(context.filesDir, "sample-English.srt").writeText(vm.speech.srt())
            val device = UiDevice.getInstance(instrumentation)
            assertNotNull("Recognized speech was not displayed in the production player",
                device.wait(Until.findObject(By.text(state.latestText)), 10000))
            device.takeScreenshot(File(context.getExternalFilesDir(null), "sample-English-player.png"))
        } finally {
            scenario.close()
            instrumentation.runOnMainSync { store.clear() }
        }
    }
}
