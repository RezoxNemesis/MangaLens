package com.mangalens.ui.web

import org.junit.Assert.*
import org.junit.Test

class WebPageLoadStateTest {
    private val first = "https://example.org/chapter/one"
    private val second = "https://example.org/chapter/two"
    private val error = "Page could not load. Check your connection and retry."

    @Test fun completionAfterMainFrameFailureCannotMakeThePageReadyOrEraseItsError() {
        val loading = WebPageLoadState().start(first)
        val ticket = requireNotNull(loading.navigation)
        val failed = loading.failed(ticket, first, true, error)
        val lateCompletion = failed.finished(ticket, first)
        assertEquals(WebPageLoadPhase.FAILED, lateCompletion.phase)
        assertEquals(error, lateCompletion.error)
        assertFalse(lateCompletion.pageReady)
        assertFalse(lateCompletion.loading)
        assertEquals(100, lateCompletion.progress)
    }

    @Test fun sameUrlRetryClearsFailureAndRejectsThePreviousNavigationEpoch() {
        val loading = WebPageLoadState().start(first)
        val old = requireNotNull(loading.navigation)
        val retried = loading.failed(old, first, true, error).start(first)
        val current = requireNotNull(retried.navigation)
        assertTrue(current.epoch > old.epoch)
        assertNull(retried.error)
        assertTrue(retried.loading)
        assertEquals(0, retried.progress)
        assertEquals(retried, retried.finished(old, first))
        assertEquals(retried, retried.failed(old, first, true, error))
        assertEquals(retried, retried.progressed(old, first, 95))
        assertTrue(retried.finished(current, first).pageReady)
    }

    @Test fun callbackUrlMustBelongToTheActiveDocumentEvenWhenTheEpochMatches() {
        val loading = WebPageLoadState().start(second)
        val ticket = requireNotNull(loading.navigation)
        assertEquals(loading, loading.finished(ticket, first))
        assertEquals(loading, loading.failed(ticket, first, true, error))
        assertEquals(loading, loading.progressed(ticket, first, 80))
    }

    @Test fun navigatingElsewhereImmediatelyInvalidatesReadyStateAndOldTranslationTicket() {
        val loading = WebPageLoadState().start(first)
        val old = requireNotNull(loading.navigation)
        val ready = loading.finished(old, first)
        assertTrue(ready.readyFor(old))
        val next = ready.start(second)
        assertFalse(next.pageReady)
        assertFalse(next.readyFor(old))
        assertEquals(next, next.finished(old, first))
        assertEquals(second, next.navigation?.url)
    }

    @Test fun subresourceFailureDoesNotDisableSuccessfulMainFrameOrAbortLoading() {
        val loading = WebPageLoadState().start(first)
        val ticket = requireNotNull(loading.navigation)
        assertEquals(loading, loading.failed(ticket, first, false, error))
        val ready = loading.finished(ticket, first)
        assertEquals(ready, ready.failed(ticket, first, false, error))
        assertTrue(ready.pageReady)
    }

    @Test fun aHundredPercentProgressDoesNotRunTranslationBeforeMainFrameCompletion() {
        val loading = WebPageLoadState().start(first)
        val ticket = requireNotNull(loading.navigation)
        val reportedComplete = loading.progressed(ticket, first, 100)
        assertTrue(reportedComplete.loading)
        assertFalse(reportedComplete.pageReady)
        assertTrue(reportedComplete.finished(ticket, first).pageReady)
    }

    @Test fun failureAfterCompletionStillDisablesDocumentActionsAndProgressCannotReviveIt() {
        val loading = WebPageLoadState().start(first)
        val ticket = requireNotNull(loading.navigation)
        val failed = loading.finished(ticket, first).failed(ticket, first, true, error)
        assertFalse(failed.pageReady)
        assertEquals(failed, failed.progressed(ticket, first, 20))
        assertEquals(error, failed.error)
    }

    @Test fun stoppedRequestDoesNotLeaveAnInfiniteLoadingIndicatorAndCanBeRetried() {
        val loading = WebPageLoadState().start(first)
        val ticket = requireNotNull(loading.navigation)
        val stopped = loading.stopped(ticket)
        assertEquals(WebPageLoadPhase.STOPPED, stopped.phase)
        assertFalse(stopped.loading)
        assertFalse(stopped.pageReady)
        assertNotNull(stopped.error)
        assertEquals(stopped, stopped.finished(ticket, first))
        assertTrue(stopped.start(first).loading)
    }

    @Test fun fragmentsAndImplicitRootSlashReferToTheSameLoadedDocument() {
        val loading = WebPageLoadState().start("https://example.org")
        val ticket = requireNotNull(loading.navigation)
        assertTrue(loading.finished(ticket, "https://example.org/#top").pageReady)
        assertFalse(loading.finished(ticket, "https://example.org/?other=1").pageReady)
    }

    @Test fun idleAndNullCallbacksCannotClaimAReadyPage() {
        val idle = WebPageLoadState()
        assertFalse(idle.loading)
        assertFalse(idle.pageReady)
        val loading = idle.start(first)
        val ticket = requireNotNull(loading.navigation)
        assertEquals(loading, loading.finished(ticket, null))
        assertEquals(loading, loading.failed(ticket, null, true, error))
    }
}
