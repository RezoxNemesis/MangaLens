package com.mangalens.ui.web

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BrowserPageMutationGuardTest {
    private val directory = Files.createTempDirectory("browser-page-ownership-").toFile()
    private val io = BrowserTestFileIo(File(directory, "session.json"))
    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun heldTranslationCannotApplyOrPublishAfterTheActorSelectsAnotherTabBeforeOldViewDisposal() = runTest {
        val store = BrowserWorkspaceStore(io); val a = store.snapshot().activeTabId
        store.navigate(a, "https://example.com/a")
        val b = store.newTab("https://example.org/b").activeTabId; store.selectTab(a)
        val session = BrowserWorkspaceSession(io, backgroundScope); runCurrent()
        val oldViewDisposed = false // The IO commit wins before Compose disposes the old subtree.
        val page = WebPageLoadState().start("https://example.com/a").let { it.finished(it.navigation!!, it.navigation.url) }
        val ticket = page.navigation!!
        val guard = BrowserPageMutationGuard { !oldViewDisposed && session.state.value?.activeTabId == a && page.readyFor(ticket) }
        val translation = CompletableDeferred<String>(); val effects = mutableListOf<String>()
        val work = async(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = guard.await { translation.await() }
                if (result != null) guard.publish { effects += "apply:${result.value}"; effects += "progress" }
            } finally { guard.publish { effects += "finished-status" } }
        }
        session.submit { it.selectTab(b) }; runCurrent()
        assertEquals(b, BrowserWorkspaceStore(io).snapshot().activeTabId)
        translation.complete("Translated result"); runCurrent(); work.await()
        assertFalse(oldViewDisposed)
        assertTrue("Inactive A must dispatch no JS, progress, error or finally publication", effects.isEmpty())
    }

    @Test fun inactiveInitialBuildAndRestorationDoNotEvenDispatchTheirProviderOperation() = runTest {
        val guard = BrowserPageMutationGuard { false }; var dispatches = 0
        assertNull(guard.await { dispatches++; "read-or-restore-js" })
        assertFalse(guard.publish { dispatches++ })
        assertEquals(0, dispatches)
    }

    @Test fun stoppedOrReplacedTicketDropsTheHeldResultEvenWhenTheTabAndViewStillMatch() = runTest {
        for (replacement in listOf("stop", "new-document")) {
            var page = WebPageLoadState().start("https://example.com/a").let { it.finished(it.navigation!!, it.navigation.url) }
            val ticket = page.navigation!!
            val guard = BrowserPageMutationGuard { page.readyFor(ticket) }
            val value = CompletableDeferred<String>()
            val pending = async(start = CoroutineStart.UNDISPATCHED) { guard.await { value.await() } }
            page = if (replacement == "stop") page.copy(phase = WebPageLoadPhase.STOPPED) else page.start("https://example.com/new")
            value.complete("old text"); runCurrent()
            assertNull(pending.await())
            assertFalse(guard.publish { fail("Old ticket cannot publish") })
        }
    }

    @Test fun qualifiedActivePageCanPublishANullJsResultAndCancellationStillPropagates() = runTest {
        val guard = BrowserPageMutationGuard { true }
        val result = guard.await<String?> { null }
        assertNotNull(result); assertNull(result!!.value)
        var published = false; assertTrue(guard.publish { published = true }); assertTrue(published)
        try { guard.await<String> { throw CancellationException("closed view") }; fail("Cancellation must propagate") }
        catch (_: CancellationException) {}
    }

    @Test fun aPostedJsDispatchRechecksOwnershipAfterEnqueueBeforeTouchingTheOldView() = runTest {
        val store = BrowserWorkspaceStore(io); val a = store.snapshot().activeTabId
        store.navigate(a, "https://example.com/a")
        val b = store.newTab("https://example.org/b").activeTabId; store.selectTab(a)
        val session = BrowserWorkspaceSession(io, backgroundScope); runCurrent()
        val guard = BrowserPageMutationGuard { session.state.value?.activeTabId == a }
        var jsDispatches = 0
        assertTrue(guard.current())
        val queuedOnView = { guard.publish { jsDispatches++ } }
        session.submit { it.selectTab(b) }; runCurrent()
        assertFalse(queuedOnView())
        assertEquals(0, jsDispatches)
    }
}
