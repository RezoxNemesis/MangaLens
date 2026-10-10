package com.mangalens.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mangalens.ui.web.BrowserVisit

@Composable
internal fun HomeBrowserSection(title: String, visits: List<BrowserVisit>, loading: Boolean,
    error: String?, enabled: Boolean, onOpen: (BrowserVisit) -> Unit, onOpenWeb: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            TextButton(onOpenWeb) { Text("Open Web") }
        }
        if (visits.isEmpty()) Text(if (loading) "Loading browser history…" else "No matching saved visits.",
            style = MaterialTheme.typography.bodySmall)
        visits.forEach { visit ->
            TextButton({ onOpen(visit) }, enabled = enabled,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Open saved browser visit: ${visit.title.take(160)}" }) {
                Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text(visit.title.take(160).ifBlank { HomeBrowserShortcutPolicy.host(visit).orEmpty() },
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(HomeBrowserShortcutPolicy.host(visit).orEmpty(), style = MaterialTheme.typography.bodySmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
