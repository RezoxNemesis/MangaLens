package com.mangalens

import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.mangalens.download.OriginalFragmentPlan
import com.mangalens.download.OriginalMediaFragment
import com.mangalens.orez.OrezRoomDatabase
import com.mangalens.orez.agent.*
import com.mangalens.orez.agent.OrezSubtitleTools.Companion.outputs
import com.mangalens.ui.video.*
import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Authored UNRUN. Real staged AVC/AAC speech bytes -> owned spool/Worker/Whisper -> timed exports.
 * The selected fragment HTTP exchange is controlled; public-provider access and ASR WER are separate.
 */
@RunWith(AndroidJUnit4::class)
class OrezFragmentSubtitleRuntimeTest {
    @Test fun selectedOriginalVideoFragmentsRunOwnedWorkerAndColdReopenRealWhisperExportsWithoutAnchorRequests(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val file = File(requireNotNull(args.getString("sample_video")) { "Stage the pinned-source 22s AVC/AAC speech fixture." })
        val expectedFileHash = requireNotNull(args.getString("sample_video_sha256")) { "Pass the hash of the actual generated/staged speech video." }
        assertTrue(expectedFileHash.matches(Regex("[a-f0-9]{64}")))
        assertTrue(file.isFile && file.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
        assertTrue(file.length() in 1..16L * 1024 * 1024)
        assertEquals(expectedFileHash, SubtitleGenerationStore.fileHash(file))
        val extractor = MediaExtractor()
        val durationUs: Long
        try {
            extractor.setDataSource(file.absolutePath)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val video = formats.single { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val audio = formats.single { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            assertEquals("video/avc", video.getString(MediaFormat.KEY_MIME)); assertEquals(720, video.getInteger(MediaFormat.KEY_HEIGHT))
            assertEquals("audio/mp4a-latm", audio.getString(MediaFormat.KEY_MIME))
            durationUs = audio.getLong(MediaFormat.KEY_DURATION)
            assertTrue("The full speech fixture must contain the second spoken pass", durationUs in 21_000_000..23_000_000)
        } finally { extractor.release() }
        val reference = mutableListOf<SubtitleWindow>()
        withTimeout(30_000) {
            SubtitleAudioDecoder(context).decode(SubtitleMediaSource(Uri.fromFile(file).toString())) { pcm, start, _, _ ->
                reference += SubtitleWindow(reference.size, start, start + pcm.size * 1000L / 16000, SubtitleInputs.pcmHash(pcm), emptyList(), false)
            }
        }
        assertTrue(reference.size >= 3); assertTrue(reference.last().endMs >= 21_000)
        val bytes = file.readBytes()
        val parts = linkedMapOf<String, ByteArray>()
        val requests = AtomicInteger(); val anchors = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest): MockResponse {
            requests.incrementAndGet()
            val payload = parts[request.requestUrl?.encodedPath]
            if (payload == null) { anchors.incrementAndGet(); return MockResponse().setResponseCode(404).setBody("This anchor is not the selected encoded track") }
            return MockResponse().setHeader("Content-Type", "video/mp4").setBody(Buffer().write(payload))
        } }
        server.start()
        val resolution = UUID.randomUUID().toString().replace("-", "")
        val boundaries = listOf(0, 1024, bytes.size / 2, bytes.size)
        val fragments = boundaries.zipWithNext().mapIndexed { index, (start, end) ->
            val name = "/selected-$index"; val payload = bytes.copyOfRange(start, end); parts[name] = payload
            OriginalMediaFragment(server.url(name).toString(), expectedBytes = payload.size.toLong())
        }
        val plan = OriginalFragmentPlan(server.url("/selected-source-anchor.mpd").toString(), "controlled-avc-aac", "video/mp4", durationUs, fragments)
        val selection = OrezMediaSelection(plan.sourceUrl, cacheKey = "fragment-runtime-$resolution", resolutionId = resolution,
            expectedDurationUs = durationUs, videoFragments = plan).captured()
        val options = OrezSubtitleOptions(sourceLanguage = "en", threads = 2)
        val native = SubtitleGenerationStore.shared(context)
        fun journal() = OrezTaskStore(OrezRoomDatabase.get(context).tasks())
        val proposed = OrezAgentRuntime().decide("Generate English subtitles for this selected video",
            OrezAgentContext(selectedMedia = selection, subtitleOptions = options)).plan!!
        val owner = OrezDurablePlanRules.requestId(proposed.id, 1)
        val evidence = JSONObject().put("status", "failed").put("scope", "controlled full encoded fragment/Worker/Whisper export")
            .put("source_sha256", expectedFileHash).put("source_bytes", bytes.size).put("fragment_plan_sha256", plan.sha256())
            .put("model_sha256", MODEL_SHA).put("source_height", 720).put("audio_mime", "audio/mp4a-latm")
        var stopped = false
        var taskId: String? = null
        var workRequestId: UUID? = null
        var primaryFailure: Throwable? = null
        try {
            val model = File(requireNotNull(args.getString("whisper_model_path")) { "Stage the pinned multilingual Tiny model." })
            assertEquals(MODEL_BYTES, model.length()); assertEquals(MODEL_SHA, SubtitleGenerationStore.fileHash(model))
            if (SubtitleInputs.config(context, "en").modelSha256 != MODEL_SHA) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val engine = VideoSpeechEngine(context, scope)
                try { withTimeout(90_000) { engine.importModel(Uri.fromFile(model)) }; assertTrue(engine.state.value.status, engine.state.value.ready) }
                finally { try { withContext(NonCancellable) { engine.close() } } finally { scope.cancel() } }
            }
            assertEquals(MODEL_SHA, SubtitleInputs.config(context, "en").modelSha256)
            val source = SubtitleInputs.capture(context, selection.nativeSubtitleSource())
            assertFalse(hasSubtitleSourceProof(source)); assertEquals(0, requests.get())
            val g1 = native.start(source, options.nativeConfig(MODEL_SHA), ownerRequestId = owner)
            taskId = g1.id
            val receipt = OrezSubtitleNativeEvidence.metadataReceipt(g1, selection.sourceId, selection)
            val queued = proposed.copy(status = OrezTaskStatus.RUNNING, steps = proposed.steps.map { step ->
                if (step.index == 0) step.copy(status = OrezStepStatus.COMPLETED, outputKind = OrezOutputKind.MEDIA_SOURCE,
                    outputs = receipt.media.outputs(OrezDurablePlanRules.requestId(proposed.id, 0)))
                else step.copy(status = OrezStepStatus.RUNNING, outputs = receipt.outputs(owner))
            })
            assertTrue(journal().checkpoint(queued))
            // Persist a real resume-commit gap. The worker must bind its own G2 and enrich only that source.
            native.pause(g1.id, g1.generation); journal().pause(proposed.id)
            journal().resume(proposed.id, dispatchReady = false)
            val g2 = requireNotNull(native.resume(g1.id, g1.generation))
            assertNotEquals(g1.generation, g2.generation); assertTrue(journal().load(proposed.id)!!.resuming)
            assertNull(SubtitleGenerationJobs.cancel(context, g1.id, g1.generation))
            assertEquals(g2.generation, native.get(g1.id)!!.generation)
            // A cold production control reconciles the persisted gap before dispatch. Its enqueue
            // seam is intentionally empty: the exact native request below is the only test dispatch.
            OrezTaskControls(context, journal(), OrezNativeSubtitleHost(context), enqueueTask = {}).resume(proposed.id, restoreOnly = true)
            val restored = requireNotNull(journal().load(proposed.id))
            assertFalse(restored.resuming); assertFalse(restored.pausedByUser)
            assertEquals(g2.generation, restored.steps[1].outputs["generation"])
            val work = WorkManager.getInstance(context)
            val request = OneTimeWorkRequestBuilder<SubtitleGenerationWorker>().setInputData(workDataOf(
                "subtitle_task" to g2.id, "subtitle_generation" to g2.generation)).build()
            workRequestId = request.id
            work.enqueueUniqueWork("fragment-runtime-${g2.id}-${g2.generation}", ExistingWorkPolicy.KEEP, request).result.get(10, TimeUnit.SECONDS)
            withTimeout(300_000) {
                while (work.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS)?.state?.isFinished != true) delay(100)
                assertEquals(WorkInfo.State.SUCCEEDED, work.getWorkInfoById(request.id).get(10, TimeUnit.SECONDS)!!.state)
                val coldPlan = requireNotNull(journal().load(proposed.id))
                val store = journal()
                val executor = OrezTaskExecutor(store, OrezSubtitleTools.forPlan(store, coldPlan, OrezNativeSubtitleHost(context)))
                var result = executor.run(coldPlan.id, coldPlan.executionEpoch)
                while (result is OrezTaskExecutor.Result.Pending) { delay(100); result = executor.run(coldPlan.id, coldPlan.executionEpoch) }
                assertTrue(result.toString(), result is OrezTaskExecutor.Result.Completed || result is OrezTaskExecutor.Result.AlreadyFinished)
            }
            val saved = requireNotNull(journal().load(proposed.id))
            assertEquals(OrezTaskStatus.COMPLETED, saved.status); assertFalse(saved.resuming)
            val output = saved.steps[1].outputs
            val completed = requireNotNull(native.exportVerified(g2.id, g2.generation))
            assertEquals(owner, completed.ownerRequestId); assertEquals(MODEL_SHA, completed.config.modelSha256)
            assertEquals(selection.nativeSubtitleSource(), completed.source.source)
            assertEquals(expectedFileHash, completed.source.fragmentContentSha256); assertEquals(file.length(), completed.source.fragmentSize)
            assertTrue(hasSubtitleSourceProof(completed.source)); assertTrue(completed.audioComplete)
            assertFalse(completed.validationPending); assertFalse(completed.pcmValidationRequired)
            assertEquals(reference.map { Triple(it.startMs, it.endMs, it.pcmSha256) }, completed.windows.map { Triple(it.startMs, it.endMs, it.pcmSha256) })
            assertTrue(completed.cues.isNotEmpty()); assertTrue(completed.cues.last().endMs >= 18_000)
            assertTrue(completed.cues.all { it.startMs >= 0 && it.endMs > it.startMs && it.endMs <= completed.durationMs + 1500 })
            assertTrue(completed.processedMs + 1500 >= completed.durationMs)
            val srt = File(completed.srtPath!!); val vtt = File(completed.vttPath!!)
            assertEquals(SubtitleFormats.srt(completed.cues), srt.readText()); assertEquals(SubtitleFormats.vtt(completed.cues), vtt.readText())
            assertEquals(SubtitleGenerationStore.fileHash(srt), output["srtSha256"]); assertEquals(SubtitleGenerationStore.fileHash(vtt), output["vttSha256"])
            assertEquals(srt.length().toString(), output["srtBytes"]); assertEquals(vtt.length().toString(), output["vttBytes"])
            assertEquals(0, anchors.get()); assertEquals(parts.size, requests.get())
            server.shutdown(); stopped = true
            val count = requests.get()
            val fresh = withTimeout(30_000) { SubtitleInputs.capture(context, selection.nativeSubtitleSource()) }
            assertTrue(canTrustSubtitleSource(completed.source, fresh)); assertEquals(count, requests.get())
            val expected = OrezSubtitlePlanScope.expected(saved, saved.steps[1])
            val replay = OrezNativeSubtitleHost(context).start(expected, options, owner, allowReplacement = false)
            assertEquals(g2.generation, replay.generation); assertEquals(OrezNativeSubtitleStatus.COMPLETED, replay.status)
            assertNull(SubtitleGenerationJobs.cancel(context, g1.id, g1.generation))
            assertEquals(g2.generation, native.exportVerified(g2.id, g2.generation)!!.generation)
            assertEquals(output["srtSha256"], SubtitleGenerationStore.fileHash(srt)); assertEquals(count, requests.get())
            evidence.put("status", "passed").put("processed_ms", completed.processedMs).put("duration_ms", completed.durationMs)
                .put("windows", JSONArray().apply { completed.windows.forEach { put(JSONObject().put("start_ms", it.startMs).put("end_ms", it.endMs).put("pcm_sha256", it.pcmSha256)) } })
                .put("cue_count", completed.cues.size).put("last_cue_end_ms", completed.cues.last().endMs)
                .put("srt_sha256", output["srtSha256"]).put("vtt_sha256", output["vttSha256"])
                .put("anchor_requests", anchors.get()).put("fragment_requests", requests.get()).put("cold_replay_network_requests", requests.get() - count)
        } catch (failure: Throwable) {
            primaryFailure = failure
            evidence.put("status", "failed").put("failure_class", failure.javaClass.name)
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            suspend fun cleanup(block: suspend () -> Unit) {
                try { withContext(NonCancellable) { block() } }
                catch (failure: Throwable) {
                    val original = primaryFailure ?: cleanupFailure
                    if (original == null) cleanupFailure = failure else original.addSuppressed(failure)
                    evidence.put("cleanup_failure_class", failure.javaClass.name).put("status", "failed")
                }
            }
            // This test uses its own exact WM operation, not the production unique name.
            // Scheduler retirement is NOT an assertion that a pending JNI call has returned.
            cleanup { workRequestId?.let { WorkManager.getInstance(context).cancelWorkById(it).result.get(10, TimeUnit.SECONDS) } }
            cleanup { taskId?.let { id -> native.get(id)?.takeIf { it.ownerRequestId == owner && it.status != SubtitleGenerationStatus.COMPLETED }?.let {
                SubtitleGenerationJobs.cancel(context, it.id, it.generation)
            } } }
            cleanup { if (!stopped) server.shutdown() }
            cleanup {
                val target = File(context.filesDir, "mangalens-qa/fragment-subtitle-runtime/outputs.json")
                target.parentFile!!.mkdirs(); target.writeText(evidence.toString(2))
            }
            // Failed/unproven actual cleanup keeps its source and ownership receipt intact.
            if (primaryFailure == null) cleanupFailure?.let { throw it }
        }
    }
    companion object {
        private const val MODEL_SHA = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"
        private const val MODEL_BYTES = 77_691_713L
    }
}
