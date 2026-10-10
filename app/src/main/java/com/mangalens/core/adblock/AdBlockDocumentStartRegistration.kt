package com.mangalens.core.adblock

/** Owns only this client's removable document-start registration. Platform work stays on Main. */
internal class AdBlockDocumentStartRegistration {
    private var owner: Any? = null
    private var remove: (() -> Unit)? = null
    private var scopeKey: Any? = null
    private var closed = false
    val installed: Boolean get() = remove != null

    fun update(view: Any, enabled: Boolean, install: () -> (() -> Unit)?): Boolean =
        updateScoped(view, enabled, null, install)

    fun updateScoped(view: Any, enabled: Boolean, key: Any?, install: () -> (() -> Unit)?): Boolean {
        if (closed) return false
        if (!enabled || owner !== view || scopeKey != key) detach()
        if (!enabled) return false
        if (remove != null) return true
        val lease = runCatching(install).getOrNull() ?: return false
        owner = view
        scopeKey = key
        remove = lease
        return true
    }

    fun close() { closed = true; detach() }

    private fun detach() {
        val previous = remove
        remove = null
        owner = null
        scopeKey = null
        runCatching { previous?.invoke() }
    }
}
