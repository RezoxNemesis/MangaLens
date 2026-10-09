package com.mangalens.ui.web

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class WebReloadRequestTest {
    private val first = "http://localhost:9876/first"
    private val failedUrl = "http://localhost:9876/recover"
    private val error = "Page could not load. Check your connection and retry."

    @Test fun retryKeepsTheFailedRequestWhenWebViewExposesThePreviousCommittedDocument() {
        val loading = WebPageLoadState().start(failedUrl)
        val failed = loading.failed(loading.navigation!!, failedUrl, true, error)
        assertEquals(WebReloadRequest(failedUrl, false), planWebReload(failed, first, failedUrl, ::safe))
        assertEquals(failedUrl, failed.navigation?.url)
        assertEquals(WebPageLoadPhase.FAILED, failed.phase)
    }

    @Test fun retryKeepsTheStoppedRequestRatherThanReloadingThePreviousPage() {
        val loading = WebPageLoadState().start(failedUrl)
        val stopped = loading.stopped(loading.navigation!!)
        assertEquals(WebReloadRequest(failedUrl, false), planWebReload(stopped, first, failedUrl, ::safe))
    }

    @Test fun retryUsesItsTicketEvenWhenAnOldCallbackChangedTheDisplayUrl() {
        val loading = WebPageLoadState().start(failedUrl)
        val failed = loading.failed(loading.navigation!!, failedUrl, true, error)
        assertEquals(WebReloadRequest(failedUrl, false), planWebReload(failed, first, first, ::safe))
    }

    @Test fun sameDocumentRetryPreservesNativeReloadAndItsHistoryEntry() {
        val loading = WebPageLoadState().start(failedUrl)
        val failed = loading.failed(loading.navigation!!, failedUrl, true, error)
        assertEquals(WebReloadRequest(failedUrl, true), planWebReload(failed, "$failedUrl#top", failedUrl, ::safe))
    }

    @Test fun ordinaryRefreshFollowsTheActualVisibleDocument() {
        val loading = WebPageLoadState().start(first)
        val ready = loading.finished(loading.navigation!!, first)
        assertEquals(WebReloadRequest(failedUrl, true), planWebReload(ready, failedUrl, first, ::safe))
    }

    @Test fun anInternalErrorDocumentDoesNotReplaceTheIntendedHttpRequest() {
        val loading = WebPageLoadState().start(failedUrl)
        val failed = loading.failed(loading.navigation!!, failedUrl, true, error)
        assertEquals(WebReloadRequest(failedUrl, false),
            planWebReload(failed, "chrome-error://chromewebdata/", failedUrl, ::safe))
    }

    @Test fun unsupportedRetryCannotEscapeItsSafetyCheckByChoosingAnOlderSafePage() {
        val loading = WebPageLoadState().start("javascript:alert('unsafe')")
        val failed = loading.failed(loading.navigation!!, loading.navigation.url, true, error)
        assertNull(planWebReload(failed, first, first, ::safe))
    }

    @Test fun aThrowingDiagnosticObserverCannotAbortBrowserCallbackControlFlow() {
        val old = WebNavigationDiagnostics.observer
        try {
            WebNavigationDiagnostics.observer = { throw AssertionError("diagnostic failure") }
            val state = WebPageLoadState().start(first)
            WebNavigationDiagnostics.observe("page_started", state, first, first)
            assertTrue(state.finished(state.navigation!!, first).pageReady)
        } finally { WebNavigationDiagnostics.observer = old }
    }

    private fun safe(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }.getOrDefault(false)
}
