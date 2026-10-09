package com.mangalens

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.mangalens.orez.OrezRoomDatabase
import com.mangalens.orez.agent.*
import com.mangalens.orez.agent.OrezSubtitleTools.Companion.outputs
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
import java.util.concurrent.TimeUnit

/** Actual Room, AtomicFile, playable PCM and WorkManager; recognition quality has a separate reference test. */
@RunWith(AndroidJUnit4::class)
class OrezNativeSubtitleWorkflowTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val native get() = SubtitleGenerationStore.shared(context)
    private fun journal() = OrezTaskStore(OrezRoomDatabase.get(context).tasks())
    private val options = OrezSubtitleOptions(sourceLanguage = "en", threads = 2)
    private fun fixture(): Pair<File, OrezMediaSelection> {
        // A stable private fixture path bounds test history to one source/config slot.
        val file = File(context.cacheDir, "orez-native-subtitle-jfk.wav")
        instrumentation.context.assets.open("orez-fixtures/jfk.wav").use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        assertEquals(352078L, file.length())
        assertEquals(AUDIO_SHA, SubtitleGenerationStore.fileHash(file))
        return file to OrezMediaSelection(Uri.fromFile(file).toString(), label = "Orez explicit speech fixture")
    }
    private fun proposed(selection: OrezMediaSelection) = OrezAgentRuntime().decide("Generate English subtitles for this selected video",
        OrezAgentContext(selectedMedia = selection, subtitleOptions = options)).plan!!
    private suspend fun cancelNative(plan: OrezTaskPlan) {
        val owner = OrezDurablePlanRules.requestId(plan.id, 1)
        native.states.value.firstOrNull { it.ownerRequestId == owner }?.let {
            SubtitleGenerationJobs.cancel(context, it.id, it.generation)
        }
    }

    private suspend fun ensurePinnedWhisper(): String {
        // This class must run on a fresh install without relying on another test's model import.
        val path = requireNotNull(InstrumentationRegistry.getArguments().getString("whisper_model_path")) {
            "Stage the pinned multilingual Whisper fixture and pass whisper_model_path."
        }
        val fixture = File(path)
        assertTrue("Pinned Whisper fixture is missing: $path", fixture.isFile)
        assertEquals("Whisper fixture size differs from the pinned reference", MODEL_BYTES, fixture.length())
        assertEquals("Whisper fixture checksum differs from the pinned reference", MODEL_SHA,
            SubtitleGenerationStore.fileHash(fixture))
        if (SubtitleInputs.config(context, options.sourceLanguage).modelSha256 != MODEL_SHA) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val engine = VideoSpeechEngine(context, scope)
            try {
                withTimeout(90_000) { engine.importModel(Uri.fromFile(fixture)) }
                assertTrue(engine.state.value.status, engine.state.value.ready)
            } finally {
                // Release the setup handle before the independent native WorkManager generator loads it.
                try { withContext(NonCancellable) { engine.close() } }
                finally { scope.cancel() }
            }
        }
        assertEquals("Installed worker model differs from the verified fixture", MODEL_SHA,
            SubtitleInputs.config(context, options.sourceLanguage).modelSha256)
        return MODEL_SHA
    }

    @Test fun capturedPlayableSourceRunsNativeWorkerAndReopensWithRealVerifiedSrtAndVtt() = runBlocking {
        val (file, selection) = fixture()
        val plan = proposed(selection)
        try {
            val capturedModel = ensurePinnedWhisper()
            var store = journal()
            assertTrue(store.checkpoint(plan))
            fun executor() = OrezTaskExecutor(store, OrezSubtitleTools.forPlan(store, plan, OrezNativeSubtitleHost(context)))
            var result = executor().run(plan.id, plan.executionEpoch)
            // Recreate orchestration from the persisted Room row while native WorkManager runs independently.
            store = journal()
            result = withTimeout(300_000) {
                while (result is OrezTaskExecutor.Result.Pending) {
                    delay(2_000)
                    result = executor().run(plan.id, plan.executionEpoch)
                }
                result
            }
            assertTrue(result.toString(), result is OrezTaskExecutor.Result.Completed)
            val saved = journal().load(plan.id)!!
            assertEquals(OrezTaskStatus.COMPLETED, saved.status)
            assertEquals(listOf(OrezOutputKind.MEDIA_SOURCE, OrezOutputKind.SUBTITLE_TRACK), saved.steps.map { it.outputKind })
            val output = saved.steps[1].outputs
            assertEquals(capturedModel, output["speechModelSha256"])
            val task = native.exportVerified(output.getValue("subtitleTaskId"), output.getValue("generation"))!!
            assertEquals(OrezDurablePlanRules.requestId(plan.id, 1), task.ownerRequestId)
            assertEquals(SubtitleGenerationStatus.COMPLETED, task.status)
            assertFalse(task.validationPending); assertFalse(task.pcmValidationRequired)
            assertTrue(task.windows.size >= 2); assertTrue(task.cues.isNotEmpty())
            assertTrue(task.processedMs + 1500 >= task.durationMs)
            val srt = File(task.srtPath!!); val vtt = File(task.vttPath!!)
            assertEquals(SubtitleFormats.srt(task.cues), srt.readText(Charsets.UTF_8))
            assertEquals(SubtitleFormats.vtt(task.cues), vtt.readText(Charsets.UTF_8))
            assertEquals(SubtitleGenerationStore.fileHash(srt), output["srtSha256"])
            assertEquals(SubtitleGenerationStore.fileHash(vtt), output["vttSha256"])
            assertEquals(srt.length().toString(), output["srtBytes"])
            assertEquals(vtt.length().toString(), output["vttBytes"])
            assertFalse(saved.steps.flatMap { it.outputs.values }.any { it.contains(selection.uri) || it.contains(context.filesDir.path) })
            val media = OrezSubtitlePlanScope.expected(saved, saved.steps[1])
            val replay = OrezNativeSubtitleHost(context).start(media, options, task.ownerRequestId!!, allowReplacement = false)
            assertEquals(task.generation, replay.generation)
            assertEquals(OrezNativeSubtitleStatus.COMPLETED, replay.status)
            assertTrue(executor().run(plan.id, plan.executionEpoch) is OrezTaskExecutor.Result.AlreadyFinished)
        } finally {
            cancelNative(plan)
            file.delete()
        }
    }

    private suspend fun queuedPlan(selection: OrezMediaSelection): Pair<OrezTaskPlan, SubtitleGenerationTask> {
        val source = SubtitleInputs.capture(context, SubtitleMediaSource(selection.uri, selection.headers, selection.cacheKey, selection.label))
        val plan = proposed(selection)
        // The headless stop fixture must stop before model loading or inference; this is a metadata pin only.
        val task = native.start(source, SubtitleGenerationConfig(sourceLanguage = options.sourceLanguage, modelSha256 = "b".repeat(64), threads = options.threads),
            ownerRequestId = OrezDurablePlanRules.requestId(plan.id, 1))
        val receipt = OrezSubtitleNativeEvidence.metadataReceipt(task, selection.sourceId)
        val queued = plan.copy(status = OrezTaskStatus.RUNNING, steps = plan.steps.map {
            if (it.index == 0) it.copy(status = OrezStepStatus.COMPLETED, outputKind = OrezOutputKind.MEDIA_SOURCE,
                outputs = receipt.media.outputs(OrezDurablePlanRules.requestId(plan.id, 0)))
            else it.copy(status = OrezStepStatus.RUNNING, outputs = receipt.outputs(task.ownerRequestId!!))
        })
        assertTrue(journal().checkpoint(queued))
        return queued to task
    }
    private suspend fun runNativeWorker(task: SubtitleGenerationTask) {
        val request = OneTimeWorkRequestBuilder<SubtitleGenerationWorker>().setInputData(workDataOf(
            "subtitle_task" to task.id, "subtitle_generation" to task.generation)).build()
        val work = WorkManager.getInstance(context)
        work.enqueueUniqueWork("orez-subtitle-headless-test-${task.id}-${task.generation}", ExistingWorkPolicy.KEEP, request).result.get(10, TimeUnit.SECONDS)
        val result = withTimeout(30_000) {
            while (true) {
                val info = work.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS)
                if (info?.state?.isFinished == true) return@withTimeout info.state
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") WorkInfo.State.FAILED
        }
        assertEquals(WorkInfo.State.SUCCEEDED, result)
    }

    @Test fun headlessWorkerHonorsPersistedCancellationBeforeSourceModelOrAudioWork() = runBlocking {
        val (file, selection) = fixture()
        val (plan, task) = queuedPlan(selection)
        try {
            journal().beginStop(plan.id, OrezPendingControl.CANCEL)
            // No Orez screen, ViewModel or control command is created. The worker reads accepted intent itself.
            runNativeWorker(task)
            val saved = journal().load(plan.id)!!
            assertEquals(OrezTaskStatus.CANCELLED, saved.status); assertNull(saved.pendingControl)
            assertEquals(SubtitleGenerationStatus.CANCELLED, native.get(task.id)!!.status)
            assertTrue(native.get(task.id)!!.windows.isEmpty())
        } finally { cancelNative(plan); journal().cancel(plan.id); file.delete() }
    }

    @Test fun headlessWorkerRecoversNativeResumeCommitGapThenPausesOnlyOwnedNewGeneration() = runBlocking {
        val (file, selection) = fixture()
        val (plan, task) = queuedPlan(selection)
        try {
            native.pause(task.id, task.generation)
            journal().pause(plan.id)
            journal().resume(plan.id, dispatchReady = false)
            val g2 = native.resume(task.id, task.generation)!!
            assertNotEquals(task.generation, g2.generation)
            // Persist stop while Orez still contains G1 and its durable resume proof.
            assertTrue(journal().load(plan.id)!!.resuming)
            journal().beginStop(plan.id, OrezPendingControl.PAUSE)
            runNativeWorker(g2)
            val saved = journal().load(plan.id)!!
            assertEquals(OrezTaskStatus.WAITING, saved.status)
            assertNull(saved.pendingControl); assertFalse(saved.resuming)
            assertEquals(g2.generation, saved.steps[1].outputs["generation"])
            assertEquals(SubtitleGenerationStatus.PAUSED, native.get(task.id)!!.status)
            assertTrue(native.get(task.id)!!.windows.isEmpty())
        } finally { cancelNative(plan); journal().cancel(plan.id); file.delete() }
    }

    companion object {
        private const val AUDIO_SHA = "59dfb9a4acb36fe2a2affc14bacbee2920ff435cb13cc314a08c13f66ba7860e"
        private const val MODEL_SHA = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"
        private const val MODEL_BYTES = 77_691_713L
    }
}
