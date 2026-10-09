package com.mangalens.ui.web

import org.junit.Assert.*
import org.junit.Test

class BrowserCapturePolicyTest {
    private val page = BrowserUploadScope("0123456789abcdef0123456789abcdef", 4, "https://example.com/video")

    @Test fun anExplicitCurrentPageCanRequestProjectionAndConsumeConsentOnlyOnce() {
        val gate = BrowserCaptureGate()
        assertTrue(gate.begin(page)); assertTrue(gate.mayProject(page))
        assertTrue(gate.finish(page)); assertFalse(gate.finish(page))
    }

    @Test fun aPermissionResultCannotAuthorizeAnotherTabOrNavigation() {
        for (replacement in listOf(page.copy(tabId = "abcdef0123456789abcdef0123456789"), page.copy(navigationEpoch = 5), null)) {
            val gate = BrowserCaptureGate()
            assertTrue(gate.begin(page)); assertFalse(gate.mayProject(replacement)); assertFalse(gate.finish(replacement))
            assertTrue(gate.begin(page))
        }
    }

    @Test fun stopOrPageDisposalRevokesEvenALateConsentForTheSameDocument() {
        val gate = BrowserCaptureGate()
        assertTrue(gate.begin(page)); gate.revoke()
        assertFalse("Revoked permission cannot launch Android projection", gate.mayProject(page))
        assertFalse("Revoked projection consent cannot start the capture service", gate.finish(page))
    }

    @Test fun aCancelledNavigationDoesNotLetANewerRequestStealItsOutstandingAndroidResult() {
        val gate = BrowserCaptureGate()
        assertTrue(gate.begin(page)); gate.revoke()
        val next = page.copy(navigationEpoch = 5)
        assertFalse(gate.begin(next)); assertFalse(gate.finish(next))
        assertTrue(gate.begin(next)); assertTrue(gate.finish(next))
    }

    @Test fun malformedOrFileBasedScopeCannotBecomeCaptureAuthority() {
        val gate = BrowserCaptureGate()
        assertFalse(gate.begin(page.copy(tabId = "prompt-selected-tab")))
        assertFalse(gate.begin(page.copy(navigationEpoch = 0)))
        assertFalse(gate.begin(page.copy(pageUrl = "file:///data/private")))
    }
}
