package com.mangalens.ui.web

internal data class WebNavigationObservation(
    val action: String,
    val state: WebPageLoadState,
    val currentUrl: String,
    val visibleUrl: String?,
    val callbackUrl: String?,
    val targetUrl: String?,
    val detail: String,
    val viewIdentity: Int?
)

/** Inert in normal browsing. The controlled Android fixture installs a filtered observer. */
internal object WebNavigationDiagnostics {
    @Volatile var observer: ((WebNavigationObservation) -> Unit)? = null
    val enabled: Boolean get() = observer != null

    fun observe(
        action: String, state: WebPageLoadState, currentUrl: String, visibleUrl: String?,
        callbackUrl: String? = null, targetUrl: String? = null,
        detail: String = "", viewIdentity: Int? = null
    ) {
        val listener = observer ?: return
        // Diagnostics cannot change the native callback's control flow or throw on Main.
        runCatching { listener(WebNavigationObservation(action, state, currentUrl, visibleUrl,
            callbackUrl, targetUrl, detail, viewIdentity)) }
    }
}
