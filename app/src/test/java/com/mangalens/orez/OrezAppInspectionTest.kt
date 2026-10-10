package com.mangalens.orez

import com.mangalens.orez.agent.OrezCapability
import com.mangalens.orez.agent.OrezToolRegistry
import com.mangalens.orez.agent.OrezToolRisk
import org.junit.Assert.*
import org.junit.Test

class OrezAppInspectionTest {
    @Test fun explicitRequestsSelectReadOnlyTools() {
        assertEquals(OrezAppInspection.MODEL_STATUS, OrezAppInspection.parseExplicit("Please check model status!"))
        assertEquals(OrezAppInspection.APP_DIAGNOSTICS, OrezAppInspection.parseExplicit("Inspect app diagnostics."))
        OrezAppInspection.entries.forEach {
            val tool = OrezToolRegistry().call(it.toolName, emptyMap())
            assertEquals(OrezToolRisk.READ_ONLY, tool.risk)
            assertEquals(OrezCapability.SETTINGS, tool.capability)
            assertNull(tool.route)
        }
    }
    @Test fun quotationsCommandsAndUnrelatedQuestionsCannotTriggerInspection() {
        listOf("Translate check model status", "The website says check model status", "\"check model status\"",
            "check model status and download a model", "check model status https://example.com", "check model status\n",
            "check model status; delete data", "x".repeat(161)).forEach { assertNull(it, OrezAppInspection.parseExplicit(it)) }
    }
    @Test fun nativeRequestsAreNotInterceptedByNavigationPlanner() {
        val planner = com.mangalens.orez.agent.OrezAgentPlanner()
        listOf("show model status", "show app diagnostics", "check model status", "inspect app diagnostics")
            .forEach { assertNull(planner.plan(it, com.mangalens.orez.agent.OrezAgentContext())) }
    }
    @Test fun registryRejectsPathsAndMutationsInReadOnlyCalls() {
        OrezAppInspection.entries.forEach {
            assertTrue(runCatching { OrezToolRegistry().call(it.toolName, mapOf("path" to "/private")) }.isFailure)
            assertTrue(runCatching { OrezToolRegistry().call(it.toolName, mapOf("download" to "true")) }.isFailure)
        }
    }
    @Test fun installedIsNotReadyAndRawErrorsDoNotEnterReply() {
        val result = OrezAppInspectionFormatter.describe(OrezAppInspection.MODEL_STATUS,
            snapshot(OrezModelState(installed = true, ready = false, error = "private-secret-path")))
        assertTrue(result.contains("not currently ready"))
        assertFalse(result.contains("private-secret-path"))
        assertTrue(result.contains("inference did not run"))
    }
    @Test fun verificationAndTransferAreReportedWithoutCompletionClaims() {
        val result = OrezAppInspectionFormatter.describe(OrezAppInspection.MODEL_STATUS,
            snapshot(OrezModelState(verifying = true, installed = true, ready = true, downloading = true, bytes = -4, total = 100)))
        assertTrue(result.contains("verification is in progress"))
        assertTrue(result.contains("Downloaded: 0 bytes of 100"))
        assertTrue(result.contains("transfer progress"))
    }
    @Test fun diagnosticsContainOnlyCapturedScalarsAndTierNames() {
        val result = OrezAppInspectionFormatter.describe(OrezAppInspection.APP_DIAGNOSTICS,
            snapshot(OrezModelState(ready = true, selectedTier = OrezModelTier.CORE,
                activeTier = OrezModelTier.LITE, installedTiers = setOf(OrezModelTier.CORE, OrezModelTier.LITE))))
        assertTrue(result.contains("Android API 35"))
        assertTrue(result.contains("arm64-v8a"))
        assertTrue(result.contains("fallback candidate tier: Lite"))
        assertTrue(result.contains("installed tiers: Lite, Core"))
    }
    private fun snapshot(model: OrezModelState) = OrezAppInspectionSnapshot("test", 35,
        listOf("arm64-v8a"), "LOCAL_LITE", "BALANCED", model)
}
