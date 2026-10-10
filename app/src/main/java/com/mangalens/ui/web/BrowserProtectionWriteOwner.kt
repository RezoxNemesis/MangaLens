package com.mangalens.ui.web

import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Bounded native authority only. No page callback, Compose state, WebView or UI continuation is retained. */
internal class BrowserProtectionWriteOwner(scope: BrowserUploadScope,
    private val liveScope: AtomicReference<BrowserUploadScope?>,
    private val revoked: AtomicBoolean,
    private val profile: BrowserProfileOwner,
    private val workspace: StateFlow<BrowserWorkspaceSnapshot?>) {
    private val publicationGuard = Any()
    private val expected = AtomicReference<BrowserUploadScope?>(scope)
    fun isCurrent(): Boolean {
        val accepted = expected.get() ?: return false
        return profile.isCurrent() && !revoked.get() && profile.choice.key == accepted.profileKey &&
            liveScope.get() == accepted && workspace.value?.activeTabId == accepted.tabId && expected.get() == accepted
    }
    /** Linearize this USER host-setting commit with token retirement; no FD is open at this gate. */
    fun publish(effect: () -> Unit) = synchronized(publicationGuard) {
        check(isCurrent()) { "The selected browser page changed before protection publication." }
        effect()
    }
    /** Drops the copied address without closing a handle or waiting for a provider. */
    fun retire() = synchronized(publicationGuard) { expected.set(null) }
}
