package com.mangalens.orez.agent

import com.mangalens.core.translation.HindiRomanization
import com.mangalens.core.translation.TranslationDraft
import com.mangalens.core.translation.TranslationQualityPolicy
import com.mangalens.core.translation.TranslationRefinementPolicy
import com.mangalens.core.translation.TranslationRefinementRequest
import com.mangalens.core.translation.TranslationStyleProfile
import com.mangalens.orez.OrezModelPin
import com.mangalens.orez.OrezLocalizationProfile
import com.mangalens.ui.video.SubtitleRefinementCompletionEvidence
import com.mangalens.orez.OrezTaskDao
import com.mangalens.orez.OrezTaskEntity
import com.mangalens.orez.agent.OrezSubtitleTools.Companion.outputs
import com.mangalens.ui.video.SpeechCue
import com.mangalens.ui.video.SubtitleFormats
import com.mangalens.ui.video.SubtitleGenerationStatus
import com.mangalens.ui.video.SubtitleGenerationStore
import com.mangalens.ui.video.SubtitleGenerationTask
import com.mangalens.ui.video.SubtitleJournalIo
import com.mangalens.ui.video.SubtitleOutputMode
import com.mangalens.ui.video.SubtitleGenerationConfig
import com.mangalens.ui.video.SubtitleRefinementPin
import com.mangalens.ui.video.SubtitleSavedRefinement
import com.mangalens.ui.video.SubtitleTargetOptions
import com.mangalens.ui.video.translationContext
import com.mangalens.ui.video.SubtitleSourceIdentity
import com.mangalens.ui.video.SubtitleTranslatedCue
import com.mangalens.ui.video.SubtitleWindow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/** Actual native Store/checkpoint/export/receipt contract. Synthetic timed cues do not prove recognition or ML Kit quality. */
class OrezNativeTargetWorkflowTest {
    @get:Rule val temporary = TemporaryFolder()
    private val model = "b".repeat(64)
    private val pcm = "c".repeat(64)
    private val io = object : SubtitleJournalIo {
        override fun read(file: File) = file.readBytes()
        override fun write(file: File, bytes: ByteArray) { file.writeBytes(bytes) }
    }
    private class Journal : OrezTaskDao {
        var row: OrezTaskEntity? = null
        override fun observeActive() = flowOf(listOfNotNull(row))
        override suspend fun get(id: String) = row?.takeIf { it.id == id }
        override suspend fun upsert(task: OrezTaskEntity) { row = task }
        override suspend fun pruneFinished(before: Long) = Unit
    }
    private data class Fixture(val directory: File, val source: SubtitleSourceIdentity, val media: OrezMediaSelection,
        val plan: OrezTaskPlan, val native: SubtitleGenerationStore, val taskId: String, val generation: String) {
        fun receipt(task: SubtitleGenerationTask = native.get(taskId)!!) = OrezSubtitleNativeEvidence.receipt(task, media.sourceId, directory, media)
    }
    private fun fixture(target: String = "hi-latn", paired: Boolean = false, options: OrezSubtitleOptions? = null): Fixture {
        val root = temporary.newFolder()
        val audio = temporary.newFile().apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val media = if (paired) OrezMediaSelection("https://explicit-video.example/captured.mp4", "https://selected-page.example/watch",
            headers = mapOf("Cookie" to "private-video"), resolutionId = "selected-resolution",
            audio = OrezAudioSelection(audio.toURI().toString(), "selected-resolution", mapOf("Cookie" to "private-audio")), expectedDurationUs = 8_000_000)
        else OrezMediaSelection(audio.toURI().toString())
        val plan = OrezAgentRuntime().decide("Generate dual subtitles in ${if (target == "hi") "Hindi" else "Hinglish"} for this selected video",
            OrezAgentContext(selectedMedia = media, subtitleOptions = options ?: OrezSubtitleOptions(threads = 2))).plan!!
        assertEquals(OrezTaskStatus.RUNNING, plan.status)
        val source = SubtitleSourceIdentity(media.nativeSubtitleSource(), sha(audio.readBytes()))
        val native = SubtitleGenerationStore(root, io)
        val task = native.start(source, plan.authorization!!.subtitle!!.nativeConfig(model),
            ownerRequestId = OrezDurablePlanRules.requestId(plan.id, 1), allowOwnerReplacement = false)
        assertNotNull(native.running(task.id, task.generation))
        assertTrue(native.checkpoint(task.id, task.generation, SubtitleWindow(0, 0, 8_000, pcm,
            sourceCues = listOf(SpeechCue(100, 2200, "Fire!"), SpeechCue(2800, 6500, "We can begin.")), detectedLanguage = "en"), 8_000, "en"))
        return Fixture(root, source, media, plan, native, task.id, task.generation)
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun addTarget(f: Fixture, index: Int) {
        val task = f.native.get(f.taskId)!!; val source = task.windows[0].sourceCues[index].text
        val hindi = if (index == 0) "आग!" else "हम शुरू कर सकते हैं।"
        val draft = if (task.config.targetLanguage == "hi-latn") TranslationDraft(HindiRomanization.render(hindi, source), hindi) else TranslationDraft(hindi)
        val accepted = TranslationQualityPolicy.chooseDraft(source, draft, "", task.config.targetLanguage)
        assertTrue(f.native.checkpointTarget(task.id, task.generation, 0, pcm, SubtitleTranslatedCue(index, accepted.text, accepted.hindiDraft)))
    }
    private fun complete(f: Fixture): SubtitleGenerationTask {
        addTarget(f, 0); addTarget(f, 1)
        assertNotNull(f.native.completeAudio(f.taskId, f.generation, 1))
        return f.native.finish(f.taskId, f.generation)!!
    }
    private fun prepared(f: Fixture): OrezTaskPlan {
        val receipt = f.receipt()
        return f.plan.copy(steps = f.plan.steps.map { step ->
            if (step.index == 0) step.copy(status = OrezStepStatus.COMPLETED, outputKind = OrezOutputKind.MEDIA_SOURCE,
                outputs = receipt.media.outputs(OrezDurablePlanRules.requestId(f.plan.id, 0)))
            else step.copy(status = OrezStepStatus.RUNNING, outputs = receipt.outputs(OrezDurablePlanRules.requestId(f.plan.id, 1)))
        })
    }
    private fun provider(f: Fixture, native: SubtitleGenerationStore = f.native, installedModelAvailable: Boolean = true) = object : OrezSubtitleHost {
        var installedInspections = 0
        override suspend fun inspectSelection(selection: OrezMediaSelection, pinnedModelSha256: String?): OrezSubtitleSnapshot {
            installedInspections++
            check(installedModelAvailable) { "No speech model is installed." }
            assertEquals(f.media, selection)
            return OrezSubtitleSnapshot(selection.sourceId, selection, f.source.fingerprint, model)
        }
        override suspend fun inspectDownload(download: OrezSubtitleDownload, pinnedModelSha256: String?) = error("Not a native download fixture")
        private fun receipt(task: SubtitleGenerationTask) = OrezSubtitleNativeEvidence.receipt(task, f.media.sourceId, f.directory, f.media)
        override suspend fun start(media: OrezSubtitleSnapshot, options: OrezSubtitleOptions, requestId: String, allowReplacement: Boolean) =
            receipt(native.start(f.source, options.nativeConfig(media.speechModelSha256), ownerRequestId = requestId, allowOwnerReplacement = allowReplacement))
        override suspend fun observe(taskId: String, requestId: String, media: OrezSubtitleSnapshot, options: OrezSubtitleOptions) = start(media, options, requestId, false)
        override suspend fun findOwned(requestId: String) = native.get(f.taskId)?.takeIf { it.ownerRequestId == requestId }?.let(::receipt)
        override suspend fun revalidateOwned(receipt: OrezSubtitleReceipt): OrezSubtitleSnapshot? =
            native.get(receipt.taskId)?.let { OrezSubtitleNativeEvidence.savedSpeechSnapshot(receipt, it, f.source) }
        override suspend fun pause(receipt: OrezSubtitleReceipt) = native.pause(receipt.taskId, receipt.generation)?.let(::receipt)
        override suspend fun resume(receipt: OrezSubtitleReceipt) = native.resume(receipt.taskId, receipt.generation)?.let(::receipt)
        override suspend fun cancel(receipt: OrezSubtitleReceipt) = native.cancel(receipt.taskId, receipt.generation)?.let(::receipt)
    }

    @Test fun actualHindiAndHinglishDualExportsCompleteTheirTypedPlanAndReopenFullScope() = runTest {
        for (target in listOf("hi", "hi-latn")) {
            val f = fixture(target, paired = true); val completed = complete(f)
            assertEquals(SubtitleGenerationStatus.COMPLETED, completed.status)
            assertEquals(SubtitleOutputMode.DUAL, completed.config.outputMode)
            assertEquals(listOf(100L to 2200L, 2800L to 6500L), completed.cues.map { it.startMs to it.endMs })
            assertTrue(completed.cues[0].text.startsWith("Fire!\n"))
            assertTrue(completed.cues[1].text.startsWith("We can begin.\n"))
            assertEquals(SubtitleFormats.srt(completed.cues), File(completed.srtPath!!).readText())
            assertEquals(SubtitleFormats.vtt(completed.cues), File(completed.vttPath!!).readText())
            val dao = Journal(); val store = OrezTaskStore(dao); store.checkpoint(prepared(f))
            val result = OrezTaskExecutor(store, OrezSubtitleTools.forPlan(store, f.plan, provider(f))).run(f.plan.id, f.plan.executionEpoch)
            assertTrue(result is OrezTaskExecutor.Result.Completed)
            val reopened = OrezTaskStore(dao).load(f.plan.id)!!
            assertEquals(f.plan.authorization, reopened.authorization)
            val evidence = reopened.steps[1].outputs
            assertEquals(completed.config.fingerprint(), evidence["configFingerprint"])
            assertEquals("DUAL", evidence["outputMode"])
            assertEquals("SOURCE_TRANSLATION", evidence["pipeline"])
            assertEquals("true", evidence["audioComplete"])
            assertEquals("0", evidence["pendingTargetCues"])
            assertEquals(target, evidence["targetLanguage"])
            assertTrue(evidence.size in 21..32)
            for (privateValue in listOf(f.media.uri, f.media.audio!!.uri, "private-video", "private-audio", f.directory.path))
                assertFalse(reopened.steps.flatMap { it.outputs.values }.any { privateValue in it })
            OrezDurablePlanRules.validate(reopened)
        }
    }

    @Test fun partialTargetsRetainOriginalSpeechAndCannotPublishCompletion() {
        val f = fixture(); addTarget(f, 0); f.native.completeAudio(f.taskId, f.generation, 1)
        val partial = f.native.finish(f.taskId, f.generation)!!
        assertEquals(SubtitleGenerationStatus.PARTIAL, partial.status)
        assertTrue(partial.audioComplete)
        assertEquals(2, partial.sourceCueCount)
        assertEquals(1, partial.pendingTargetCues)
        assertEquals("आग!", partial.windows[0].translations.single().hindiDraft)
        val receipt = f.receipt(partial)
        assertNull(receipt.exports)
        assertThrows(IllegalArgumentException::class.java) { OrezSubtitleTools.verifyCompleted(receipt) }
        assertNull(f.native.exportVerified(partial.id, partial.generation))
    }

    @Test fun reopenedOriginalSpeechRepairsAndResumesWithoutInspectingAnUnavailableInstalledWhisperModel() = runTest {
        val f = fixture(paired = true); addTarget(f, 0); f.native.completeAudio(f.taskId, f.generation, 1)
        f.native.finish(f.taskId, f.generation)
        val plan = prepared(f).copy(status = OrezTaskStatus.FAILED)
        val dao = Journal(); val store = OrezTaskStore(dao); store.checkpoint(plan)
        val reopening = SubtitleGenerationStore(f.directory, io)
        val host = provider(f, reopening, installedModelAvailable = false)
        val waiting = store.resume(f.plan.id, dispatchReady = false)!!
        val resumed = OrezSubtitleControlOperations(host).resume(plan, waiting, waiting.steps[1])
        assertNotEquals(f.generation, resumed.steps[1].outputs["generation"])
        assertEquals(0, host.installedInspections)
        assertNotNull(store.finishResume(resumed))
        val dispatch = store.load(f.plan.id)!!
        val result = OrezTaskExecutor(store, OrezSubtitleTools.forPlan(store, dispatch, host)).run(dispatch.id, dispatch.executionEpoch)
        assertTrue(result is OrezTaskExecutor.Result.Pending)
        assertEquals(0, host.installedInspections)
        assertEquals(model, reopening.get(f.taskId)!!.config.modelSha256)
        assertEquals(1, reopening.get(f.taskId)!!.pendingTargetCues)
        assertEquals(f.native.get(f.taskId)!!.windows, reopening.get(f.taskId)!!.windows)
    }

    @Test fun headlessPauseAndCancelRecoverNativeResumeCommitGapWithExactPairAndTargetEvidence() = runTest {
        for (control in OrezPendingControl.entries) {
            val f = fixture(paired = true); addTarget(f, 0); f.native.completeAudio(f.taskId, f.generation, 1)
            val dao = Journal(); val store = OrezTaskStore(dao); store.checkpoint(prepared(f))
            val saved = f.native.get(f.taskId)!!.windows
            f.native.pause(f.taskId, f.generation); store.pause(f.plan.id); store.resume(f.plan.id, dispatchReady = false)
            val rotated = f.native.resume(f.taskId, f.generation)!!
            store.beginStop(f.plan.id, control)
            val native = SubtitleGenerationStore(f.directory, io)
            val current = native.get(f.taskId)!!
            val allowed = OrezSubtitleWorkGate.allow(OrezTaskStore(dao), f.plan.id,
                OrezSubtitleNativeEvidence.metadataReceipt(current, f.media.sourceId, f.media), stop = { receipt, command ->
                    val stopped = if (command == OrezPendingControl.CANCEL) native.cancel(receipt.taskId, receipt.generation) else native.pause(receipt.taskId, receipt.generation)
                    stopped?.let { OrezSubtitleNativeEvidence.metadataReceipt(it, f.media.sourceId, f.media) }
                }, current = { id -> native.get(id)?.let { OrezSubtitleNativeEvidence.metadataReceipt(it, f.media.sourceId, f.media) } })
            assertFalse(allowed)
            val stopped = SubtitleGenerationStore(f.directory, io).get(f.taskId)!!
            assertEquals(rotated.generation, stopped.generation)
            assertEquals(saved, stopped.windows)
            assertTrue(stopped.audioComplete)
            assertEquals(if (control == OrezPendingControl.CANCEL) SubtitleGenerationStatus.CANCELLED else SubtitleGenerationStatus.PAUSED, stopped.status)
            val reopened = OrezTaskStore(dao).load(f.plan.id)!!
            assertNull(reopened.pendingControl); assertFalse(reopened.resuming)
            assertEquals(rotated.generation, reopened.steps[1].outputs["generation"])
            assertEquals(stopped.config.fingerprint(), reopened.steps[1].outputs["configFingerprint"])
            assertEquals(f.media, reopened.authorization!!.selectedMedia)
        }
    }

    @Test fun sameSlotReaderReplacementCannotBeStoppedOrOverwrittenByTheOlderTargetPlan() = runTest {
        val f = fixture(paired = true); addTarget(f, 0)
        val store = OrezTaskStore(Journal()); store.checkpoint(prepared(f)); store.beginStop(f.plan.id, OrezPendingControl.CANCEL)
        val prior = f.native.get(f.taskId)!!
        var replacement: SubtitleGenerationTask? = null
        val allowed = OrezSubtitleWorkGate.allow(store, f.plan.id,
            OrezSubtitleNativeEvidence.metadataReceipt(prior, f.media.sourceId, f.media), stop = { receipt, _ ->
                replacement = f.native.start(f.source, prior.config, ownerRequestId = "unrelated-reader")
                f.native.cancel(receipt.taskId, receipt.generation)?.let { OrezSubtitleNativeEvidence.metadataReceipt(it, f.media.sourceId, f.media) }
            }, current = { id -> f.native.get(id)?.let { OrezSubtitleNativeEvidence.metadataReceipt(it, f.media.sourceId, f.media) } })
        assertTrue(allowed)
        assertEquals("unrelated-reader", f.native.get(f.taskId)!!.ownerRequestId)
        assertEquals(replacement!!.generation, f.native.get(f.taskId)!!.generation)
        assertEquals(SubtitleGenerationStatus.QUEUED, f.native.get(f.taskId)!!.status)
        assertThrows(IllegalStateException::class.java) {
            f.native.start(f.source, prior.config, ownerRequestId = prior.ownerRequestId, allowOwnerReplacement = false)
        }
        assertEquals(OrezPendingControl.CANCEL, store.load(f.plan.id)!!.pendingControl)
    }

    @Test fun changedFullTargetConfigOrPairNeverGetsOldOwnedStopRights() = runTest {
        val f = fixture(paired = true); val prior = f.native.get(f.taskId)!!
        for (changed in listOf(prior.copy(config = prior.config.copy(outputMode = SubtitleOutputMode.TRANSLATED)),
            prior.copy(config = prior.config.copy(targetLanguage = "hi")),
            prior.copy(source = prior.source.copy(source = prior.source.source.copy(headers = mapOf("Cookie" to "changed-audio")))))) {
            val store = OrezTaskStore(Journal()); store.checkpoint(prepared(f)); store.beginStop(f.plan.id, OrezPendingControl.PAUSE)
            var mutations = 0
            val receipt = runCatching { OrezSubtitleNativeEvidence.metadataReceipt(changed, f.media.sourceId, f.media) }.getOrNull()
            if (receipt != null) assertTrue(OrezSubtitleWorkGate.allow(store, f.plan.id, receipt,
                stop = { _, _ -> mutations++; null }, current = { null }))
            assertEquals(0, mutations)
            assertEquals(OrezPendingControl.PAUSE, store.load(f.plan.id)!!.pendingControl)
            assertEquals(prior.generation, f.native.get(f.taskId)!!.generation)
        }
    }

    @Test fun exactCapturedRefinementProfileAndHindiProofSurviveActualNativeAndOrezJournals() = runTest {
        val instruction = "Keep short, serious dialogue."
        val profile = TranslationStyleProfile("custom", "Chosen style", instruction, false, false, false)
        val pin = SubtitleRefinementPin("selected-local-model", "f".repeat(64), 2_000_000)
        val requested = SubtitleGenerationConfig(modelSha256 = model, threads = 2).withTarget(SubtitleTargetOptions(
            "hi-latn", SubtitleOutputMode.DUAL, "custom", instruction, true, pin, profile,
            refinementInputProfileRevision = "orez-localization-v2")).orezOptions()
        val f = fixture(paired = true, options = requested)
        for (index in 0..1) {
            val task = f.native.get(f.taskId)!!
            val source = task.windows[0].sourceCues[index].text
            val hindi = if (index == 0) "आग!" else "हम शुरू कर सकते हैं।"
            val draft = TranslationQualityPolicy.chooseDraft(source, TranslationDraft(HindiRomanization.render(hindi, source), hindi), "", "hi-latn")
            // This deterministic receipt fixture verifies request/prompt/output binding. No local-model inference is asserted.
            val request = TranslationRefinementRequest(true, profile, OrezModelPin(pin.modelId, pin.sha256, pin.bytes),
                task.config.refinementInputProfileRevision)
            assertEquals(requested.refinementInputProfileRevision, request.inputProfileRevision)
            val prompt = TranslationRefinementPolicy.capturedPrompt(source, draft.text, "hi-latn", request,
                task.translationContext(0, index), emptyMap())
            val completion = SubtitleRefinementCompletionEvidence(requireNotNull(request.inputProfileRevision),
                OrezLocalizationProfile.hash(OrezLocalizationProfile.formattedPrompt(prompt, requireNotNull(request.inputProfileRevision))), "EOG",
                1, 1, OrezLocalizationProfile.MAX_TOKENS, 0, 0, 0, 0)
            val promptHash = TranslationRefinementPolicy.hash(prompt)
            val target = SubtitleTranslatedCue(index, draft.text, draft.hindiDraft,
                SubtitleSavedRefinement(pin, promptHash, TranslationRefinementPolicy.hash(draft.text), completion), draft.text, draft.text, draft.hindiDraft)
            assertThrows(IllegalArgumentException::class.java) {
                f.native.checkpointTarget(f.taskId, f.generation, 0, pcm,
                    target.copy(refinement = target.refinement!!.copy(completion = null)))
            }
            assertEquals(index, f.native.get(f.taskId)!!.windows.single().translations.size)
            assertTrue(f.native.checkpointTarget(f.taskId, f.generation, 0, pcm, target))
        }
        f.native.completeAudio(f.taskId, f.generation, 1)
        val completed = f.native.finish(f.taskId, f.generation)!!
        assertEquals(SubtitleGenerationStatus.COMPLETED, completed.status)
        val dao = Journal(); val store = OrezTaskStore(dao); store.checkpoint(prepared(f))
        val reopening = SubtitleGenerationStore(f.directory, io)
        val restored = reopening.get(f.taskId)!!
        assertEquals(completed.config, restored.config)
        assertEquals(completed.windows, restored.windows)
        assertEquals("आग!", restored.windows[0].translations[0].refinementHindiDraft)
        assertEquals(requested, OrezTaskStore(dao).load(f.plan.id)!!.authorization!!.subtitle)
        val host = provider(f, reopening, installedModelAvailable = false)
        val result = OrezTaskExecutor(store, OrezSubtitleTools.forPlan(store, f.plan, host)).run(f.plan.id, f.plan.executionEpoch)
        assertTrue(result is OrezTaskExecutor.Result.Completed)
        assertEquals(0, host.installedInspections)
        assertEquals(completed.config.fingerprint(), store.load(f.plan.id)!!.steps[1].outputs["configFingerprint"])
        val journal = File(f.directory, f.taskId + ".json")
        val json = org.json.JSONObject(journal.readText())
        json.getJSONArray("windows").getJSONObject(0).getJSONArray("translations").getJSONObject(0)
            .getJSONObject("refinement").put("promptSha256", "9".repeat(64))
        journal.writeText(json.toString())
        assertNull("An altered generated-candidate receipt cannot reopen as verified captions", SubtitleGenerationStore(f.directory, io).get(f.taskId))
    }
}
