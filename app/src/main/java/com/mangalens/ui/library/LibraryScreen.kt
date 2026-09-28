package com.mangalens.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mangalens.ui.MangaLensUiState

@Composable
fun LibraryScreen(
    state: MangaLensUiState,
    onOpenReader: () -> Unit,
    onOpenLocalVideo: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Library", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 16.dp))
            Text("Chapters, local media and offline playback.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Local Media Player", style = MaterialTheme.typography.titleLarge)
                    Text("Open videos from internal storage, SD cards or document providers.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = onOpenLocalVideo, modifier = Modifier.padding(top = 10.dp)) { Text("Browse videos") }
                }
            }
        }
        if (state.pages.isEmpty()) {
            item { Text("No saved chapter pages yet.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp)) }
        } else {
            item {
                ElevatedCard(onClick = onOpenReader, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Current chapter")
                        Text(state.pages.size.toString() + " pages", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(state.pages.take(20), key = { it.index }) { page ->
                Text("Page " + page.index, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
    }
}
