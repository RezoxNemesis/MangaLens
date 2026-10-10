package com.mangalens.ui.video

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** A pause retires held work immediately, even when Compose coalesces pause and resume. */
internal class RecentVideoWidgetEntryAuthority {
    private val epoch = AtomicLong()
    private val closed = AtomicBoolean()
    class Ticket internal constructor(internal val owner: RecentVideoWidgetEntryAuthority, internal val epoch: Long)
    fun capture() = Ticket(this, epoch.get())
    fun retire() { epoch.incrementAndGet() }
    fun close() { closed.set(true); retire() }
    fun isCurrent(ticket: Ticket): Boolean = ticket.owner === this && !closed.get() && ticket.epoch == epoch.get()
}
