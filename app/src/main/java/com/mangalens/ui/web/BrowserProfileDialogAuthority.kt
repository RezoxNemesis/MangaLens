package com.mangalens.ui.web

/** Main-owned dialog ticket. Accepted durable creation cannot revive a dismissed/superseded UI. */
internal class BrowserProfileDialogAuthority {
    internal class Ticket internal constructor(internal val epoch: Long, internal val owner: BrowserProfileOwner)
    private var epoch = 0L
    private var owner: BrowserProfileOwner? = null
    fun open(value: BrowserProfileOwner) { epoch++; owner = value }
    fun retire() { epoch++; owner = null }
    fun capture(value: BrowserProfileOwner): Ticket? =
        if (owner === value && value.isCurrent()) Ticket(epoch, value) else null
    fun accepts(ticket: Ticket, current: BrowserProfileOwner): Boolean =
        owner === current && ticket.owner === current && ticket.epoch == epoch && current.isCurrent()
}
