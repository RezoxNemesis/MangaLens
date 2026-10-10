package com.mangalens.ui.web

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class PrivateBrowserWorkspaceSessionTest {
    @Test fun privateHistoryAndBookmarksNeverEnterAnotherWorkspace() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val normal = BrowserWorkspaceSession(PrivateBrowserWorkspaceIo(),scope)
            val privateIo = PrivateBrowserWorkspaceIo(); val private = BrowserWorkspaceSession(privateIo,scope,"private_" + "a".repeat(32),true)
            val initial = withTimeout(2000) { private.state.filterNotNull().first() }; val secret = "https://private.invalid/secret"
            private.recordNavigation(initial.activeTabId,secret).await(); private.submit { it.commit(initial.activeTabId,secret,secret,"Private title") }.await(); private.submit { it.toggleBookmark(secret,"Private bookmark") }.await()
            val other = withTimeout(2000) { normal.state.filterNotNull().first() }; assertTrue(other.history.isEmpty()); assertTrue(other.bookmarks.isEmpty()); assertFalse(other.tabs.any { secret in it.entries })
            private.retireEphemeral(); private.awaitRetired(); privateIo.retire(); assertNull(private.state.value); assertTrue(private.submit { it.snapshot() }.isCancelled)
        } finally { scope.cancel() }
    }
    @Test fun retirementClearsAQueuedPrivatePublicationAfterTheActorActuallySettles() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO); val io = PrivateBrowserWorkspaceIo(); val session = BrowserWorkspaceSession(io,scope,"private_" + "a".repeat(32),true)
        try { withTimeout(2000) { session.state.filterNotNull().first() }; session.submit { it.newTab("https://private.invalid/queued") }; session.retireEphemeral(); withTimeout(2000) { session.awaitRetired() }; io.retire(); assertNull(session.state.value); assertTrue(session.submit { it.snapshot() }.isCancelled) } finally { scope.cancel() }
    }
}
