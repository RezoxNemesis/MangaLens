package com.mangalens.ui.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun RecentVideoCard(entry: RecentVideoEntry, store: RecentVideoStore, onOpen: (String) -> Unit) {
    com.mangalens.ui.components.Panel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium)
            Text(when {
                entry.watched -> "Watched"
                entry.canContinue -> "Continue at ${entry.positionMs / 60_000}:${((entry.positionMs / 1000) % 60).toString().padStart(2, '0')}"
                entry.source.kind == RecentVideoKind.ONLINE -> "Online source • resolves again when opened"
                else -> "Saved local video"
            }, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ onOpen(entry.key) }) { Text(if (entry.canContinue) "Continue video" else "Open video") }
                TextButton({ store.favorite(entry.key, !entry.favorite) }) { Text(if (entry.favorite) "Unfavorite" else "Favorite") }
            }
            TextButton({ store.remove(entry.key) }) { Text("Remove from history") }
        }
    }
}
