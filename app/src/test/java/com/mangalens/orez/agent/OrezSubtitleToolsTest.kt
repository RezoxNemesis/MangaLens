package com.mangalens.orez.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import com.mangalens.orez.OrezTaskDao
import com.mangalens.orez.OrezTaskEntity
import org.junit.Assert.*
import org.junit.Test

class OrezSubtitleToolsTest {
    private val selection = OrezMediaSelection("content://explicit/video", label = "Explicit clip")
    private val scope = OrezTaskAuthorization(OrezTrustOrigin.USER, true, selectedMedia = selection, subtitle = OrezSubtitleOptions(threads = 2))
    private val snapshot = OrezSubtitleSnapshot(selection.sourceId, selection, "a".repeat(64), "b".repeat(64))
    private val request = "orez-subtitle-step-1"
    private val exports = OrezSubtitleExports("d".repeat(64), "e".repeat(64), 80, 80)
    private val completed = OrezSubtitleReceipt("c".repeat(32), "1".repeat(32), request, snapshot, scope.subtitle!!,
        OrezNativeSubtitleStatus.COMPLETED, 16_000, 16_000, 2, 2, exports)
    private class Host(var media: OrezSubtitleSnapshot, var receipt: OrezSubtitleReceipt) : OrezSubtitleHost {
        var starts = 0; var replacement: Boolean? = null; var pauses = 0; var cancelled = 0; var inspections = 0
        var onStart: (suspend () -> Unit)? = null
        override suspend fun inspectSelection(selection: OrezMediaSelection, pinnedModelSha256: String?) = media.also { inspections++ }
        override suspend fun inspectDownload(download: OrezSubtitleDownload, pinnedModelSha256: String?) = media.also { inspections++ }
        override suspend fun start(media: OrezSubtitleSnapshot, options: OrezSubtitleOptions, requestId: String, allowReplacement: Boolean): OrezSubtitleReceipt {
            starts++; replacement = allowReplacement; onStart?.invoke(); return receipt
        }
        override suspend fun observe(taskId: String, requestId: String, media: OrezSubtitleSnapshot, options: OrezSubtitleOptions) = receipt.takeIf { it.taskId == taskId }
        override suspend fun findOwned(requestId: String) = receipt.takeIf { it.ownerRequestId == requestId }
        override suspend fun pause(receipt: OrezSubtitleReceipt) = receipt.copy(status = OrezNativeSubtitleStatus.PAUSED).also { pauses++; this.receipt = it }
        override suspend fun resume(receipt: OrezSubtitleReceipt) = receipt.copy(generation = "2".repeat(32), status = OrezNativeSubtitleStatus.QUEUED).also { this.receipt = it }
        override suspend fun cancel(receipt: OrezSubtitleReceipt) = receipt.copy(status = OrezNativeSubtitleStatus.CANCELLED).also { cancelled++; this.receipt = it }
    }
    private fun generate(status: OrezStepStatus = OrezStepStatus.PENDING, outputs: Map<String, String> = emptyMap()) = OrezPlanStep(1,
        OrezToolRegistry().call("generate_subtitles", mapOf("sourceId" to snapshot.sourceId,
            "sourceFingerprint" to snapshot.sourceFingerprint, "speechModelSha256" to snapshot.speechModelSha256, "targetLanguage" to "en")),
        status = status, outputs = outputs)

    @Test fun explicitInspectionProducesOnlyOpaqueVerifiedSourceAndModelEvidence() = runTest {
        val host = Host(snapshot, completed)
        val outcome = OrezSubtitleTools(host, scope).execute(OrezPlanStep(0,
            OrezToolRegistry().call("inspect_selected_media", mapOf("sourceId" to selection.sourceId))), "orez-subtitle")
        assertTrue(outcome is OrezToolResult.Completed)
        val values = (outcome as OrezToolResult.Completed).outputs
        assertEquals(selection.sourceId, values["sourceId"])
        assertEquals(snapshot.sourceFingerprint, values["sourceFingerprint"])
        assertEquals(snapshot.speechModelSha256, values["speechModelSha256"])
        assertFalse(values.values.any { it.contains("content://") })
        assertEquals(0, host.starts)
    }

    @Test fun completedNativeResultRequiresFullActualExportAndSourceEvidence() = runTest {
        val host = Host(snapshot, completed)
        val result = OrezSubtitleTools(host, scope).execute(generate(), request)
        assertTrue(result is OrezToolResult.Completed)
        assertEquals("subtitle-track:${completed.taskId}", (result as OrezToolResult.Completed).outputs["destination"])
        assertEquals("80", result.outputs["vttBytes"])
        assertEquals(true, host.replacement)
        for (bad in listOf(completed.copy(exports = null), completed.copy(validationPending = true), completed.copy(cueCount = 0),
            completed.copy(media = snapshot.copy(verifiable = false)), completed.copy(processedMs = 1000), completed.copy(generation = "1".repeat(36)))) {
            assertTrue("Invalid native provider receipt must fail", OrezSubtitleTools(Host(snapshot, bad), scope).execute(generate(), request) is OrezToolResult.Failed)
        }
    }

    @Test fun nativeOwnerSourceModelAndSettingsCannotBeSubstituted() = runTest {
        for (bad in listOf(completed.copy(ownerRequestId = "reader"), completed.copy(media = snapshot.copy(sourceFingerprint = "f".repeat(64))),
            completed.copy(media = snapshot.copy(speechModelSha256 = "f".repeat(64))), completed.copy(options = scope.subtitle!!.copy(threads = 4)),
            completed.copy(media = snapshot.copy(descriptor = selection.copy(uri = "content://gallery/not-selected"))))) {
            assertTrue(OrezSubtitleTools(Host(snapshot, bad), scope).execute(generate(), request) is OrezToolResult.Failed)
        }
    }

    @Test fun changedSourceOrUnverifiedRemoteSourceCannotDispatchNativeWork() = runTest {
        for (bad in listOf(snapshot.copy(sourceFingerprint = "f".repeat(64)), snapshot.copy(verifiable = false),
            snapshot.copy(descriptor = selection.copy(uri = "https://unselected.example/video")))) {
            val host = Host(bad, completed)
            assertTrue(OrezSubtitleTools(host, scope).execute(generate(), request) is OrezToolResult.Failed)
            assertEquals(0, host.starts)
        }
    }

    @Test fun runningReplayUsesNoReplacementAndRecordedGenerationCannotAdoptAnother() = runTest {
        val host = Host(snapshot, completed)
        assertTrue(OrezSubtitleTools(host, scope).execute(generate(OrezStepStatus.RUNNING), request) is OrezToolResult.Completed)
        assertEquals(false, host.replacement)
        val recorded = mapOf("requestId" to request, "subtitleTaskId" to completed.taskId, "generation" to "f".repeat(32))
        assertTrue(OrezSubtitleTools(host, scope).execute(generate(OrezStepStatus.RUNNING, recorded), request) is OrezToolResult.Failed)
        assertEquals(1, host.starts)
    }

    @Test fun pausedAndCancelledNativeStatesNeverClaimCompletion() = runTest {
        val paused = OrezSubtitleTools(Host(snapshot, completed.copy(status = OrezNativeSubtitleStatus.PAUSED)), scope).execute(generate(), request)
        assertTrue(paused is OrezToolResult.Pending && paused.needsResume)
        val cancelled = OrezSubtitleTools(Host(snapshot, completed.copy(status = OrezNativeSubtitleStatus.CANCELLED)), scope).execute(generate(), request)
        assertTrue(cancelled is OrezToolResult.Cancelled)
    }

    @Test fun aStoppedEpochCannotStartNativeWorkAndOsInterruptionKeepsOwnedWorkAlive() = runTest {
        val host = Host(snapshot, completed)
        val stopped = OrezSubtitleTools(host, scope, isExecuting = { false }).execute(generate(), request)
        assertTrue(stopped is OrezToolResult.Pending && stopped.needsResume)
        assertEquals(0, host.starts)
        host.onStart = { throw CancellationException("OS stopped orchestrator") }
        try { OrezSubtitleTools(host, scope, mayStopOwned = { false }).execute(generate(), request); fail("Expected cancellation") }
        catch (_: CancellationException) { }
        assertEquals(0, host.pauses)
    }

    @Test fun onlyThisPlansTypedPublishedDownloadCanSupplyTheSource() = runTest {
        val id = "orez-subtitle"
        val file = OrezSubtitleDownload(id, "https://example.com/video.mp4", "content://download/verified", 1200, "Verified video")
        val downloadedMedia = snapshot.copy(sourceId = "download-$id", descriptor = OrezMediaSelection(file.destination, "orez-download:$id", file.title))
        val host = Host(downloadedMedia, completed.copy(media = downloadedMedia))
        val authorization = scope.copy(selectedMedia = null, urls = setOf(file.sourceUrl))
        val step = OrezPlanStep(1, OrezToolRegistry().call("inspect_downloaded_media", mapOf("downloadId" to id)))
        assertTrue(OrezSubtitleTools(host, authorization, downloaded = { file.takeIf { it.id == id } }).execute(step, "orez-subtitle-step-1") is OrezToolResult.Completed)
        assertTrue(OrezSubtitleTools(host, authorization).execute(step, "orez-subtitle-step-1") is OrezToolResult.Failed)
        assertTrue(OrezSubtitleTools(host, authorization.copy(urls = emptySet()), downloaded = { file }).execute(step, "orez-subtitle-step-1") is OrezToolResult.Failed)
    }

    @Test fun boundWorkflowReloadsItsJustCompletedDownloadBeforeResolvingSubtitleDependency() = runTest {
        val dao = object : OrezTaskDao {
            var row: OrezTaskEntity? = null
            override fun observeActive() = flowOf(listOfNotNull(row))
            override suspend fun get(id: String) = row?.takeIf { it.id == id }
            override suspend fun upsert(task: OrezTaskEntity) { row = task }
            override suspend fun pruneFinished(before: Long) = Unit
        }
        val store = OrezTaskStore(dao)
        val source = "https://example.com/explicit.mp4"
        val plan = OrezAgentRuntime().decide("Download $source then generate English subtitles",
            OrezAgentContext(subtitleOptions = scope.subtitle!!)).plan!!
        val id = OrezDurablePlanRules.requestId(plan.id, 0)
        val file = OrezSubtitleDownload(id, source, "content://downloads/this-plan", 1200, "Own download")
        val media = snapshot.copy(sourceId = "download-$id", descriptor = OrezMediaSelection(file.destination, "orez-download:$id", file.title))
        val receipt = completed.copy(ownerRequestId = OrezDurablePlanRules.requestId(plan.id, 2), media = media)
        val host = Host(media, receipt)
        store.checkpoint(plan)
        val bound = OrezSubtitleTools.forPlan(store, plan, host)
        val executor = OrezTaskExecutor(store, OrezDurableTools { step, requestId ->
            if (step.call.name == "enqueue_download") OrezToolResult.Completed(mapOf("downloadId" to requestId, "destination" to file.destination,
                "title" to file.title, "bytes" to file.bytes.toString(), "storage" to "published-file"))
            else bound.execute(step, requestId)
        })
        assertTrue("The native dependency must load the saved completed prefix, not the worker's initial snapshot", executor.run(plan.id, plan.executionEpoch) is OrezTaskExecutor.Result.Completed)
        val saved = store.load(plan.id)!!
        assertEquals(OrezOutputKind.DOWNLOAD_RECEIPT, saved.steps[0].outputKind)
        assertEquals(OrezOutputKind.MEDIA_SOURCE, saved.steps[1].outputKind)
        assertEquals(OrezOutputKind.SUBTITLE_TRACK, saved.steps[2].outputKind)
        assertEquals("subtitle-track:${receipt.taskId}", saved.steps[2].outputs["destination"])
        assertEquals(1, host.starts)
    }
}
