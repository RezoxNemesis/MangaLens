package com.mangalens.orez.agent

import com.mangalens.orez.OrezTaskDao
import com.mangalens.orez.OrezTaskEntity
import com.mangalens.ui.video.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class OrezProviderCaptionWorkflowTest {
    @Test fun ordinaryEnglishRequestPrioritisesCapturedProviderInventoryBeforeAsr() = CaptionFixture().use { f ->
        val plan = requireNotNull(OrezAgentRuntime().decide("Generate English subtitles for this selected video",
            OrezAgentContext(selectedMedia = descriptor(f))).plan)
        assertEquals("The normal English request bypasses genuine captured captions", SubtitlePipeline.SOURCE_TRANSLATION,
            plan.authorization?.subtitle?.pipeline)
        assertEquals(f.inventory.fingerprint(), descriptor(f).providerCaptions?.fingerprint())
        assertEquals(setOf("sourceId", "sourceFingerprint", "speechModelSha256", "captionInventorySha256"), plan.steps[1].references.keys)
    }

    @Test fun realProviderProcessorExportsCompleteOrezExecutorAndRetainSourceStyleAndSceneAcrossReopen() = runBlocking {
        CaptionFixture().use { f ->
            val selected = descriptor(f)
            val options = f.config.orezOptions()
            val plan = requireNotNull(OrezAgentRuntime().decide("Generate English subtitles for this selected video",
                OrezAgentContext(selectedMedia = selected, subtitleOptions = options)).plan)
            val native = f.store()
            val host = object : OrezSubtitleHost {
                override suspend fun inspectSelection(selection: OrezMediaSelection, pinnedModelSha256: String?) =
                    OrezSubtitleSnapshot(selection.sourceId, selection, f.source.fingerprint, "")
                override suspend fun inspectDownload(download: OrezSubtitleDownload, pinnedModelSha256: String?) = error("not a download")
                override suspend fun start(media: OrezSubtitleSnapshot, options: OrezSubtitleOptions, requestId: String, allowReplacement: Boolean): OrezSubtitleReceipt {
                    assertEquals(selected, media.descriptor)
                    assertEquals(f.config, options.nativeConfig(media.speechModelSha256))
                    val task = native.start(f.source, options.nativeConfig(media.speechModelSha256), ownerRequestId = requestId)
                    assertTrue(ProviderCaptionProcessor(native, task, checkOwner = {
                        check(native.get(task.id)?.generation == task.generation && native.get(task.id)?.status != SubtitleGenerationStatus.CANCELLED)
                    }, fetch = { _, _, _ -> FetchedProviderCaptions(f.track, f.document) }, translate = { _, window, index ->
                        SubtitleTranslatedCue(index, window.sourceCues[index].text)
                    }).process())
                    return OrezSubtitleNativeEvidence.receipt(requireNotNull(native.get(task.id)), media.sourceId, f.directory, selected)
                }
                override suspend fun observe(taskId: String, requestId: String, media: OrezSubtitleSnapshot, options: OrezSubtitleOptions) =
                    native.get(taskId)?.let { OrezSubtitleNativeEvidence.receipt(it, media.sourceId, f.directory, selected) }
                override suspend fun findOwned(requestId: String) = native.states.value.firstOrNull { it.ownerRequestId == requestId }?.let {
                    OrezSubtitleNativeEvidence.receipt(it, selected.sourceId, f.directory, selected)
                }
                override suspend fun pause(receipt: OrezSubtitleReceipt): OrezSubtitleReceipt? = error("not a control")
                override suspend fun resume(receipt: OrezSubtitleReceipt): OrezSubtitleReceipt? = error("not a control")
                override suspend fun cancel(receipt: OrezSubtitleReceipt): OrezSubtitleReceipt? = error("not a control")
            }
            val dao = Journal(); val tasks = OrezTaskStore(dao)
            assertTrue(tasks.checkpoint(plan))
            assertTrue(OrezTaskExecutor(tasks, OrezSubtitleTools.forPlan(tasks, plan, host)).run(plan.id, plan.executionEpoch) is OrezTaskExecutor.Result.Completed)
            val reopened = requireNotNull(OrezTaskStore(dao).load(plan.id))
            assertEquals(OrezTaskStatus.COMPLETED, reopened.status)
            assertEquals(selected, reopened.authorization?.selectedMedia)
            assertEquals(options, reopened.authorization?.subtitle)
            val outputs = reopened.steps.last().outputs
            assertEquals("", outputs["speechModelSha256"])
            assertEquals("false", outputs["audioComplete"])
            assertEquals("5000", outputs["processedMs"])
            assertEquals("60000", outputs["durationMs"])
            assertEquals(f.document.payloadSha256, outputs["providerPayloadSha256"])
            assertFalse(outputs.values.any { it.contains(f.directory.path) || it.contains(f.track.url) })
            OrezDurablePlanRules.validate(reopened)
            val forged = reopened.copy(steps = reopened.steps.map { if (it.index == 1) it.copy(outputs = it.outputs + ("audioComplete" to "true")) else it })
            assertTrue(runCatching { OrezDurablePlanRules.validate(forged) }.isFailure)
        }
    }

    private fun descriptor(f: CaptionFixture) = OrezMediaSelection(f.source.source.uri, f.source.source.cacheKey, f.source.source.label,
        f.source.source.headers, f.source.source.sourceResolutionId, expectedDurationUs = 60_000_000, providerCaptions = f.inventory)
    private class Journal : OrezTaskDao {
        var row: OrezTaskEntity? = null
        override fun observeActive() = flowOf(listOfNotNull(row))
        override suspend fun get(id: String) = row?.takeIf { it.id == id }
        override suspend fun upsert(task: OrezTaskEntity) { row = task }
        override suspend fun pruneFinished(before: Long) = Unit
    }
}
