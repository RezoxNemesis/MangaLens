package com.mangalens.orez.agent

import com.mangalens.core.translation.TranslationStyleProfile
import com.mangalens.orez.OrezTaskDao
import com.mangalens.orez.OrezTaskEntity
import com.mangalens.orez.agent.OrezSubtitleTools.Companion.outputs
import com.mangalens.ui.video.SubtitleGenerationConfig
import com.mangalens.ui.video.SubtitleGenerationStatus
import com.mangalens.ui.video.SubtitleGenerationTask
import com.mangalens.ui.video.SubtitleMediaSource
import com.mangalens.ui.video.SubtitleOutputMode
import com.mangalens.ui.video.SubtitleRefinementPin
import com.mangalens.ui.video.SubtitleSourceIdentity
import com.mangalens.ui.video.SubtitleTargetOptions
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OrezSubtitleTargetPlanningTest {
    private val selected = OrezMediaSelection("file:///selected.mp4")
    private val model = "b".repeat(64)
    private class Journal : OrezTaskDao {
        var row: OrezTaskEntity? = null
        override fun observeActive() = flowOf(listOfNotNull(row))
        override suspend fun get(id: String) = row?.takeIf { it.id == id }
        override suspend fun upsert(task: OrezTaskEntity) { row = task }
        override suspend fun pruneFinished(before: Long) = Unit
    }
    private fun options() = SubtitleGenerationConfig(modelSha256 = model, threads = 2).withTarget(
        SubtitleTargetOptions("hi-latn", SubtitleOutputMode.DUAL, "custom", "Keep short, serious dialogue.", true,
            SubtitleRefinementPin("local-style-model", "f".repeat(64), 2_000_000),
            TranslationStyleProfile("custom", "Captured custom", "Keep short, serious dialogue.", false, false, false))).orezOptions()

    @Test fun fullCapturedTargetAndStyleAndModelScopeSurvivesDurableReopen() = runTest {
        val base = OrezAgentRuntime().decide("Generate subtitles for this selected video", OrezAgentContext(selectedMedia = selected)).plan!!
        val captured = options()
        val plan = base.copy(authorization = base.authorization!!.copy(subtitle = captured), steps = base.steps.map {
            if (it.call.name == "generate_subtitles") it.copy(call = it.call.copy(arguments = mapOf("targetLanguage" to captured.targetLanguage))) else it
        })
        val dao = Journal(); val store = OrezTaskStore(dao)
        assertTrue(store.checkpoint(plan))
        val reopened = OrezTaskStore(dao).load(plan.id)!!
        assertEquals(captured, reopened.authorization!!.subtitle)
        assertEquals(3, JSONObject(dao.row!!.planJson).getInt("schema"))
        assertEquals(captured.nativeConfig(model).fingerprint(), reopened.authorization.subtitle!!.nativeConfig(model).fingerprint())
        OrezDurablePlanRules.validate(reopened)
    }

    @Test fun explicitHindiAndHinglishUseSourceTranslationWithCapturedDualMode() {
        for ((name, target) in listOf("Hindi" to "hi", "Hinglish" to "hi-latn", "Roman Hindi" to "hi-latn")) {
            val decision = OrezAgentRuntime().decide("Generate dual subtitles in $name for this selected video",
                OrezAgentContext(selectedMedia = selected))
            assertEquals(decision.message, OrezTaskStatus.RUNNING, decision.plan!!.status)
            val captured = decision.plan.authorization!!.subtitle!!
            assertEquals(target, captured.targetLanguage)
            assertEquals(SubtitleOutputMode.DUAL, captured.outputMode)
            assertEquals("SOURCE_TRANSLATION", captured.pipeline.name)
            assertEquals("mlkit-dialogue-v1", captured.translationPolicy)
            assertEquals(TranslationStyleProfile.NATURAL, captured.capturedStyle)
        }
    }

    @Test fun actualNativeReceiptFitsBoundAndFullConfigurationIsValidated() {
        val plan = OrezAgentRuntime().decide("Generate English subtitles for this selected video",
            OrezAgentContext(selectedMedia = selected, subtitleOptions = OrezSubtitleOptions(threads = 2))).plan!!
        val task = SubtitleGenerationTask("a".repeat(32), "c".repeat(32),
            SubtitleSourceIdentity(SubtitleMediaSource(selected.uri), "d".repeat(64)),
            SubtitleGenerationConfig(modelSha256 = model, threads = 2), SubtitleGenerationStatus.RUNNING,
            ownerRequestId = OrezDurablePlanRules.requestId(plan.id, 1))
        val receipt = OrezSubtitleNativeEvidence.metadataReceipt(task, selected.sourceId)
        val inspected = plan.copy(steps = listOf(plan.steps[0].copy(status = OrezStepStatus.COMPLETED,
            outputKind = OrezOutputKind.MEDIA_SOURCE, outputs = receipt.media.outputs(OrezDurablePlanRules.requestId(plan.id, 0))), plan.steps[1]))
        val step = OrezDurablePlanRules.resolve(inspected, inspected.steps[1])
        val outputs = receipt.outputs(OrezDurablePlanRules.requestId(plan.id, 1))
        assertTrue("The actual extended receipt needs more than the old 20-field bound", outputs.size > 20)
        OrezDurablePlanRules.validateReceipt(inspected, step, outputs, completed = false)
        assertThrows(IllegalArgumentException::class.java) {
            OrezDurablePlanRules.validateReceipt(inspected, step, outputs + ("configFingerprint" to "e".repeat(64)), completed = false)
        }
    }

    @Test fun schemaTwoLegacyDefaultsRestoreWithoutInventingNewPolicy() = runTest {
        val plan = OrezAgentRuntime().decide("Generate English subtitles for this selected video",
            OrezAgentContext(selectedMedia = selected, subtitleOptions = OrezSubtitleOptions(threads = 2))).plan!!
        val dao = Journal(); val store = OrezTaskStore(dao); store.checkpoint(plan)
        val root = JSONObject(dao.row!!.planJson).put("schema", 2)
        val options = root.getJSONObject("authorization").getJSONObject("subtitle")
        for (key in listOf("outputMode", "pipeline", "customStyle", "localRefinement", "translationPolicy", "capturedStyle", "refinementPin")) options.remove(key)
        val loaded = store.decode(root.toString())
        assertEquals(OrezSubtitleOptions(threads = 2), loaded.authorization!!.subtitle)
        OrezDurablePlanRules.validate(loaded)
    }

    @Test fun everyFullProfileAndRefinementPinFieldChangesActualConfigFingerprint() {
        val captured = options()
        val profile = captured.capturedStyle!!
        val refinement = captured.refinementPin!!
        val changed = listOf(captured.copy(targetLanguage = "hi"), captured.copy(outputMode = SubtitleOutputMode.TRANSLATED),
            captured.copy(capturedStyle = profile.copy(name = "Different name")),
            captured.copy(capturedStyle = profile.copy(instruction = "Different instruction"), customStyle = "Different instruction"),
            captured.copy(capturedStyle = profile.copy(preserveHonorifics = true)),
            captured.copy(capturedStyle = profile.copy(preserveNames = true)),
            captured.copy(capturedStyle = profile.copy(naturalDialogue = true)),
            captured.copy(refinementPin = refinement.copy(modelId = "another-model")),
            captured.copy(refinementPin = refinement.copy(sha256 = "e".repeat(64))),
            captured.copy(refinementPin = refinement.copy(bytes = 3_000_000)))
        for (other in changed) assertNotEquals(captured.nativeConfig(model).fingerprint(), other.nativeConfig(model).fingerprint())
    }
}
