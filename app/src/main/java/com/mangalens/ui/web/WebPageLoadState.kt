package com.mangalens.ui.web

import java.net.URI
import java.util.Locale

internal enum class WebPageLoadPhase { EMPTY, LOADING, READY, FAILED, STOPPED }
internal data class WebNavigationTicket(val epoch: Long, val url: String)

/** Main-frame state is separate from translation/download status and transient browser chrome. */
internal data class WebPageLoadState(
    val navigation: WebNavigationTicket? = null,
    val phase: WebPageLoadPhase = WebPageLoadPhase.EMPTY,
    val progress: Int = 0,
    val error: String? = null
) {
    val pageReady: Boolean get() = phase == WebPageLoadPhase.READY
    val loading: Boolean get() = phase == WebPageLoadPhase.LOADING

    fun start(url: String): WebPageLoadState {
        require(url.isNotBlank())
        return WebPageLoadState(WebNavigationTicket((navigation?.epoch ?: 0) + 1, url), WebPageLoadPhase.LOADING)
    }

    fun readyFor(ticket: WebNavigationTicket): Boolean = pageReady && navigation == ticket

    fun accepts(ticket: WebNavigationTicket, callbackUrl: String?): Boolean =
        navigation == ticket && callbackUrl != null && sameDocument(ticket.url, callbackUrl)

    fun finished(ticket: WebNavigationTicket, callbackUrl: String?): WebPageLoadState =
        if (loading && accepts(ticket, callbackUrl)) copy(phase = WebPageLoadPhase.READY, progress = 100, error = null) else this

    fun failed(ticket: WebNavigationTicket, callbackUrl: String?, mainFrame: Boolean, message: String): WebPageLoadState =
        if (mainFrame && accepts(ticket, callbackUrl)) copy(phase = WebPageLoadPhase.FAILED, progress = 100, error = message) else this

    fun progressed(ticket: WebNavigationTicket, callbackUrl: String?, value: Int): WebPageLoadState =
        if (loading && accepts(ticket, callbackUrl)) copy(progress = value.coerceIn(0, 100)) else this

    fun stopped(ticket: WebNavigationTicket): WebPageLoadState =
        if (navigation == ticket && loading) copy(phase = WebPageLoadPhase.STOPPED, progress = 100,
            error = "Page loading stopped. Retry to load the source.") else this

    companion object {
        fun sameDocument(first: String, second: String): Boolean = document(first) == document(second)

        private fun document(url: String): String = runCatching {
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return@runCatching url.substringBefore('#')
            val host = uri.rawAuthority?.lowercase(Locale.ROOT) ?: return@runCatching url.substringBefore('#')
            "$scheme://$host${uri.rawPath.orEmpty().ifEmpty { "/" }}${uri.rawQuery?.let { "?$it" }.orEmpty()}"
        }.getOrDefault(url.substringBefore('#'))
    }
}
