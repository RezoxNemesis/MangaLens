package com.mangalens.orez

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OrezResourceRoutingTest {
    private val lite = candidate(OrezModelCatalog.lite.id, OrezModelCatalog.lite.sha256, OrezModelCatalog.lite.bytes)
    private val core = candidate(OrezModelCatalog.core.id, OrezModelCatalog.core.sha256, OrezModelCatalog.core.bytes)
    private val legacy = candidate("legacy-qwen2.5-0.5b-q6_k", "b".repeat(64), 650_379_104)
    private fun candidate(id: String, sha: String, bytes: Long) = OrezModelCandidate(File("/verified/$id"), OrezModelPin(id, sha, bytes))
    private fun request(mode: OrezResourceMode, task: OrezModelTask = OrezModelTask.CHAT,
        pressure: OrezRoutingPressure = OrezRoutingPressure.NORMAL) = OrezRequestResources(mode, task, pressure)
    private fun route(mode: OrezResourceMode, task: OrezModelTask = OrezModelTask.CHAT,
        selected: List<OrezModelCandidate> = listOf(core, lite, legacy),
        pool: List<OrezModelCandidate> = listOf(core, lite, legacy),
        pressure: OrezRoutingPressure = OrezRoutingPressure.NORMAL) =
        OrezResourceRouting.order(null, selected, pool, request(mode, task, pressure))

    @Test fun balancedNormalPreservesSelectedCoreAndEveryExistingFallback() {
        assertEquals(listOf(core, lite, legacy), route(OrezResourceMode.BALANCED))
    }
    @Test fun balancedLiteSelectionCannotSilentlyUpgradeToInstalledCore() {
        assertEquals(listOf(lite, legacy), route(OrezResourceMode.BALANCED, selected = listOf(lite, legacy)))
    }
    @Test fun balancedAllTaskHintsPreserveNormalExistingOrder() {
        OrezModelTask.entries.forEach { assertEquals(listOf(core, lite, legacy), route(OrezResourceMode.BALANCED, it)) }
    }
    @Test fun fastStartsWithSmallestVerifiedInstalledWeights() {
        assertEquals(listOf(lite, legacy, core), route(OrezResourceMode.FAST))
    }
    @Test fun fastNeverInventsAWeightOrDownloadsWhenSmallModelIsMissing() {
        assertEquals(listOf(core), route(OrezResourceMode.FAST, pool = listOf(core), selected = listOf(core)))
    }
    @Test fun maximumRoutineChatStillUsesSmallInstalledModelFirst() {
        assertEquals(listOf(lite, legacy, core), route(OrezResourceMode.MAXIMUM))
    }
    @Test fun maximumTrustedContextAndPlanningKindsCanUseInstalledCoreOutsideLiteSelection() {
        listOf(OrezModelTask.REASONING, OrezModelTask.LOCALIZATION, OrezModelTask.CHAPTER_REASONING, OrezModelTask.LIBRARY_REASONING,
            OrezModelTask.TOOL_PLANNING, OrezModelTask.BROWSER_GOAL, OrezModelTask.SAVED_BUBBLE,
            OrezModelTask.EVIDENCE_SYNTHESIS).forEach {
            assertEquals(listOf(core, lite, legacy), route(OrezResourceMode.MAXIMUM, it, selected = listOf(lite, legacy)))
        }
    }
    @Test fun maximumWithNoCoreFallsBackOnlyToActuallyVerifiedInstalledArtifacts() {
        assertEquals(listOf(lite, legacy), route(OrezResourceMode.MAXIMUM, OrezModelTask.CHAPTER_REASONING,
            pool = listOf(legacy, lite), selected = listOf(lite, legacy)))
        assertNull(OrezModelCatalog.descriptor(OrezModelTier.MAX))
    }
    @Test fun everyModeUsesLighterExistingOptionsDuringElevatedOrCriticalPressure() {
        OrezResourceMode.entries.forEach { mode ->
            listOf(OrezRoutingPressure.ELEVATED, OrezRoutingPressure.CRITICAL).forEach {
                assertEquals(listOf(lite, legacy, core), route(mode, OrezModelTask.CHAPTER_REASONING, pressure = it))
            }
        }
    }
    @Test fun elevatedBalancedLiteSelectionStillCannotUpgradeToCore() {
        assertEquals(listOf(lite, legacy), route(OrezResourceMode.BALANCED, selected = listOf(lite, legacy),
            pressure = OrezRoutingPressure.ELEVATED))
    }
    @Test fun everyModeTaskAndPressureResolvesCapturedCoreExactly() {
        OrezResourceMode.entries.forEach { mode -> OrezModelTask.entries.forEach { task ->
            OrezRoutingPressure.entries.forEach { pressure ->
                assertEquals(listOf(core), OrezResourceRouting.order(core.pin, listOf(lite), listOf(lite, core, legacy),
                    request(mode, task, pressure)))
            }
        } }
    }
    @Test fun absentCapturedCoreCannotBorrowLiteEvenInFastMode() {
        assertTrue(OrezResourceRouting.order(core.pin, listOf(lite), listOf(lite), request(OrezResourceMode.FAST)).isEmpty())
    }
    @Test fun capturedIdentityRequiresShaAndBytesAsWellAsModelId() {
        val substituted = core.copy(pin = core.pin.copy(sha256 = "c".repeat(64)))
        assertTrue(OrezResourceRouting.order(core.pin, listOf(substituted), listOf(substituted),
            request(OrezResourceMode.MAXIMUM, OrezModelTask.TOOL_PLANNING)).isEmpty())
    }
    @Test fun invalidCandidateAndInvalidCapturedPinNeverGainEligibility() {
        val invalid = lite.copy(pin = lite.pin.copy(sha256 = "invalid"))
        assertEquals(listOf(core), route(OrezResourceMode.FAST, selected = listOf(core), pool = listOf(invalid, core)))
        assertTrue(OrezResourceRouting.order(invalid.pin, listOf(core), listOf(core), request(OrezResourceMode.FAST)).isEmpty())
    }
    @Test fun staleSelectedArtifactAbsentFromVerifiedPoolIsRemoved() {
        assertEquals(listOf(lite), route(OrezResourceMode.BALANCED, selected = listOf(core, lite), pool = listOf(lite)))
    }
    @Test fun invalidAndMissingPreferenceValuesUseBalanced() {
        listOf(null, "", "MAX", "Maximum", "page_instruction", "FAST ").forEach {
            assertEquals(OrezResourceMode.BALANCED, OrezResourceRouting.parseMode(it))
        }
        assertEquals(OrezResourceMode.FAST, OrezResourceRouting.parseMode("FAST"))
    }
    @Test fun changingTrustedTaskKeepsAlreadyCapturedModeAndPressure() {
        val captured = request(OrezResourceMode.FAST, pressure = OrezRoutingPressure.ELEVATED)
        val contextual = captured.forTask(OrezModelTask.BROWSER_GOAL)
        assertEquals(captured.mode, contextual.mode); assertEquals(captured.pressure, contextual.pressure)
        assertEquals(OrezModelTask.BROWSER_GOAL, contextual.task)
        assertEquals(OrezModelTask.CHAT, captured.task)
    }
}
