package com.mangalens.orez.agent

import com.mangalens.orez.OrezTaskDao
import com.mangalens.orez.OrezTaskEntity
import com.mangalens.orez.agent.OrezSubtitleTools.Companion.outputs
import com.mangalens.ui.video.SpeechCue
import com.mangalens.ui.video.SubtitleFormats
import com.mangalens.ui.video.SubtitleGenerationConfig
import com.mangalens.ui.video.SubtitleGenerationStatus
import com.mangalens.ui.video.SubtitleGenerationStore
import com.mangalens.ui.video.SubtitleGenerationTask
import com.mangalens.ui.video.SubtitleJournalIo
import com.mangalens.ui.video.SubtitleMediaSource
import com.mangalens.ui.video.SubtitleSourceIdentity
import com.mangalens.ui.video.SubtitleWindow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/** Real native journal/export contracts; cue fixtures verify receipt handling, not recognition accuracy. */
class OrezSubtitleNativeEvidenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val options = OrezSubtitleOptions(threads = 2)
    private val pin = "b".repeat(64)
    private val io = object : SubtitleJournalIo {
        override fun read(file: File) = file.readBytes()
        override fun write(file: File, bytes: ByteArray) { file.writeBytes(bytes) }
    }
    private data class Fixture(val directory: File, val native: SubtitleGenerationStore, val task: SubtitleGenerationTask,
        val selection: OrezMediaSelection, val plan: OrezTaskPlan) {
        val receipt get() = OrezSubtitleNativeEvidence.receipt(task, selection.sourceId, directory)
    }
    private fun fixture(remote: Boolean = false, complete: Boolean = true): Fixture {
        val directory = temporary.newFolder()
        val mediaFile = temporary.newFile().apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val selection = OrezMediaSelection(if (remote) "https://example.com/explicit.mp4" else mediaFile.toURI().toString(),
            label = "Explicit native receipt")
        val plan = OrezAgentRuntime().decide("Generate English subtitles for this selected video",
            OrezAgentContext(selectedMedia = selection, subtitleOptions = options)).plan!!
        val source = SubtitleSourceIdentity(SubtitleMediaSource(selection.uri, selection.headers, selection.cacheKey, selection.label),
            sha(mediaFile.readBytes()), strongEtag = if (remote) "\"version-1\"" else null,
            networkSize = if (remote) mediaFile.length() else null, networkUrl = if (remote) selection.uri else null)
        val native = SubtitleGenerationStore(directory, io)
        val task = native.start(source, SubtitleGenerationConfig(modelSha256 = pin, threads = options.threads),
            ownerRequestId = OrezDurablePlanRules.requestId(plan.id, 1), allowOwnerReplacement = false)
        assertNotNull(native.running(task.id, task.generation))
        assertTrue(native.checkpoint(task.id, task.generation, SubtitleWindow(0, 0, 8_000, "c".repeat(64),
            listOf(SpeechCue(500, 2500, "Ask what you can do."), SpeechCue(3000, 6500, "Together we can begin."))), 8_000, "en"))
        val captured = if (complete) native.finish(task.id, task.generation)!! else native.get(task.id)!!
        assertEquals(if (complete) SubtitleGenerationStatus.COMPLETED else SubtitleGenerationStatus.RUNNING, captured.status)
        return Fixture(directory, native, captured, selection, plan)
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private class Journal : OrezTaskDao {
        var row: OrezTaskEntity? = null
        override fun observeActive() = flowOf(listOfNotNull(row))
        override suspend fun get(id: String) = row?.takeIf { it.id == id }
        override suspend fun upsert(task: OrezTaskEntity) { row = task }
        override suspend fun pruneFinished(before: Long) = Unit
    }
    private fun provider(fixture: Fixture, nativeTask: () -> SubtitleGenerationTask = { fixture.task }) = object : OrezSubtitleHost {
        override suspend fun inspectSelection(selection: OrezMediaSelection, pinnedModelSha256: String?) =
            OrezSubtitleNativeEvidence.metadataReceipt(fixture.task, selection.sourceId).media
        override suspend fun inspectDownload(download: OrezSubtitleDownload, pinnedModelSha256: String?) = error("Not a download fixture")
        override suspend fun start(media: OrezSubtitleSnapshot, options: OrezSubtitleOptions, requestId: String, allowReplacement: Boolean) =
            OrezSubtitleNativeEvidence.receipt(nativeTask(), fixture.selection.sourceId, fixture.directory)
        override suspend fun observe(taskId: String, requestId: String, media: OrezSubtitleSnapshot, options: OrezSubtitleOptions) =
            start(media, options, requestId, false)
        override suspend fun findOwned(requestId: String) = fixture.receipt.takeIf { it.ownerRequestId == requestId }
        override suspend fun pause(receipt: OrezSubtitleReceipt): OrezSubtitleReceipt? = error("Not a control fixture")
        override suspend fun resume(receipt: OrezSubtitleReceipt): OrezSubtitleReceipt? = error("Not a control fixture")
        override suspend fun cancel(receipt: OrezSubtitleReceipt): OrezSubtitleReceipt? = error("Not a control fixture")
    }

    @Test fun actualNativeJournalAndExportsCompleteTypedExecutorAndReopenWithoutLeakingPaths() = runTest {
        val f = fixture()
        assertTrue(f.task.generation.matches(Regex("[a-f0-9]{32}")))
        val receipt = f.receipt
        assertEquals(2, receipt.cueCount)
        assertEquals(File(f.task.srtPath!!).length(), receipt.exports!!.srtBytes)
        assertEquals(sha(SubtitleFormats.vtt(f.task.cues).toByteArray()), receipt.exports.vttSha256)
        val dao = Journal()
        val store = OrezTaskStore(dao)
        store.checkpoint(f.plan)
        val tools = OrezSubtitleTools.forPlan(store, f.plan, provider(f))
        assertTrue(OrezTaskExecutor(store, tools).run(f.plan.id, f.plan.executionEpoch) is OrezTaskExecutor.Result.Completed)
        val saved = OrezTaskStore(dao).load(f.plan.id)!!
        assertEquals(OrezTaskStatus.COMPLETED, saved.status)
        assertEquals(OrezOutputKind.MEDIA_SOURCE, saved.steps[0].outputKind)
        assertEquals(OrezOutputKind.SUBTITLE_TRACK, saved.steps[1].outputKind)
        assertEquals(f.task.generation, saved.steps[1].outputs["generation"])
        assertEquals("subtitle-track:${f.task.id}", saved.steps[1].outputs["destination"])
        assertFalse(saved.steps.flatMap { it.outputs.values }.any { it.contains(f.directory.path) || it.contains(f.selection.uri) })
    }

    @Test fun bothActualExportFilesMustStillMatchChecksumsAndNativeCues() {
        for (extension in listOf("srt", "vtt")) {
            val f = fixture()
            val file = File(if (extension == "srt") f.task.srtPath!! else f.task.vttPath!!)
            file.appendText("modified")
            assertThrows(IllegalArgumentException::class.java) { f.receipt }
            val updated = if (extension == "srt") f.task.copy(srtSha256 = sha(file.readBytes())) else f.task.copy(vttSha256 = sha(file.readBytes()))
            assertThrows(IllegalArgumentException::class.java) { OrezSubtitleNativeEvidence.receipt(updated, f.selection.sourceId, f.directory) }
            file.delete()
            assertThrows(IllegalArgumentException::class.java) { f.receipt }
        }
    }

    @Test fun anExportFromAnotherDirectoryOrGenerationCannotCompleteThisReceipt() {
        val f = fixture()
        val copy = temporary.newFile().apply { writeBytes(File(f.task.srtPath!!).readBytes()) }
        assertThrows(IllegalArgumentException::class.java) {
            OrezSubtitleNativeEvidence.receipt(f.task.copy(srtPath = copy.path), f.selection.sourceId, f.directory)
        }
        assertThrows(IllegalArgumentException::class.java) {
            OrezSubtitleNativeEvidence.receipt(f.task.copy(generation = "f".repeat(32)), f.selection.sourceId, f.directory)
        }
    }

    @Test fun restoredSourceAndPcmMasksNeverYieldCompletionEvidenceUntilActuallyValidated() {
        val f = fixture()
        val reopened = SubtitleGenerationStore(f.directory, io).get(f.task.id)!!
        assertTrue(reopened.validationPending)
        assertNull(OrezSubtitleNativeEvidence.receipt(reopened, f.selection.sourceId, f.directory).exports)
        for (masked in listOf(f.task.copy(validationPending = true), f.task.copy(pcmValidationRequired = true))) {
            val receipt = OrezSubtitleNativeEvidence.receipt(masked, f.selection.sourceId, f.directory)
            assertTrue(receipt.validationPending)
            assertEquals(0, receipt.cueCount)
            assertNull(receipt.exports)
            assertThrows(IllegalArgumentException::class.java) { OrezSubtitleTools.verifyCompleted(receipt) }
        }
    }

    @Test fun unverifiableAndLegacyRemoteClaimsCannotBecomeVerifiedNativeCompletions() {
        val f = fixture(remote = true)
        OrezSubtitleTools.verifyCompleted(f.receipt)
        for (identity in listOf(f.task.source.copy(verifiable = false), f.task.source.copy(strongEtag = null, networkSize = null, networkUrl = null),
            f.task.source.copy(strongEtag = "W/\"version-1\""), f.task.source.copy(networkUrl = null))) {
            val receipt = OrezSubtitleNativeEvidence.receipt(f.task.copy(source = identity), f.selection.sourceId, f.directory)
            assertFalse("A legacy verifiable flag is insufficient for remote media proof", receipt.media.verifiable)
            assertNull(receipt.exports)
            assertThrows(IllegalArgumentException::class.java) { OrezSubtitleTools.verifyCompleted(receipt) }
        }
    }

    @Test fun nativeOwnerAndCapturedOptionsAreCheckedAgainBeforeExecutorCompletion() = runTest {
        val f = fixture()
        for (bad in listOf(f.task.copy(ownerRequestId = "unrelated-reader"), f.task.copy(config = f.task.config.copy(threads = 4)),
            f.task.copy(config = f.task.config.copy(modelSha256 = "f".repeat(64))))) {
            val store = OrezTaskStore(Journal())
            store.checkpoint(f.plan)
            val result = OrezTaskExecutor(store, OrezSubtitleTools.forPlan(store, f.plan, provider(f) { bad })).run(f.plan.id, f.plan.executionEpoch)
            assertTrue(result is OrezTaskExecutor.Result.Failed)
            assertEquals(OrezStepStatus.COMPLETED, store.load(f.plan.id)!!.steps[0].status)
            assertEquals(OrezStepStatus.FAILED, store.load(f.plan.id)!!.steps[1].status)
        }
    }

    private fun runningPlan(f: Fixture): OrezTaskPlan {
        val receipt = OrezSubtitleNativeEvidence.metadataReceipt(f.task, f.selection.sourceId)
        return f.plan.copy(status = OrezTaskStatus.RUNNING, steps = f.plan.steps.map {
            if (it.index == 0) it.copy(status = OrezStepStatus.COMPLETED, outputKind = OrezOutputKind.MEDIA_SOURCE,
                outputs = receipt.media.outputs(OrezDurablePlanRules.requestId(f.plan.id, 0)))
            else it.copy(status = OrezStepStatus.RUNNING, outputs = receipt.outputs(OrezDurablePlanRules.requestId(f.plan.id, it.index)))
        })
    }
    private suspend fun nativeGate(store: OrezTaskStore, f: Fixture, native: SubtitleGenerationStore, task: SubtitleGenerationTask) =
        OrezSubtitleWorkGate.allow(store, f.plan.id, OrezSubtitleNativeEvidence.metadataReceipt(task, f.selection.sourceId),
            stop = { captured, control ->
                val stopped = if (control == OrezPendingControl.CANCEL) native.cancel(captured.taskId, captured.generation)
                    else native.pause(captured.taskId, captured.generation)
                stopped?.let { OrezSubtitleNativeEvidence.metadataReceipt(it, f.selection.sourceId) }
            }, current = { id -> native.get(id)?.let { OrezSubtitleNativeEvidence.metadataReceipt(it, f.selection.sourceId) } })

    @Test fun reopenedNativeJournalAcknowledgesPersistedHeadlessPauseAndCancelWithoutLosingWindows() = runTest {
        for (control in OrezPendingControl.entries) {
            val f = fixture(complete = false)
            val dao = Journal(); val store = OrezTaskStore(dao)
            store.checkpoint(runningPlan(f)); store.beginStop(f.plan.id, control)
            val reopenedNative = SubtitleGenerationStore(f.directory, io)
            assertFalse(nativeGate(OrezTaskStore(dao), f, reopenedNative, reopenedNative.get(f.task.id)!!))
            val stopped = SubtitleGenerationStore(f.directory, io).get(f.task.id)!!
            assertEquals(f.task.generation, stopped.generation)
            assertEquals(f.task.windows, stopped.windows)
            assertEquals(if (control == OrezPendingControl.CANCEL) SubtitleGenerationStatus.CANCELLED else SubtitleGenerationStatus.PAUSED, stopped.status)
            val saved = OrezTaskStore(dao).load(f.plan.id)!!
            assertNull(saved.pendingControl)
            assertEquals(stopped.generation, saved.steps[1].outputs["generation"])
        }
    }

    @Test fun nativeResumeCommitGapIsRecoveredForPauseAndCancelWithActualRotatedGeneration() = runTest {
        for (control in OrezPendingControl.entries) {
            val f = fixture(complete = false)
            val dao = Journal(); val store = OrezTaskStore(dao)
            store.checkpoint(runningPlan(f))
            f.native.pause(f.task.id, f.task.generation)
            store.pause(f.plan.id); store.resume(f.plan.id, dispatchReady = false)
            val resumed = f.native.resume(f.task.id, f.task.generation)!!
            assertNotEquals(f.task.generation, resumed.generation)
            store.beginStop(f.plan.id, control)
            val reopenedNative = SubtitleGenerationStore(f.directory, io)
            assertFalse(nativeGate(OrezTaskStore(dao), f, reopenedNative, reopenedNative.get(f.task.id)!!))
            val saved = OrezTaskStore(dao).load(f.plan.id)!!
            assertFalse(saved.resuming); assertNull(saved.pendingControl)
            assertEquals(resumed.generation, saved.steps[1].outputs["generation"])
            assertEquals(f.task.windows, reopenedNative.get(f.task.id)!!.windows)
            assertEquals(if (control == OrezPendingControl.CANCEL) SubtitleGenerationStatus.CANCELLED else SubtitleGenerationStatus.PAUSED,
                reopenedNative.get(f.task.id)!!.status)
        }
    }

    @Test fun nativeReplacementInsideStopCannotAcknowledgeOrPauseAnUnrelatedOwner() = runTest {
        val f = fixture(complete = false)
        val store = OrezTaskStore(Journal())
        store.checkpoint(runningPlan(f)); store.beginStop(f.plan.id, OrezPendingControl.PAUSE)
        var replacement: SubtitleGenerationTask? = null
        val allow = OrezSubtitleWorkGate.allow(store, f.plan.id, OrezSubtitleNativeEvidence.metadataReceipt(f.task, f.selection.sourceId),
            stop = { captured, _ ->
                replacement = f.native.start(f.task.source, f.task.config, ownerRequestId = "another-explicit-owner")
                f.native.pause(captured.taskId, captured.generation)?.let { OrezSubtitleNativeEvidence.metadataReceipt(it, f.selection.sourceId) }
            }, current = { id -> f.native.get(id)?.let { OrezSubtitleNativeEvidence.metadataReceipt(it, f.selection.sourceId) } })
        assertTrue(allow)
        assertNotEquals(f.task.generation, replacement!!.generation)
        assertEquals(SubtitleGenerationStatus.QUEUED, f.native.get(f.task.id)!!.status)
        assertEquals("another-explicit-owner", f.native.get(f.task.id)!!.ownerRequestId)
        assertTrue(f.native.current(f.task.id, replacement!!.generation))
        assertEquals(OrezPendingControl.PAUSE, store.load(f.plan.id)!!.pendingControl)
    }
}
