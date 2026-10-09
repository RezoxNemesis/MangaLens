package com.mangalens.ui.web

internal object BrowserWorkspaceLimits {
    const val TABS = 16
    const val TAB_ENTRIES = 64
    const val HISTORY = 300
    const val BOOKMARKS = 250
    const val URL_CHARS = 8192
    const val TITLE_CHARS = 512
    const val ENCODED_BYTES = 8 * 1024 * 1024
}

internal data class BrowserTab(
    val id: String,
    val entries: List<String> = emptyList(),
    val position: Int = -1,
    val title: String = "",
    val desktop: Boolean = false
) {
    val url: String get() = entries.getOrNull(position).orEmpty()
    val canGoBack: Boolean get() = position > 0
    val canGoForward: Boolean get() = position >= 0 && position < entries.lastIndex
}

internal data class BrowserVisit(val url: String, val title: String, val visitedAt: Long, val visits: Int = 1)
internal data class BrowserBookmark(val id: String, val url: String, val title: String, val createdAt: Long)
internal data class BrowserWorkspaceSnapshot(
    val tabs: List<BrowserTab>,
    val activeTabId: String,
    val history: List<BrowserVisit> = emptyList(),
    val bookmarks: List<BrowserBookmark> = emptyList(),
    val revision: Long = 0,
    val notice: String? = null,
    val lastReportedUrl: String = ""
) {
    val activeTab: BrowserTab get() = tabs.first { it.id == activeTabId }
}
