package com.mangalens.ui.reader

import java.util.concurrent.atomic.AtomicInteger

/** A composed Image owns its preview until disposal; dismissal closes only unclaimed results. */
internal class ReaderBubblePreviewOwnership<T : AutoCloseable>(private val resource: T) {
    private val owner = AtomicInteger(0) // pending / composed UI / released
    fun claim(): T? = if (owner.compareAndSet(0, 1)) resource else null
    fun abandon() { if (owner.compareAndSet(0, 2)) resource.close() }
    fun disposeUi() { if (owner.compareAndSet(1, 2)) resource.close() }
}
