package com.mangalens.ui.web

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class BrowserWorkspaceStoreTest {
    private val directory = Files.createTempDirectory("browser-store-").toFile()
    private val io = BrowserTestFileIo(File(directory, "session.json"))
    private var now = 100L
    private fun store(): BrowserWorkspaceStore = BrowserWorkspaceStore(io) { now++ }
    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun coldReopenRetainsTabsCurrentCursorAndDesktopChoice() {
        val s = store()
        val a = s.snapshot().activeTabId
        s.navigate(a, "https://example.com/one")
        s.navigate(a, "https://example.com/two")
        s.move(a, -1)
        s.setDesktop(a, true)
        val b = s.newTab("https://example.org/other").activeTabId
        val reopened = store().snapshot()
        assertEquals(2, reopened.tabs.size)
        assertEquals(b, reopened.activeTabId)
        val saved = reopened.tabs.single { it.id == a }
        assertEquals("https://example.com/one", saved.url)
        assertTrue(saved.canGoForward)
        assertTrue(saved.desktop)
    }

    @Test fun navigatingAfterBackDiscardsOnlyThatTabsForwardHistory() {
        val s = store(); val a = s.snapshot().activeTabId
        s.navigate(a, "https://example.com/1"); s.navigate(a, "https://example.com/2")
        val b = s.newTab("https://example.org/keep").activeTabId
        s.move(a, -1); s.navigate(a, "https://example.com/new")
        assertEquals(listOf("https://example.com/1", "https://example.com/new"), s.snapshot().tabs.single { it.id == a }.entries)
        assertEquals("https://example.org/keep", s.snapshot().tabs.single { it.id == b }.url)
    }

    @Test fun repeatedRequestedUrlDoesNotCreateDuplicateBackEntries() {
        val s = store(); val a = s.snapshot().activeTabId
        s.navigate(a, "https://example.com/one"); s.navigate(a, "https://example.com/one")
        assertEquals(1, s.snapshot().activeTab.entries.size)
    }

    @Test fun onlyExactCurrentSuccessfulCommitAddsVisitedHistory() {
        val s = store(); val a = s.snapshot().activeTabId
        s.navigate(a, "https://example.com/failed")
        assertTrue(s.snapshot().history.isEmpty())
        s.navigate(a, "https://example.com/ready")
        val before = s.snapshot()
        s.commit(a, "https://example.com/failed", "https://example.com/failed", "Old callback")
        assertEquals(before, s.snapshot())
        s.commit(a, "https://example.com/ready", "https://example.com/ready", "Ready chapter")
        assertEquals(1, s.snapshot().history.size)
        assertEquals("Ready chapter", s.snapshot().history.single().title)
        assertEquals("Ready chapter", store().snapshot().history.single().title)
    }

    @Test fun redirectReplacementDoesNotAppendAnExtraBackPage() {
        val s = store(); val a = s.snapshot().activeTabId
        s.navigate(a, "https://example.com/before")
        s.navigate(a, "https://example.com/login")
        s.navigate(a, "https://example.com/account", replace = true)
        assertEquals(listOf("https://example.com/before", "https://example.com/account"), s.snapshot().activeTab.entries)
        s.move(a, -1)
        assertEquals("https://example.com/before", s.snapshot().activeTab.url)
    }

    @Test fun bookmarkTogglePersistsExactTitleWithoutTouchingCookiesOrHistory() {
        val s = store()
        s.toggleBookmark("https://example.com/reader?q=two", "My chapter")
        assertEquals(1, s.snapshot().bookmarks.size)
        assertEquals("My chapter", store().snapshot().bookmarks.single().title)
        assertTrue(s.snapshot().history.isEmpty())
        s.toggleBookmark("https://example.com/reader?q=two", "Ignored replacement title")
        assertTrue(store().snapshot().bookmarks.isEmpty())
        assertFalse(io.file.readText().contains("cookie", ignoreCase = true))
    }

    @Test fun closingSelectedTabChoosesNeighbourAndClosingLastCreatesBlankTab() {
        val s = store(); val a = s.snapshot().activeTabId
        val b = s.newTab("https://example.org/two").activeTabId
        s.closeTab(b)
        assertEquals(a, s.snapshot().activeTabId)
        s.closeTab(a)
        assertEquals(1, s.snapshot().tabs.size)
        assertNotEquals(a, s.snapshot().activeTabId)
        assertEquals("", s.snapshot().activeTab.url)
        assertEquals(-1, store().snapshot().activeTab.position)
    }

    @Test fun storageFailureDoesNotPublishOrOverwriteThePriorDurableTab() {
        val base = store(); val id = base.snapshot().activeTabId
        base.navigate(id, "https://example.com/kept")
        assertTrue("Accepted tab navigation must reach real durable storage", io.file.exists())
        val before = base.snapshot(); val bytes = io.file.readBytes()
        val failing = BrowserWorkspaceStore(object : BrowserWorkspaceIo {
            override fun read(maxBytes: Int) = io.read(maxBytes)
            override fun write(bytes: ByteArray) { throw IOException("Full storage") }
        }) { now++ }
        assertThrows(IOException::class.java) { failing.navigate(id, "https://example.com/lost") }
        assertEquals(before, failing.snapshot())
        assertArrayEquals(bytes, io.file.readBytes())
        assertEquals("https://example.com/kept", store().snapshot().activeTab.url)
    }

    @Test fun unsafeOrCredentialedNavigationIsRejectedBeforeAnyWrite() {
        val s = store(); val id = s.snapshot().activeTabId
        for (url in listOf("javascript:alert(1)", "file:///data/private", "content://gallery/item", "https://user:password@example.com/", "https://example.com/a\n")) {
            assertThrows(IllegalArgumentException::class.java) { s.navigate(id, url) }
        }
        assertEquals("", s.snapshot().activeTab.url)
        assertFalse(io.file.exists())
    }

    @Test fun tabAndCursorCapsAreEnforcedWithoutLosingCurrentPage() {
        val s = store(); val first = s.snapshot().activeTabId
        repeat(BrowserWorkspaceLimits.TABS - 1) { s.newTab("https://example.org/$it") }
        val before = s.snapshot()
        assertThrows(IllegalStateException::class.java) { s.newTab("https://example.org/overflow") }
        assertEquals(before, s.snapshot())
        repeat(BrowserWorkspaceLimits.TAB_ENTRIES + 5) { s.navigate(first, "https://example.com/$it") }
        val tab = store().snapshot().tabs.single { it.id == first }
        assertEquals(BrowserWorkspaceLimits.TAB_ENTRIES, tab.entries.size)
        assertEquals("https://example.com/${BrowserWorkspaceLimits.TAB_ENTRIES + 4}", tab.url)
    }

    @Test fun historyIsBoundedDeduplicatedAndCanBeExplicitlyCleared() {
        val s = store(); val id = s.snapshot().activeTabId
        repeat(BrowserWorkspaceLimits.HISTORY + 3) {
            val url = "https://example.com/$it"
            s.navigate(id, url); s.commit(id, url, url, "Chapter $it")
        }
        assertEquals(BrowserWorkspaceLimits.HISTORY, s.snapshot().history.size)
        val url = s.snapshot().history.last().url
        s.navigate(id, url); s.commit(id, url, url, "Visited twice")
        assertEquals(2, s.snapshot().history.first().visits)
        s.clearHistory()
        assertTrue(store().snapshot().history.isEmpty())
        assertEquals(url, s.snapshot().activeTab.url)
    }

    @Test fun malformedOrUnsafeSnapshotRestoresBlankWithoutAutoDispatchingEmbeddedUrls() {
        io.file.writeText("{\"version\":1,\"tabs\":[{\"url\":\"file:///data/secret\"}]}")
        val s = store().snapshot()
        assertEquals("", s.activeTab.url)
        assertNotNull(s.notice)
        assertTrue(s.history.isEmpty())
    }

    @Test fun committedParentUrlSurvivesColdBlankTabRestorationWithoutSelectingAnOlderTab() {
        val s = store(); val first = s.snapshot().activeTabId; val url = "https://example.com/committed"
        s.navigate(first, url); s.commit(first, url, url, "Committed")
        val blank = s.newTab().activeTabId
        val cold = store().snapshot()
        assertEquals(blank, cold.activeTabId)
        assertEquals(url, cold.lastReportedUrl)
        assertEquals(BrowserIncomingAction.IGNORE, planBrowserIncoming(cold, url).action)
    }
}

internal class BrowserTestFileIo(val file: File) : BrowserWorkspaceIo {
    override fun read(maxBytes: Int): ByteArray? {
        if (!file.exists()) return null
        if (file.length() > maxBytes) return ByteArray(maxBytes + 1)
        return file.readBytes()
    }
    override fun write(bytes: ByteArray) {
        val pending = File(file.parentFile, "session.pending")
        pending.writeBytes(bytes)
        Files.move(pending.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
