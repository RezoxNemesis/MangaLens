package com.mangalens.ui.web

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BrowserWorkspaceSessionTest {
    private val directory = Files.createTempDirectory("browser-session-").toFile()
    private val io = BrowserTestFileIo(File(directory, "session.json"))
    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun orderedApplicationCommandsPublishOnlyTheRealSavedSnapshot() = runTest {
        val session = BrowserWorkspaceSession(io, backgroundScope); runCurrent()
        val id = session.state.value!!.activeTabId
        val one = session.recordNavigation(id, "https://example.com/one")
        val two = session.recordNavigation(id, "https://example.com/two")
        val ready = session.submit { it.commit(id, "https://example.com/two", "https://example.com/two", "Second page") }
        runCurrent(); one.await(); two.await(); ready.await()
        val saved = BrowserWorkspaceStore(io).snapshot()
        assertEquals(listOf("https://example.com/one", "https://example.com/two"), saved.activeTab.entries)
        assertEquals("Second page", saved.history.single().title)
        assertEquals(saved, session.state.value)
    }

    @Test fun cancellingTheUiWaiterDoesNotDiscardAnAlreadyAcceptedSessionCommand() = runTest {
        val session = BrowserWorkspaceSession(io, backgroundScope); runCurrent()
        val id = session.state.value!!.activeTabId
        val accepted = session.recordNavigation(id, "https://example.com/accepted")
        val waiter = async(start = CoroutineStart.UNDISPATCHED) { accepted.await() }
        waiter.cancel(); runCurrent()
        assertTrue("The accepted command must finish in application scope", io.file.exists())
        assertEquals("https://example.com/accepted", BrowserWorkspaceStore(io).snapshot().activeTab.url)
        assertFalse(accepted.isCancelled)
    }

    @Test fun failedDiskMutationKeepsThePublishedSnapshotAndProvidesARetryMessage() = runTest {
        val base = BrowserWorkspaceStore(io); val id = base.snapshot().activeTabId
        base.navigate(id, "https://example.com/kept")
        val failing = object : BrowserWorkspaceIo {
            override fun read(maxBytes: Int) = io.read(maxBytes)
            override fun write(bytes: ByteArray) { throw IOException("Disk full") }
        }
        val session = BrowserWorkspaceSession(failing, backgroundScope); runCurrent()
        val before = session.state.value
        val result = session.recordNavigation(id, "https://example.com/lost"); runCurrent()
        try { result.await(); fail("Failed persistence must not report a successful navigation command") }
        catch (_: IOException) {}
        assertEquals(before, session.state.value)
        assertNotNull(session.error.value)
        assertEquals("https://example.com/kept", BrowserWorkspaceStore(io).snapshot().activeTab.url)
    }

    @Test fun callbacksFromAnInactiveTabCannotChangeThatTabAfterSelection() = runTest {
        val base = BrowserWorkspaceStore(io); val a = base.snapshot().activeTabId
        base.navigate(a, "https://example.com/kept")
        val b = base.newTab("https://example.org/selected").activeTabId
        val session = BrowserWorkspaceSession(io, backgroundScope); runCurrent()
        session.recordNavigation(a, "https://example.com/stale"); runCurrent()
        val saved = BrowserWorkspaceStore(io).snapshot()
        assertEquals(b, saved.activeTabId)
        assertEquals("https://example.com/kept", saved.tabs.single { it.id == a }.url)
        assertEquals("https://example.org/selected", saved.activeTab.url)
    }

    @Test fun callbackQueueIsBoundedAndOverflowNeverBecomesAnInvisibleAcceptedCommand() = runTest {
        val session = BrowserWorkspaceSession(io, backgroundScope); runCurrent()
        val results = List(66) { session.submit { it.snapshot() } }
        assertTrue("The finite actor queue must reject overflow explicitly", results.last().isCancelled)
        assertNotNull(session.error.value)
        runCurrent()
        assertEquals(65, results.count { !it.isCancelled }) // 64 buffered plus the actor's already reserved receive
    }

    @Test fun titleAndDesktopChangesDoNotInvalidateACapturedSourceHistoryCursor() = runTest {
        val base = BrowserWorkspaceStore(io); val id = base.snapshot().activeTabId
        base.navigate(id, "https://example.com/one"); base.navigate(id, "https://example.com/two")
        val captured = base.snapshot().activeTab
        val session = BrowserWorkspaceSession(io, backgroundScope); runCurrent()
        session.submit { it.setDesktop(id, true) }
        session.submit { it.commit(id, captured.url, captured.url, "Actual page title") }
        val back = session.moveHistory(captured, -1)
        runCurrent()
        assertEquals("https://example.com/one", back.await().activeTab.url)
        val saved = BrowserWorkspaceStore(io).snapshot()
        assertTrue(saved.activeTab.desktop)
        assertEquals("Actual page title", saved.history.single().title)
    }

    @Test fun replacedSourceCursorRejectsOldBackWithoutMovingTheNewIntent() = runTest {
        val base = BrowserWorkspaceStore(io); val id = base.snapshot().activeTabId
        base.navigate(id, "https://example.com/one"); base.navigate(id, "https://example.com/two")
        val captured = base.snapshot().activeTab
        val session = BrowserWorkspaceSession(io, backgroundScope); runCurrent()
        session.recordNavigation(id, "https://example.com/new-explicit-intent")
        val oldBack = session.moveHistory(captured, -1); runCurrent()
        try { oldBack.await(); fail("A stale source cursor must be rejected") } catch (_: IllegalStateException) {}
        assertNotNull(session.error.value)
        assertEquals("https://example.com/new-explicit-intent", BrowserWorkspaceStore(io).snapshot().activeTab.url)
    }

    @Test fun anotherSelectedTabCannotBeMovedByACapturedOldBackCommand() = runTest {
        val base = BrowserWorkspaceStore(io); val id = base.snapshot().activeTabId
        base.navigate(id, "https://example.com/one"); base.navigate(id, "https://example.com/two")
        val captured = base.snapshot().activeTab
        val session = BrowserWorkspaceSession(io, backgroundScope); runCurrent()
        session.submit { it.newTab("https://example.org/new") }
        val oldBack = session.moveHistory(captured, -1); runCurrent()
        try { oldBack.await(); fail("An old tab must not control the selected cursor") } catch (_: IllegalStateException) {}
        val saved = BrowserWorkspaceStore(io).snapshot()
        assertEquals("https://example.org/new", saved.activeTab.url)
        assertEquals("https://example.com/two", saved.tabs.single { it.id == id }.url)
    }
}
