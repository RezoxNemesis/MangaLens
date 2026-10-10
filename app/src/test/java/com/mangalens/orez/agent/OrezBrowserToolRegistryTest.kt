package com.mangalens.orez.agent

import com.mangalens.ui.web.BrowserDomPolicy
import org.junit.Assert.*
import org.junit.Test

/** Authored controls; no execution claim is made in this preparation packet. */
class OrezBrowserToolRegistryTest {
    @Test fun browserMetadataCannotBeDowngradedByAModel() {
        val call = OrezToolRegistry().call("browser_click", mapOf("pageId" to "a".repeat(64), "elementId" to "b".repeat(32)))
        assertEquals(OrezCapability.WEB, call.capability)
        assertEquals(OrezToolRisk.NETWORK_MUTATION, call.risk)
        assertThrows(IllegalArgumentException::class.java) { OrezToolRegistry().validate(call.copy(risk = OrezToolRisk.READ_ONLY)) }
    }
    @Test fun scriptsSelectorsAndApprovalFlagsAreNotAcceptedArguments() {
        val r = OrezToolRegistry()
        for (key in listOf("javascript", "selector", "approved", "cookies")) {
            assertThrows(IllegalArgumentException::class.java) { r.call("browser_observe", mapOf("pageId" to "a".repeat(64), key to "payload")) }
        }
    }
    @Test fun credentialsAndNonHttpTargetsAreRejected() {
        for (url in listOf("https://user:pass@example.org/", "https://example.org/?token=secret", "javascript:alert(1)", "file:///tmp/a"))
            assertFalse(url, BrowserDomPolicy.permittedAddress(url))
        assertTrue(BrowserDomPolicy.permittedAddress("https://example.org/chapter?id=2"))
    }
    @Test fun extractedPageToolsCannotBecomeModelGeneratedDurableWork() {
        assertFalse(OrezToolRegistry().catalog().contains("browser_observe"))
        assertFalse(OrezDurablePlanRules.supports(OrezTaskPlan(objective = "Read this page", steps = listOf(
            OrezPlanStep(0, OrezToolRegistry().call("browser_observe", mapOf("pageId" to "a".repeat(64))))))))
    }
}
