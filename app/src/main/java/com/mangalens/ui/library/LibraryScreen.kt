package com.mangalens.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mangalens.core.reader.*
import com.mangalens.ui.MangaLensUiState
import com.mangalens.ui.components.*

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(state: MangaLensUiState, onDeleteChapter: (String) -> Unit, onOpenSavedChapter: (String) -> Unit,
    onOpenReader: () -> Unit, onOpenLocalVideo: () -> Unit,
    onChapterDetails: (String, Boolean, ReadingStatus) -> Unit) {
    var pendingDelete by remember { mutableStateOf<SavedChapter?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("All") }
    val filters = listOf("All", "Reading", "Completed", "On hold", "Bookmarks")
    fun matches(chapter: SavedChapter, label: String) = when (label) { "All" -> true; "Bookmarks" -> chapter.bookmarked; else -> chapter.readingStatus.label == label }
    val chapters = state.library.filter { matches(it, filter) && it.title.contains(query, true) }
    pendingDelete?.let { chapter ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("Delete saved chapter?") },
            text = { Text("Remove ${chapter.title} and its offline pages? Shared pages are retained.") },
            confirmButton = { TextButton({ onDeleteChapter(chapter.id); pendingDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton({ pendingDelete = null }) { Text("Cancel") } })
    }
    LazyVerticalGrid(GridCells.Adaptive(100.dp), Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { BrandHeader("My Library", "YOUR STORIES, YOUR PACE") }
        item(span = { GridItemSpan(maxLineSpan) }) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search your library") }, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
        }
        state.library.firstOrNull { it.readingStatus == ReadingStatus.READING }?.let { chapter ->
            item(span = { GridItemSpan(maxLineSpan) }) { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Continue reading", style = MaterialTheme.typography.titleMedium)
                ContinueCard(chapter) { onOpenSavedChapter(chapter.id) }
            } }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                filters.forEach { label ->
                    FilterChip(
                        selected = filter == label,
                        onClick = { filter = label },
                        label = { Text("$label (${state.library.count { matches(it, label) }})", maxLines = 1) }
                    )
                }
            }
        }
        if (chapters.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) { Panel {
            Text(if (state.library.isEmpty()) "Your library starts here" else "No matching chapters", style = MaterialTheme.typography.titleMedium)
            Text("Import a chapter or open a manga link from Home. Bookmarks and reading lists are saved on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        items(chapters, key = { it.id }) { chapter ->
            var menu by remember { mutableStateOf(false) }
            ChapterCover(chapter, { onOpenSavedChapter(chapter.id) }, menu = {
                IconButton({ menu = true }) { Icon(Icons.Outlined.MoreVert, "Chapter actions for ${chapter.title}", tint = androidx.compose.ui.graphics.Color.White) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(if (chapter.bookmarked) "Remove bookmark" else "Bookmark") }, onClick = { onChapterDetails(chapter.id, !chapter.bookmarked, chapter.readingStatus); menu = false })
                    ReadingStatus.entries.forEach { status -> DropdownMenuItem(text = { Text(status.label) }, onClick = { onChapterDetails(chapter.id, chapter.bookmarked, status); menu = false }) }
                    DropdownMenuItem(text = { Text("Delete offline chapter") }, onClick = { pendingDelete = chapter; menu = false })
                }
            })
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Panel {
            Text("Local Media Player", style = MaterialTheme.typography.titleMedium)
            Text("Play videos from your device or document provider.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onOpenLocalVideo) { Text("Browse videos →") }
        } }
    }
}
