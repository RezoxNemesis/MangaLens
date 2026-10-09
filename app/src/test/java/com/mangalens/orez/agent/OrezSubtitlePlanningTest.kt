package com.mangalens.orez.agent

import com.mangalens.orez.OrezTaskDao
import com.mangalens.orez.OrezTaskEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** Reconstructed from retained pre-restart bytecode; independently recompiled and rerun. */
class OrezSubtitlePlanningTest {
    private val media = OrezMediaSelection("content://explicit-video/selected", label = "Selected clip",
        headers = mapOf("Authorization" to "private-token"))
    private val options = OrezSubtitleOptions(threads = 2)
    private val sourceHash = "a".repeat(64)
    private val modelHash = "b".repeat(64)
    private val nativeId = "c".repeat(32)
    private class Journal : OrezTaskDao {
        var row: OrezTaskEntity? = null
        override fun observeActive() = flowOf(listOfNotNull(row))
        override suspend fun get(id: String) = row?.takeIf { it.id == id }
        override suspend fun upsert(task: OrezTaskEntity) { row = task }
        override suspend fun pruneFinished(before: Long) = Unit
    }
    private fun selectedPlan() = OrezAgentRuntime().decide("Generate subtitles for this video in English",
        OrezAgentContext(selectedMedia = media, subtitleOptions = options)).plan!!
    private fun inspected(plan: OrezTaskPlan) = mapOf("requestId" to OrezDurablePlanRules.requestId(plan.id, 0),
        "sourceId" to media.sourceId, "sourceFingerprint" to sourceHash, "speechModelSha256" to modelHash, "title" to media.label)
    private fun prepared() = selectedPlan().let { plan -> plan.copy(steps = plan.steps.map {
        if (it.index == 0) it.copy(status = OrezStepStatus.COMPLETED, outputs = inspected(plan), outputKind = OrezOutputKind.MEDIA_SOURCE) else it
    }) }
    private fun completed(plan: OrezTaskPlan): Map<String, String> {
        val id = OrezDurablePlanRules.requestId(plan.id, 1)
        return inspected(plan) + mapOf("requestId" to id, "ownerRequestId" to id, "subtitleTaskId" to nativeId,
            "generation" to "1".repeat(32), "targetLanguage" to "en", "sourceLanguage" to "auto", "status" to "COMPLETED",
            "durationMs" to "16000", "processedMs" to "16000", "windowCount" to "2", "cueCount" to "2",
            "srtSha256" to "d".repeat(64), "vttSha256" to "e".repeat(64), "srtBytes" to "80", "vttBytes" to "80",
            "destination" to "subtitle-track:$nativeId")
    }

    @Test fun nativeSubtitleToolsAreCapturedScopeToolsAndExcludedFromModelCatalog() {
        val call = OrezToolRegistry().call("inspect_selected_media", mapOf("sourceId" to media.sourceId))
        assertNull(call.route); assertEquals(OrezToolRisk.READ_ONLY, call.risk); assertEquals(OrezCapability.MEDIA, call.capability)
        for (name in listOf("inspect_selected_media", "inspect_downloaded_media", "generate_subtitles"))
            assertFalse(OrezToolRegistry().catalog().contains(name))
    }

    @Test fun explicitSelectionBuildsRealTwoStepEnglishWorkflowAndCapturesHeaders() {
        val headers = mutableMapOf("Authorization" to "captured-token")
        val result = OrezAgentRuntime().decide("Generate subtitles for this video in English", OrezAgentContext(
            selectedMedia = media.copy(headers = headers), subtitleOptions = options, translationOptions = OrezTranslationOptions("hi-latn")))
        assertFalse(result.continueToBrain); assertNull(result.immediateRoute)
        val plan = result.plan!!
        assertEquals(listOf("inspect_selected_media", "generate_subtitles"), plan.steps.map { it.call.name })
        headers["Authorization"] = "later-token"
        assertEquals("captured-token", plan.authorization!!.selectedMedia!!.headers["Authorization"])
        assertEquals(options, plan.authorization!!.subtitle)
        assertEquals(plan.authorization!!.selectedMedia!!.sourceId, plan.steps[0].call.arguments["sourceId"])
        assertFalse(plan.steps.flatMap { it.call.arguments.values }.any { it.contains("captured-token") || it.contains("content://") })
        OrezDurablePlanRules.validate(plan)
    }

    @Test fun absentSelectionDoesNotPromoteAUrlOrFilesystemTextToSourceAuthority() {
        for (request in listOf("Generate subtitles for this video", "Generate subtitles for content://gallery/42",
            "Generate subtitles for https://example.com/video.mp4")) {
            val result = OrezAgentRuntime().decide(request, OrezAgentContext(activeUrl = "https://example.com/source-page"))
            assertNull(result.plan); assertNull(result.immediateRoute); assertFalse(result.continueToBrain)
            assertTrue(result.message.contains("select", ignoreCase = true))
        }
    }

    @Test fun unsupportedJapaneseSubtitleRequestFailsRatherThanClaimingEnglishAsJapanese() {
        val result = OrezAgentRuntime().decide("Generate subtitles for this video in Japanese", OrezAgentContext(selectedMedia = media))
        assertFalse(result.continueToBrain); assertEquals(OrezTaskStatus.FAILED, result.plan!!.status)
        assertTrue(result.message.contains("Unsupported", ignoreCase = true))
    }

    @Test fun downloadThenSubtitlePlanReferencesItsOwnExactDownloadAndPinnedMedia() {
        val url = "https://example.com/clip.mp4"
        val plan = OrezAgentRuntime().decide("Download $url at 720p then generate English subtitles", OrezAgentContext()).plan!!
        assertEquals(listOf("enqueue_download", "inspect_downloaded_media", "generate_subtitles"), plan.steps.map { it.call.name })
        assertEquals(setOf(url), plan.authorization!!.urls)
        assertEquals("P720", plan.steps[0].call.arguments["quality"])
        assertEquals(OrezOutputReference(0, OrezOutputField.DOWNLOAD_ID), plan.steps[1].references["downloadId"])
        assertEquals(OrezOutputReference(1, OrezOutputField.MEDIA_SOURCE_ID), plan.steps[2].references["sourceId"])
        assertEquals(OrezOutputReference(1, OrezOutputField.SPEECH_MODEL_SHA256), plan.steps[2].references["speechModelSha256"])
        OrezDurablePlanRules.validate(plan)
    }

    @Test fun mediaAuthorizationAndTypedGraphSurviveActualJournalRoundtrip() = runTest {
        val plan = selectedPlan(); val journal = Journal()
        assertTrue(OrezTaskStore(journal).checkpoint(plan))
        val reopened = OrezTaskStore(journal).load(plan.id)!!
        assertEquals(plan, reopened); assertEquals(media, reopened.authorization!!.selectedMedia); assertEquals(options, reopened.authorization!!.subtitle)
        OrezDurablePlanRules.validate(reopened)
    }

    @Test fun capturedImportedOriginCannotRunSubtitleInspectionAfterRestart() = runTest {
        val plan = selectedPlan().let { it.copy(authorization = it.authorization!!.copy(origin = OrezTrustOrigin.IMPORTED_CONTENT, explicitUserRequest = false)) }
        val store = OrezTaskStore(Journal()); store.checkpoint(plan); var calls = 0
        val result = OrezTaskExecutor(store, OrezDurableTools { _, id -> calls++; OrezToolResult.Completed(inspected(plan) + ("requestId" to id)) }).run(plan.id)
        assertTrue(result is OrezTaskExecutor.Result.Failed); assertEquals(0, calls)
    }

    @Test fun verifiedMediaReferenceResolvesOpaqueSourceAndActualPinnedModel() {
        val plan = prepared(); OrezDurablePlanRules.validate(plan)
        val resolved = OrezDurablePlanRules.resolve(plan, plan.steps[1])
        assertEquals(media.sourceId, resolved.call.arguments["sourceId"]); assertEquals(sourceHash, resolved.call.arguments["sourceFingerprint"])
        assertEquals(modelHash, resolved.call.arguments["speechModelSha256"]); assertFalse(resolved.call.arguments.containsKey("uri"))
    }

    @Test fun completionRequiresOwnedHexGenerationAndBothVerifiedExportReceipts() {
        val plan = prepared(); val resolved = OrezDurablePlanRules.resolve(plan, plan.steps[1])
        OrezDurablePlanRules.validateReceipt(plan, resolved, completed(plan), completed = true)
        for (bad in listOf(mapOf("status" to "PARTIAL"), mapOf("cueCount" to "0"), mapOf("vttSha256" to ""),
            mapOf("generation" to "1".repeat(36)), mapOf("ownerRequestId" to "someone-else"), mapOf("processedMs" to "1000"))) {
            assertTrue("Invalid native receipt must fail: $bad", runCatching {
                OrezDurablePlanRules.validateReceipt(plan, resolved, completed(plan) + bad, completed = true)
            }.isFailure)
        }
    }

    @Test fun modelCannotInventMediaWorkEvenWithAnOtherwiseValidSelection() {
        assertNull(OrezModelPlanDecoder().decode("""{"tool":"generate_subtitles","arguments":{"targetLanguage":"en"}}""",
            "Translate this video into English", OrezAgentContext(selectedMedia = media)))
    }

    @Test fun multipleDownloadSourcesAndMismatchedCurrentSelectionNeverBecomeAChatFallback() {
        val runtime = OrezAgentRuntime()
        val multiple = runtime.decide("Download https://example.com/one.mp4 and https://example.com/two.mp4 then generate subtitles", OrezAgentContext())
        assertFalse(multiple.continueToBrain); assertNull(multiple.plan); assertTrue(multiple.message.contains("one", ignoreCase = true))
        val foreign = runtime.decide("Generate subtitles for https://example.com/foreign.mp4", OrezAgentContext(selectedMedia = media))
        assertFalse(foreign.continueToBrain); assertNull(foreign.plan); assertTrue(foreign.message.contains("select", ignoreCase = true))
    }
}
