package com.mangalens.ui.web

import org.junit.Assert.*
import org.junit.Test

class BrowserWorkspaceNavigationTest {
    private val a = BrowserTab("0123456789abcdef0123456789abcdef", listOf("https://example.com/1", "https://example.com/2"), 1)
    private val b = BrowserTab("abcdef0123456789abcdef0123456789", listOf("https://example.org/other"), 0)
    private val snapshot = BrowserWorkspaceSnapshot(listOf(a, b), a.id)

    @Test fun genuineIncomingUrlNavigatesTheCurrentTabWhileExistingUrlSelectsItsTab() {
        assertEquals(BrowserIncomingDecision(BrowserIncomingAction.NAVIGATE, a.id, "https://example.com/external"), planBrowserIncoming(snapshot, "https://example.com/external"))
        assertEquals(BrowserIncomingDecision(BrowserIncomingAction.SELECT, b.id, b.url), planBrowserIncoming(snapshot, b.url))
    }

    @Test fun blankOrReportedParentEchoPreservesRestoredSelectionIncludingBlankTab() {
        val blank = b.copy(entries = emptyList(), position = -1)
        val restored = snapshot.copy(tabs = listOf(a, blank), activeTabId = blank.id, lastReportedUrl = a.url)
        assertEquals(BrowserIncomingAction.IGNORE, planBrowserIncoming(restored, "").action)
        assertEquals(BrowserIncomingAction.IGNORE, planBrowserIncoming(restored, a.url).action)
        assertEquals(BrowserIncomingAction.IGNORE, planBrowserIncoming(snapshot, a.url).action)
    }

    @Test fun unsafeIncomingAuthorityNeverBecomesASelectedTabOrDispatch() {
        for (uri in listOf("javascript:alert(1)", "content://gallery/image", "https://user:password@example.com", "https://example.com/x\n")) {
            assertEquals(BrowserIncomingAction.REJECT, planBrowserIncoming(snapshot, uri).action)
        }
    }

    @Test fun coldBackUsesThePersistedSafeCursorWhenNativeHistoryIsEmpty() {
        assertEquals(BrowserHistoryDispatch(a.entries.first(), null), planBrowserHistory(a, -1, emptyList(), -1))
        assertNull(planBrowserHistory(a, 1, emptyList(), -1))
        assertNull(planBrowserHistory(a, -2, emptyList(), -1))
    }

    @Test fun matchingNativeBackOrForwardIsUsedWithoutReloadingAnotherEntry() {
        assertEquals(BrowserHistoryDispatch(a.entries.first(), -1), planBrowserHistory(a, -1, a.entries, 1))
        val back = a.copy(position = 0)
        assertEquals(BrowserHistoryDispatch(a.entries.last(), 1), planBrowserHistory(back, 1, a.entries, 0))
        assertEquals(BrowserHistoryDispatch(a.entries.first(), null), planBrowserHistory(a, -1, listOf("https://foreign.example/other", a.url), 1))
    }

    @Test fun historyCannotDispatchAnInvalidSavedUrlEvenIfNativeListMatches() {
        val corrupted = a.copy(entries = listOf("file:///data/private", a.url))
        assertNull(planBrowserHistory(corrupted, -1, corrupted.entries, 1))
    }

    @Test fun initialHistoricalParentUrlCannotReplaceAColdRestoredCurrentPage() {
        val restored = snapshot.copy(activeTabId = b.id, lastReportedUrl = b.url)
        assertEquals(BrowserIncomingAction.IGNORE, planBrowserIncoming(restored, a.entries.first(), initial = true).action)
        assertEquals(BrowserIncomingAction.NAVIGATE, planBrowserIncoming(restored, a.entries.first(), initial = false).action)
    }

    @Test fun desktopUserAgentRetainsActualChromeVersionAndRemovesTheMobilePlatform() {
        val mobile = "Mozilla/5.0 (Linux; Android 15; Pixel 8; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/129.0.6668.100 Mobile Safari/537.36"
        val desktop = browserDesktopUserAgent(mobile)
        assertTrue(desktop.contains("X11; Linux x86_64"))
        assertTrue(desktop.contains("Chrome/129.0.6668.100"))
        assertFalse(desktop.contains("Android")); assertFalse(desktop.contains("Mobile")); assertFalse(desktop.contains("wv"))
    }
}
