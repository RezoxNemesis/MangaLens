package com.mangalens.ui.web

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal enum class BrowserWorkspacePanel { TABS, TOOLS, HISTORY, BOOKMARKS }

@Composable
internal fun BrowserWorkspaceDialog(
    panel: BrowserWorkspacePanel,
    snapshot: BrowserWorkspaceSnapshot,
    pageUrl: String,
    pageTitle: String,
    ready: Boolean,
    onDismiss: () -> Unit,
    onPanel: (BrowserWorkspacePanel) -> Unit,
    onNewTab: () -> Unit,
    onSelectTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onToggleBookmark: () -> Unit,
    onRemoveBookmark: (String) -> Unit,
    onClearHistory: () -> Unit,
    onFind: () -> Unit,
    onDesktop: () -> Unit,
    onExternal: () -> Unit
) {
    val title = when (panel) {
        BrowserWorkspacePanel.TABS -> "Browser tabs"
        BrowserWorkspacePanel.TOOLS -> "Browser tools"
        BrowserWorkspacePanel.HISTORY -> "History"
        BrowserWorkspacePanel.BOOKMARKS -> "Bookmarks"
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, confirmButton = {
        TextButton(onClick = onDismiss) { Text("Close") }
    }, text = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (panel) {
                BrowserWorkspacePanel.TABS -> {
                    Button(onClick = onNewTab, enabled = snapshot.tabs.size < BrowserWorkspaceLimits.TABS) { Text("New tab") }
                    Text("${snapshot.tabs.size}/${BrowserWorkspaceLimits.TABS} tabs · inactive pages are released", style = MaterialTheme.typography.bodySmall)
                    LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(snapshot.tabs, key = { it.id }) { tab ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f).clickable { onSelectTab(tab.id) }.padding(vertical = 6.dp)
                                    .semantics { contentDescription = "Switch browser tab: ${tab.title.ifBlank { tab.url.ifBlank { "New tab" } }}" }) {
                                    Text(tab.title.ifBlank { "New tab" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (tab.url.isNotBlank()) Text(tab.url, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                    if (tab.id == snapshot.activeTabId) Text("Current tab", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(onClick = { onCloseTab(tab.id) }, modifier = Modifier.semantics {
                                    contentDescription = "Close browser tab: ${tab.title.ifBlank { "New tab" }}"
                                }) { Text("×") }
                            }
                        }
                    }
                }
                BrowserWorkspacePanel.TOOLS -> {
                    Text(pageTitle.ifBlank { "Current page" }, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = onToggleBookmark, enabled = ready && pageUrl.isNotBlank()) {
                        Text(if (snapshot.bookmarks.any { it.url == pageUrl }) "Remove page bookmark" else "Bookmark this page")
                    }
                    TextButton(onClick = { onPanel(BrowserWorkspacePanel.BOOKMARKS) }) { Text("Bookmarks") }
                    TextButton(onClick = { onPanel(BrowserWorkspacePanel.HISTORY) }) { Text("History") }
                    TextButton(onClick = onFind, enabled = ready) { Text("Find on page") }
                    TextButton(onClick = onDesktop) { Text(if (snapshot.activeTab.desktop) "Use mobile site" else "Use desktop site") }
                    TextButton(onClick = onExternal, enabled = pageUrl.isNotBlank()) { Text("Open in external browser") }
                }
                BrowserWorkspacePanel.HISTORY -> {
                    if (snapshot.history.isEmpty()) Text("No visited pages yet.")
                    else TextButton(onClick = onClearHistory) { Text("Clear history") }
                    LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(snapshot.history, key = { it.url }) { visit ->
                            BrowserSavedPage(visit.title, visit.url, "${visit.visits} visit${if (visit.visits == 1) "" else "s"}",
                                onClick = { onOpenUrl(visit.url) })
                        }
                    }
                }
                BrowserWorkspacePanel.BOOKMARKS -> {
                    if (snapshot.bookmarks.isEmpty()) Text("Bookmark a loaded page from Browser tools.")
                    LazyColumn(Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(snapshot.bookmarks, key = { it.id }) { bookmark ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                BrowserSavedPage(bookmark.title, bookmark.url, null, Modifier.weight(1f), { onOpenUrl(bookmark.url) })
                                TextButton(onClick = { onRemoveBookmark(bookmark.id) }, modifier = Modifier.semantics {
                                    contentDescription = "Remove browser bookmark: ${bookmark.title.ifBlank { bookmark.url }}"
                                }) { Text("×") }
                            }
                        }
                    }
                }
            }
        }
    })
}

@Composable
private fun BrowserSavedPage(title: String, url: String, detail: String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title.ifBlank { url }, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(url, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        if (detail != null) Text(detail, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun BrowserFindDialog(
    query: String, searching: Boolean, activeOrdinal: Int, count: Int, error: String?,
    onQuery: (String) -> Unit, onFind: () -> Unit, onNext: (Boolean) -> Unit, onDismiss: () -> Unit
) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Find on page") }, confirmButton = {
        TextButton(onClick = onDismiss) { Text("Close") }
    }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, onQuery, singleLine = true, enabled = !searching,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Find text on page" }, label = { Text("Text to find") })
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if (count >= 0) Text(if (count == 0) "No matches" else "Match ${activeOrdinal.coerceIn(1, count)} of $count")
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onFind, enabled = query.isNotBlank() && !searching) { Text("Find") }
                TextButton(onClick = { onNext(false) }, enabled = count > 0 && !searching) { Text("Previous") }
                TextButton(onClick = { onNext(true) }, enabled = count > 0 && !searching) { Text("Next") }
            }
        }
    })
}
