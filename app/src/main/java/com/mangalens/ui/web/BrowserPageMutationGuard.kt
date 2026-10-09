package com.mangalens.ui.web

/** Keeps a nullable provider result distinct from loss of the captured active page. */
internal data class BrowserGuardedValue<T>(val value: T)

internal class BrowserPageMutationGuard(private val isCurrent: () -> Boolean) {
    fun current(): Boolean = isCurrent()
    fun publish(action: () -> Unit): Boolean {
        if (!current()) return false
        action()
        return true
    }
    suspend fun <T> await(action: suspend () -> T): BrowserGuardedValue<T>? {
        if (!current()) return null
        val value = action()
        if (!current()) return null
        return BrowserGuardedValue(value)
    }
}
