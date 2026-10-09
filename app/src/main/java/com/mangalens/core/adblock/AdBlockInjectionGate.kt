package com.mangalens.core.adblock

internal data class AdBlockInjectionTicket(val generation: Long, val url: String)

/** Main-thread document ownership for evaluateJavascript continuations. */
internal class AdBlockInjectionGate {
    private var generation = 0L
    private var current: AdBlockInjectionTicket? = null
    private var closed = false

    fun started(url: String): AdBlockInjectionTicket? {
        if (closed) return null
        return AdBlockInjectionTicket(++generation, url).also { current = it }
    }

    fun currentFor(url: String?): AdBlockInjectionTicket? = current?.takeIf {
        !closed && url != null && it.url.substringBefore('#') == url.substringBefore('#')
    }

    fun permits(ticket: AdBlockInjectionTicket, visibleUrl: String?, enabled: Boolean): Boolean =
        !closed && enabled && current == ticket && visibleUrl != null &&
            ticket.url.substringBefore('#') == visibleUrl.substringBefore('#')

    fun close() { closed = true; current = null }
}
