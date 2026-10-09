package com.mangalens.ui.web

internal data class WebReloadRequest(val url: String, val reloadCurrent: Boolean)

/** Retry the failed/stopped request even if WebView still exposes its preceding document. */
internal fun planWebReload(
    state: WebPageLoadState, visibleUrl: String?, currentUrl: String,
    isSafe: (String) -> Boolean
): WebReloadRequest? {
    val retrying = state.phase == WebPageLoadPhase.FAILED || state.phase == WebPageLoadPhase.STOPPED
    val target = if (retrying) state.navigation?.url ?: currentUrl else visibleUrl?.takeIf(isSafe) ?: currentUrl
    if (!isSafe(target)) return null
    return WebReloadRequest(target, visibleUrl?.let { WebPageLoadState.sameDocument(it, target) } == true)
}
