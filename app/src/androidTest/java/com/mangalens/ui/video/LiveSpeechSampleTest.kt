package com.mangalens.ui.video

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Optional real inference acceptance test. Fixtures stay out of the repository. */
@RunWith(AndroidJUnit4::class)
class LiveSpeechSampleTest {
    @Test fun decodedSampleProducesEnglishSubtitleFile() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val video = args.getString("sample_video")
        val model = args.getString("whisper_model_path")
        assumeTrue("Stage sample_video and whisper_model_path to run real inference", video != null && model != null)
        val context = instrumentation.targetContext
        lateinit var vm: LocalVideoPlayerViewModel
        val store = ViewModelStore()
        instrumentation.runOnMainSync {
            vm = LocalVideoPlayerViewModel(context.applicationContext as Application)
            store.put("sample_speech", vm)
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
        } finally { instrumentation.runOnMainSync { store.clear() } }
    }
}
