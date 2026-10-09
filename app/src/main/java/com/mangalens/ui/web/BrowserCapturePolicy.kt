package com.mangalens.ui.web

/** Reserves Android's permission/projection result until that same request is consumed. */
internal class BrowserCaptureGate {
    private var pending: BrowserUploadScope? = null
    private var revoked = false
    @Synchronized fun begin(scope: BrowserUploadScope): Boolean {
        if (pending != null || !Regex("[a-f0-9]{32}").matches(scope.tabId) || scope.navigationEpoch <= 0 ||
            runCatching { requireBrowserUrl(scope.pageUrl) }.isFailure) return false
        pending = scope
        revoked = false
        return true
    }
    @Synchronized fun mayProject(current: BrowserUploadScope?): Boolean = !revoked && pending != null && pending == current
    @Synchronized fun finish(current: BrowserUploadScope?): Boolean {
        val accepted = mayProject(current)
        pending = null
        revoked = false
        return accepted
    }
    @Synchronized fun revoke() { revoked = true }
}
