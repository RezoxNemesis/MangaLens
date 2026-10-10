package com.mangalens.ui.home

import com.mangalens.ui.web.BrowserTab
import com.mangalens.ui.web.BrowserVisit
import com.mangalens.ui.web.BrowserWorkspaceSnapshot
import org.junit.Assert.*
import org.junit.Test

class HomeBrowserShortcutPolicyTest {
    private fun state(vararg visits: BrowserVisit) = BrowserWorkspaceSnapshot(listOf(BrowserTab("tab")), "tab", visits.toList())

    @Test fun sitesChooseNewestActualVisitPerHostAndKeepItsOriginalQuery() {
        val older = BrowserVisit("https://example.org/old", "Old", 1)
        val latest = BrowserVisit("https://EXAMPLE.org/watch?v=public-id", "New", 3)
        val other = BrowserVisit("https://other.org/", "Other", 2)
        val result = HomeBrowserShortcutPolicy.rows(state(older, latest, other), "", true)
        assertEquals(listOf(latest, other), result)
        assertEquals("example.org", HomeBrowserShortcutPolicy.host(latest))
        assertEquals("https://EXAMPLE.org/watch?v=public-id", result.first().url)
    }

    @Test fun untrustedCredentialAndNonwebRowsCannotBecomeShortcuts() {
        val visits = listOf(BrowserVisit("file:///private", "Private", 1),
            BrowserVisit("javascript:alert(1)", "Script", 2), BrowserVisit("https://user:secret@example.org/", "Credential", 3))
        assertTrue(HomeBrowserShortcutPolicy.rows(state(*visits.toTypedArray()), "", false).isEmpty())
    }

    @Test fun removedOrReplacedVisitCannotBeOpenedFromAnOldRow() {
        val old = BrowserVisit("https://example.org/a", "Name", 1)
        assertTrue(HomeBrowserShortcutPolicy.isCurrent(state(old), old))
        assertFalse(HomeBrowserShortcutPolicy.isCurrent(state(), old))
        assertFalse(HomeBrowserShortcutPolicy.isCurrent(state(old.copy(visitedAt = 2)), old))
        assertFalse(HomeBrowserShortcutPolicy.isCurrent(state(old.copy(url = "https://example.org/b")), old))
    }

    @Test fun oldSiteRepresentativeRetiresWhenANewerSameHostVisitAppears() {
        val old = BrowserVisit("https://example.org/old", "Old", 1)
        val latest = BrowserVisit("https://example.org/new", "New", 2)
        assertTrue(HomeBrowserShortcutPolicy.rows(state(old), "", true).contains(old))
        assertFalse(HomeBrowserShortcutPolicy.rows(state(old, latest), "", true).contains(old))
        assertTrue(HomeBrowserShortcutPolicy.rows(state(old, latest), "Old", true).contains(old))
    }

    @Test fun unicodeMetadataQueryFiltersBeforeSiteGrouping() {
        val wanted = BrowserVisit("https://example.org/older", "日本語 Story", 1)
        val newer = BrowserVisit("https://example.org/newer", "Other", 2)
        assertEquals(listOf(wanted), HomeBrowserShortcutPolicy.rows(state(wanted, newer), "Ｓｔｏｒｙ", true))
        assertEquals(listOf(wanted), HomeBrowserShortcutPolicy.rows(state(wanted, newer), "日本語", false))
    }

    @Test fun outputIsBoundedAndHistoryDoesNotCollapseDistinctPages() {
        val visits = (1..30).map { BrowserVisit("https://example.org/$it", "Page $it", it.toLong()) }
        assertEquals(6, HomeBrowserShortcutPolicy.rows(state(*visits.toTypedArray()), "", false).size)
        assertEquals(1, HomeBrowserShortcutPolicy.rows(state(*visits.toTypedArray()), "", true).size)
    }

    @Test fun oldHomeLayoutAddsAllOptionalModulesHiddenWithoutChangingOrder() {
        val old = """{"version":1,"order":["orez_ai","quick_actions","recent_manga"],"hidden":["quick_actions"]}"""
        val restored = HomeLayoutPolicy.decode(old)
        assertEquals(listOf(HomeModule.OREZ_AI, HomeModule.QUICK_ACTIONS, HomeModule.RECENT_MANGA), restored.order.take(3))
        assertTrue(restored.hidden.containsAll(listOf(HomeModule.RECENT_VIDEO, HomeModule.BROWSER_HISTORY,
            HomeModule.RECENT_SITES, HomeModule.OREZ_SUGGESTIONS)))
        assertTrue(HomeModule.QUICK_ACTIONS in restored.hidden)
    }

    @Test fun newModuleVisibilityAndOrderingPersistThroughTheExistingSchema() {
        val changed = HomeLayoutPolicy.setVisible(HomeLayout(), HomeModule.BROWSER_HISTORY, true)
        val moved = HomeLayoutPolicy.move(changed, HomeModule.BROWSER_HISTORY, -1)
        assertEquals(moved, HomeLayoutPolicy.decode(HomeLayoutPolicy.encode(moved)))
        assertTrue(HomeModule.BROWSER_HISTORY in moved.visibleModules)
        assertFalse(HomeModule.RECENT_SITES in moved.visibleModules)
    }
}
