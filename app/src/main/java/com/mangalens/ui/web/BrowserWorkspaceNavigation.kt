package com.mangalens.ui.web

internal enum class BrowserIncomingAction { IGNORE, SELECT, NAVIGATE, REJECT }
internal data class BrowserIncomingDecision(val action: BrowserIncomingAction, val tabId: String? = null, val url: String? = null)
internal data class BrowserHistoryDispatch(val url: String, val nativeDelta: Int?)
internal fun browserDesktopUserAgent(mobile: String): String {
    val chrome = Regex("Chrome/[0-9]{1,4}(?:\\.[0-9]{1,6}){1,3}").find(mobile)?.value
    return listOfNotNull(
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko)",
        chrome, "Safari/537.36"
    ).joinToString(" ")
}

internal fun planBrowserIncoming(state: BrowserWorkspaceSnapshot, incoming: String, initial: Boolean = false): BrowserIncomingDecision {
    if (incoming.isBlank()) return BrowserIncomingDecision(BrowserIncomingAction.IGNORE)
    if (runCatching { requireBrowserUrl(incoming) }.isFailure) return BrowserIncomingDecision(BrowserIncomingAction.REJECT)
    if (incoming == state.activeTab.url || incoming == state.lastReportedUrl) return BrowserIncomingDecision(BrowserIncomingAction.IGNORE)
    // Parent last-URL persistence and this journal are separate writes. A historical initial URL
    // is an echo after process loss, while a later changed URL remains an explicit host request.
    if (initial && (state.tabs.any { incoming in it.entries } || state.history.any { it.url == incoming }))
        return BrowserIncomingDecision(BrowserIncomingAction.IGNORE)
    val existing = state.tabs.firstOrNull { it.url == incoming }
    return if (existing != null) BrowserIncomingDecision(BrowserIncomingAction.SELECT, existing.id, incoming)
        else BrowserIncomingDecision(BrowserIncomingAction.NAVIGATE, state.activeTabId, incoming)
}

internal fun planBrowserHistory(tab: BrowserTab, delta: Int, nativeEntries: List<String>, nativePosition: Int): BrowserHistoryDispatch? {
    if (delta != -1 && delta != 1) return null
    val target = tab.entries.getOrNull(tab.position + delta) ?: return null
    if (runCatching { requireBrowserUrl(target) }.isFailure) return null
    val nativeTarget = nativeEntries.getOrNull(nativePosition + delta)
    return BrowserHistoryDispatch(target, delta.takeIf { nativeTarget == target })
}
