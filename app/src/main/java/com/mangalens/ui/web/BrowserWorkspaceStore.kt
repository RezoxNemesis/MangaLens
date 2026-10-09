package com.mangalens.ui.web

import com.mangalens.core.router.UrlEngineRouter
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal interface BrowserWorkspaceIo {
    fun read(maxBytes: Int): ByteArray?
    fun write(bytes: ByteArray)
}

internal class BrowserWorkspaceStore(
    private val io: BrowserWorkspaceIo,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var state: BrowserWorkspaceSnapshot = io.read(BrowserWorkspaceLimits.ENCODED_BYTES).let { bytes ->
        if (bytes == null) blankSnapshot()
        else BrowserWorkspaceCodec.decode(bytes) ?: blankSnapshot().copy(
            notice = "Browser session could not be restored. Open a new page to start again."
        )
    }

    @Synchronized fun snapshot(): BrowserWorkspaceSnapshot = state

    @Synchronized fun newTab(url: String = ""): BrowserWorkspaceSnapshot {
        if (url.isNotEmpty()) requireBrowserUrl(url)
        check(state.tabs.size < BrowserWorkspaceLimits.TABS) { "Close a tab before opening another (maximum ${BrowserWorkspaceLimits.TABS})." }
        val tab = newBrowserTab(url)
        return save(state.copy(tabs = state.tabs + tab, activeTabId = tab.id))
    }

    @Synchronized fun selectTab(id: String): BrowserWorkspaceSnapshot {
        require(state.tabs.any { it.id == id }) { "Browser tab no longer exists." }
        return if (id == state.activeTabId) state else save(state.copy(activeTabId = id))
    }

    @Synchronized fun closeTab(id: String): BrowserWorkspaceSnapshot {
        val index = state.tabs.indexOfFirst { it.id == id }
        if (index < 0) return state
        val remaining = state.tabs.filterNot { it.id == id }.ifEmpty { listOf(newBrowserTab()) }
        val selected = if (state.activeTabId == id) remaining[index.coerceAtMost(remaining.lastIndex)].id else state.activeTabId
        return save(state.copy(tabs = remaining, activeTabId = selected))
    }

    @Synchronized fun navigate(id: String, url: String, replace: Boolean = false): BrowserWorkspaceSnapshot {
        requireBrowserUrl(url)
        return updateTab(id) { tab ->
            if (tab.url == url) tab
            else if (replace && tab.position >= 0) tab.copy(
                entries = tab.entries.mapIndexed { index, entry -> if (index == tab.position) url else entry }, title = ""
            )
            else {
                val entries = (tab.entries.take(tab.position + 1) + url).takeLast(BrowserWorkspaceLimits.TAB_ENTRIES)
                tab.copy(entries = entries, position = entries.lastIndex, title = "")
            }
        }
    }

    @Synchronized fun move(id: String, delta: Int): BrowserWorkspaceSnapshot {
        require(delta == -1 || delta == 1) { "Browser history moves one page at a time." }
        return updateTab(id) { tab ->
            val position = tab.position + delta
            if (position !in tab.entries.indices) tab else tab.copy(position = position, title = "")
        }
    }

    /** Only a current accepted main document becomes a visited-history entry. */
    @Synchronized fun commit(id: String, expectedUrl: String, finalUrl: String, title: String): BrowserWorkspaceSnapshot {
        requireBrowserUrl(expectedUrl); requireBrowserUrl(finalUrl)
        val tab = state.tabs.firstOrNull { it.id == id } ?: return state
        if (state.activeTabId != id || tab.url != expectedUrl || !WebPageLoadState.sameDocument(expectedUrl, finalUrl)) return state
        val cleanTitle = browserTitle(title)
        val updated = tab.copy(entries = tab.entries.mapIndexed { index, entry -> if (index == tab.position) finalUrl else entry }, title = cleanTitle)
        val previous = state.history.firstOrNull { it.url == finalUrl }
        val visit = BrowserVisit(finalUrl, cleanTitle, clock().coerceAtLeast(0), ((previous?.visits ?: 0).toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        return save(state.copy(tabs = state.tabs.map { if (it.id == id) updated else it },
            history = (listOf(visit) + state.history.filterNot { it.url == finalUrl }).take(BrowserWorkspaceLimits.HISTORY), lastReportedUrl = finalUrl))
    }

    @Synchronized fun setDesktop(id: String, desktop: Boolean): BrowserWorkspaceSnapshot =
        updateTab(id) { if (it.desktop == desktop) it else it.copy(desktop = desktop) }

    @Synchronized fun toggleBookmark(url: String, title: String): BrowserWorkspaceSnapshot {
        requireBrowserUrl(url)
        val existing = state.bookmarks.firstOrNull { it.url == url }
        if (existing != null) return save(state.copy(bookmarks = state.bookmarks.filterNot { it.id == existing.id }))
        check(state.bookmarks.size < BrowserWorkspaceLimits.BOOKMARKS) { "Remove a bookmark before adding another (maximum ${BrowserWorkspaceLimits.BOOKMARKS})." }
        val bookmark = BrowserBookmark(browserId(), url, browserTitle(title), clock().coerceAtLeast(0))
        return save(state.copy(bookmarks = listOf(bookmark) + state.bookmarks))
    }

    @Synchronized fun removeBookmark(id: String): BrowserWorkspaceSnapshot =
        if (state.bookmarks.none { it.id == id }) state else save(state.copy(bookmarks = state.bookmarks.filterNot { it.id == id }))

    @Synchronized fun clearHistory(): BrowserWorkspaceSnapshot =
        if (state.history.isEmpty()) state else save(state.copy(history = emptyList()))

    private fun updateTab(id: String, transform: (BrowserTab) -> BrowserTab): BrowserWorkspaceSnapshot {
        val old = state.tabs.firstOrNull { it.id == id } ?: return state
        val updated = transform(old)
        return if (old == updated) state else save(state.copy(tabs = state.tabs.map { if (it.id == id) updated else it }))
    }

    private fun save(next: BrowserWorkspaceSnapshot): BrowserWorkspaceSnapshot {
        check(state.revision < Long.MAX_VALUE - 1) { "Browser session revision is exhausted." }
        val durable = next.copy(revision = state.revision + 1, notice = null)
        io.write(BrowserWorkspaceCodec.encode(durable))
        state = durable
        return state
    }
}

internal fun browserId(): String = UUID.randomUUID().toString().replace("-", "")
internal fun newBrowserTab(url: String = ""): BrowserTab =
    BrowserTab(browserId(), if (url.isEmpty()) emptyList() else listOf(url), if (url.isEmpty()) -1 else 0)
private fun blankSnapshot(): BrowserWorkspaceSnapshot = newBrowserTab().let { BrowserWorkspaceSnapshot(listOf(it), it.id) }
internal fun browserTitle(value: String): String = value.map { if (it.code < 32 || it.code == 127) ' ' else it }
    .joinToString("").take(BrowserWorkspaceLimits.TITLE_CHARS).trim()
internal fun requireBrowserUrl(url: String) {
    require(url.length in 1..BrowserWorkspaceLimits.URL_CHARS && url == url.trim() &&
        url.none { it.code < 32 || it.code == 127 } && UrlEngineRouter.isSafeWebUrl(url)) { "Enter a valid HTTP or HTTPS address without account credentials." }
}

internal object BrowserWorkspaceCodec {
    fun encode(state: BrowserWorkspaceSnapshot): ByteArray {
        validate(state)
        val root = JSONObject().put("version", 1).put("revision", state.revision).put("activeTabId", state.activeTabId)
            .put("lastReportedUrl", state.lastReportedUrl)
            .put("tabs", JSONArray(state.tabs.map { tab -> JSONObject().put("id", tab.id)
                .put("entries", JSONArray(tab.entries)).put("position", tab.position).put("title", tab.title).put("desktop", tab.desktop) }))
            .put("history", JSONArray(state.history.map { visit -> JSONObject().put("url", visit.url)
                .put("title", visit.title).put("visitedAt", visit.visitedAt).put("visits", visit.visits) }))
            .put("bookmarks", JSONArray(state.bookmarks.map { bookmark -> JSONObject().put("id", bookmark.id)
                .put("url", bookmark.url).put("title", bookmark.title).put("createdAt", bookmark.createdAt) }))
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= BrowserWorkspaceLimits.ENCODED_BYTES) { "Browser storage limit reached. Clear history or close tabs." }
        return bytes
    }

    fun decode(bytes: ByteArray): BrowserWorkspaceSnapshot? {
        if (bytes.isEmpty() || bytes.size > BrowserWorkspaceLimits.ENCODED_BYTES) return null
        return try {
            val root = JSONObject(String(bytes, Charsets.UTF_8))
            require(integer(root, "version") == 1L)
            val tabs = array(root, "tabs", BrowserWorkspaceLimits.TABS).map { obj ->
                val entries = obj.getJSONArray("entries")
                require(entries.length() <= BrowserWorkspaceLimits.TAB_ENTRIES)
                BrowserTab(string(obj, "id"), (0 until entries.length()).map { entries.get(it) as? String ?: error("Invalid URL type") },
                    integer(obj, "position").also { require(it in -1..BrowserWorkspaceLimits.TAB_ENTRIES.toLong()) }.toInt(),
                    string(obj, "title"), obj.get("desktop") as? Boolean ?: error("Invalid desktop flag"))
            }
            val history = array(root, "history", BrowserWorkspaceLimits.HISTORY).map { obj ->
                BrowserVisit(string(obj, "url"), string(obj, "title"), integer(obj, "visitedAt"),
                    integer(obj, "visits").also { require(it in 1..Int.MAX_VALUE.toLong()) }.toInt())
            }
            val bookmarks = array(root, "bookmarks", BrowserWorkspaceLimits.BOOKMARKS).map { obj ->
                BrowserBookmark(string(obj, "id"), string(obj, "url"), string(obj, "title"), integer(obj, "createdAt"))
            }
            BrowserWorkspaceSnapshot(tabs, string(root, "activeTabId"), history, bookmarks, integer(root, "revision"),
                lastReportedUrl = if (root.has("lastReportedUrl")) string(root, "lastReportedUrl") else "").also(::validate)
        } catch (_: Exception) { null }
    }

    private fun string(root: JSONObject, key: String): String = root.get(key) as? String ?: error("Invalid $key type")
    private fun integer(root: JSONObject, key: String): Long = root.get(key).let {
        require(it is Int || it is Long) { "Invalid $key type" }; (it as Number).toLong()
    }
    private fun array(root: JSONObject, key: String, limit: Int): List<JSONObject> = root.getJSONArray(key).let { array ->
        require(array.length() <= limit); (0 until array.length()).map(array::getJSONObject)
    }
    private fun validate(state: BrowserWorkspaceSnapshot) {
        val token = Regex("[a-f0-9]{32}")
        fun title(value: String) = require(value.length <= BrowserWorkspaceLimits.TITLE_CHARS && value.none { it.code < 32 || it.code == 127 })
        require(state.revision in 0 until Long.MAX_VALUE)
        if (state.lastReportedUrl.isNotEmpty()) requireBrowserUrl(state.lastReportedUrl)
        require(state.tabs.size in 1..BrowserWorkspaceLimits.TABS && state.tabs.map { it.id }.distinct().size == state.tabs.size)
        require(state.tabs.any { it.id == state.activeTabId })
        state.tabs.forEach { tab ->
            require(token.matches(tab.id) && tab.entries.size <= BrowserWorkspaceLimits.TAB_ENTRIES)
            require(if (tab.entries.isEmpty()) tab.position == -1 else tab.position in tab.entries.indices)
            tab.entries.forEach(::requireBrowserUrl); title(tab.title)
        }
        require(state.history.size <= BrowserWorkspaceLimits.HISTORY && state.history.map { it.url }.distinct().size == state.history.size)
        state.history.forEach { requireBrowserUrl(it.url); title(it.title); require(it.visitedAt >= 0 && it.visits > 0) }
        require(state.bookmarks.size <= BrowserWorkspaceLimits.BOOKMARKS && state.bookmarks.map { it.id }.distinct().size == state.bookmarks.size &&
            state.bookmarks.map { it.url }.distinct().size == state.bookmarks.size)
        state.bookmarks.forEach { require(token.matches(it.id) && it.createdAt >= 0); requireBrowserUrl(it.url); title(it.title) }
    }
}
