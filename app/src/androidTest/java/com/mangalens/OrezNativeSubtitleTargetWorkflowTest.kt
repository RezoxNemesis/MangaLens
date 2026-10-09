package com.mangalens

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mangalens.core.translation.TranslationQualityPolicy
import com.mangalens.orez.OrezRoomDatabase
import com.mangalens.orez.agent.*
import com.mangalens.ui.video.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual selected audio, native ASR, translation and WorkManager; no remote-provider quality claim. */
@RunWith(AndroidJUnit4::class)
class OrezNativeSubtitleTargetWorkflowTest {
    @Test fun selectedPlayableSourceCompletesOwnedHinglishDualWorkflowWithOriginalTiming(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val native = SubtitleGenerationStore.shared(context)
        val file = File(context.cacheDir, "orez-native-subtitle-target-jfk.wav")
        var accepted: OrezTaskPlan? = null
        try {
            instrumentation.context.assets.open("orez-fixtures/jfk.wav").use { input -> file.outputStream().use(input::copyTo) }
            assertEquals(352078L, file.length())
            assertEquals(AUDIO_SHA, SubtitleGenerationStore.fileHash(file))
            val model = File(requireNotNull(InstrumentationRegistry.getArguments().getString("whisper_model_path")) {
                "Stage the pinned multilingual Whisper fixture and pass whisper_model_path."
            })
            assertTrue(model.isFile)
            assertEquals(77_691_713L, model.length())
            assertEquals(MODEL_SHA, SubtitleGenerationStore.fileHash(model))
            if (SubtitleInputs.config(context, "en").modelSha256 != MODEL_SHA) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val importer = VideoSpeechEngine(context, scope)
                try {
                    withTimeout(90_000) { importer.importModel(Uri.fromFile(model)) }
                    assertTrue(importer.state.value.status, importer.state.value.ready)
                } finally {
                    try { withContext(NonCancellable) { importer.close() } }
                    finally { scope.cancel() }
                }
            }
            assertEquals(MODEL_SHA, SubtitleInputs.config(context, "en").modelSha256)
            val selection = OrezMediaSelection(Uri.fromFile(file).toString(), label = "Explicit original speech fixture")
            val plan = OrezAgentRuntime().decide("Generate Hinglish dual subtitles for this selected video",
                OrezAgentContext(selectedMedia = selection, subtitleOptions = OrezSubtitleOptions(sourceLanguage = "en", threads = 2))).plan!!
            accepted = plan
            val captured = plan.authorization!!.subtitle!!
            assertEquals("hi-latn", captured.targetLanguage)
            assertEquals(SubtitleOutputMode.DUAL, captured.outputMode)
            assertEquals(SubtitlePipeline.SOURCE_TRANSLATION, captured.pipeline)
            var journal = OrezTaskStore(OrezRoomDatabase.get(context).tasks())
            assertTrue(journal.checkpoint(plan))
            fun executor() = OrezTaskExecutor(journal, OrezSubtitleTools.forPlan(journal, plan, OrezNativeSubtitleHost(context)))
            var result = executor().run(plan.id, plan.executionEpoch)
            // The orchestration journal is reopened while the actual native worker proceeds independently.
            journal = OrezTaskStore(OrezRoomDatabase.get(context).tasks())
            result = withTimeout(720_000) {
                while (result is OrezTaskExecutor.Result.Pending) {
                    delay(2_000)
                    result = executor().run(plan.id, plan.executionEpoch)
                }
                result
            }
            assertTrue(result.toString(), result is OrezTaskExecutor.Result.Completed)
            val saved = journal.load(plan.id)!!
            assertEquals(OrezTaskStatus.COMPLETED, saved.status)
            assertEquals(captured, saved.authorization!!.subtitle)
            val output = saved.steps.last().outputs
            val task = requireNotNull(native.exportVerified(output.getValue("subtitleTaskId"), output.getValue("generation")))
            assertEquals(OrezDurablePlanRules.requestId(plan.id, 1), task.ownerRequestId)
            assertEquals(MODEL_SHA, task.config.modelSha256)
            assertEquals(task.config.fingerprint(), output["configFingerprint"])
            assertEquals("true", output["audioComplete"])
            assertEquals("0", output["pendingTargetCues"])
            assertEquals("DUAL", output["outputMode"])
            assertEquals("SOURCE_TRANSLATION", output["pipeline"])
            assertTrue(task.audioComplete && !task.validationPending && !task.pcmValidationRequired)
            assertEquals(0, task.pendingTargetCues)
            assertTrue(task.windows.size >= 2 && task.sourceCues.isNotEmpty())
            assertTrue(task.processedMs + 1500 >= task.durationMs && task.sourceCues.last().endMs >= 9_000)
            assertEquals(task.sourceCues.map { it.startMs to it.endMs }, task.cues.map { it.startMs to it.endMs })
            task.windows.forEach { window -> window.translations.forEach { target ->
                assertTrue(TranslationQualityPolicy.isUsable(window.sourceCues[target.sourceIndex].text, target.text, "hi-latn", target.hindiDraft))
                assertFalse(target.text.any { it in '\u0900'..'\u097f' && it.isLetter() })
                assertFalse(target.hindiDraft.isNullOrBlank())
            } }
            val srt = File(task.srtPath!!); val vtt = File(task.vttPath!!)
            assertEquals(SubtitleFormats.srt(task.cues), srt.readText(Charsets.UTF_8))
            assertEquals(SubtitleFormats.vtt(task.cues), vtt.readText(Charsets.UTF_8))
            assertEquals(SubtitleGenerationStore.fileHash(srt), output["srtSha256"])
            assertEquals(SubtitleGenerationStore.fileHash(vtt), output["vttSha256"])
            assertFalse(saved.steps.flatMap { it.outputs.values }.any { it.contains(selection.uri) || it.contains(context.filesDir.path) })
            assertTrue(executor().run(plan.id, plan.executionEpoch) is OrezTaskExecutor.Result.AlreadyFinished)
        } finally {
            withContext(NonCancellable) {
                accepted?.let { plan ->
                    val owner = OrezDurablePlanRules.requestId(plan.id, 1)
                    native.states.value.firstOrNull { it.ownerRequestId == owner }?.let {
                        SubtitleGenerationJobs.cancel(context, it.id, it.generation)
                    }
                }
                file.delete()
            }
        }
    }

    companion object {
        private const val AUDIO_SHA = "59dfb9a4acb36fe2a2affc14bacbee2920ff435cb13cc314a08c13f66ba7860e"
        private const val MODEL_SHA = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"
    }
}
