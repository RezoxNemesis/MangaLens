package com.mangalens.core.adblock

/** Owns only this client's removable document-start registration. Platform work stays on Main. */
internal class AdBlockDocumentStartRegistration {
    private var owner: Any? = null
    private var remove: (() -> Unit)? = null
    private var closed = false
    val installed: Boolean get() = remove != null

    fun update(view: Any, enabled: Boolean, install: () -> (() -> Unit)?): Boolean {
        if (closed) return false
        if (!enabled || owner !== view) detach()
        if (!enabled) return false
        if (remove != null) return true
        val lease = runCatching(install).getOrNull() ?: return false
        owner = view
        remove = lease
        return true
    }

    fun close() { closed = true; detach() }

    private fun detach() {
        val previous = remove
        remove = null
        owner = null
        runCatching { previous?.invoke() }
    }
}
