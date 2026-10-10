package com.mangalens.ui.home

import com.mangalens.core.reader.libraryTextKey
import com.mangalens.core.router.UrlEngineRouter
import com.mangalens.ui.web.BrowserVisit
import com.mangalens.ui.web.BrowserWorkspaceLimits
import com.mangalens.ui.web.BrowserWorkspaceSnapshot
import java.net.URI
import java.util.Locale

/** Stored browser metadata is untrusted display data, never Orez instructions. */
internal object HomeBrowserShortcutPolicy {
    fun host(visit: BrowserVisit): String? = runCatching {
        require(UrlEngineRouter.isSafeWebUrl(visit.url))
        URI(visit.url).host?.lowercase(Locale.ROOT)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    fun rows(snapshot: BrowserWorkspaceSnapshot?, query: String, sitesOnly: Boolean,
        limit: Int = 6): List<BrowserVisit> {
        require(limit in 1..8)
        val key = libraryTextKey(query.take(160))
        val visits = snapshot?.history.orEmpty().take(BrowserWorkspaceLimits.HISTORY)
            .filter { host(it)?.let { site -> key.isBlank() || key in libraryTextKey(it.title.take(512)) || key in libraryTextKey(site) } == true }
            .sortedByDescending { it.visitedAt }
        return (if (sitesOnly) visits.distinctBy(::host) else visits).take(limit)
    }

    fun isCurrent(snapshot: BrowserWorkspaceSnapshot?, captured: BrowserVisit): Boolean =
        host(captured) != null && snapshot?.history.orEmpty().take(BrowserWorkspaceLimits.HISTORY).any { it == captured }
}
