package com.mangalens.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mangalens.R
import com.mangalens.core.model.ContentType
import com.mangalens.ui.MangaLensUiState
import com.mangalens.ui.components.*

@Composable
fun HomeScreen(state: MangaLensUiState, onUrlChanged: (String) -> Unit, onPaste: () -> Unit,
    onModeSelected: (ContentType) -> Unit, onIngest: () -> Unit, onOpenReader: () -> Unit,
    onOpenVideo: () -> Unit, onOpenDownloads: () -> Unit, onOpenChapter: (String) -> Unit,
    onImportImages: (List<Uri>) -> Unit, onOpenSavedChapter: (String) -> Unit,
    onOpenOrez: () -> Unit, onOpenLibrary: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableStateOf("For you") }
    var showLink by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) onImportImages(it) }
    val chapters = state.library.filter { it.title.contains(query, true) && (tab != "Bookmarks" || it.bookmarked) }
    if (showLink) AlertDialog(onDismissRequest = { showLink = false }, title = { Text("Open a link") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(state.url, onUrlChanged, singleLine = true, placeholder = { Text("Chapter, video or web URL") }, trailingIcon = { TextButton(onPaste) { Text("Paste") } })
            listOf(ContentType.IMAGE_CHAPTER to "Manga / Manhwa", ContentType.VIDEO_STREAM to "Video", ContentType.GENERIC_WEB to "Web page").forEach { (mode, label) ->
                Row { RadioButton(state.mode == mode, { onModeSelected(mode) }); Text(label, Modifier.padding(top = 13.dp)) }
            }
        }
    }, confirmButton = { Button({ showLink = false; onIngest() }, enabled = state.url.isNotBlank() && !state.loading) { Text("Open content") } }, dismissButton = { TextButton({ showLink = false }) { Text("Cancel") } })
    LazyColumn(
        Modifier.fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = .055f),
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item { BrandHeader("MangaLens 2.0", "READ BEYOND LANGUAGE", action = {
            IconButton({ showLink = true }) { Icon(Icons.Outlined.Link, "Open link") }
            IconButton(onOpenOrez) { Icon(Icons.Outlined.SmartToy, "Ask Orez") }
        }) }
        item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) }, placeholder = { Text("Search your manga & chapters") }, shape = RoundedCornerShape(18.dp)) }
        item { LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf("For you", "Recent", "Bookmarks")) { label -> FilterChip(tab == label, { tab = label }, label = { Text(label) }) }
        } }
        if (query.isBlank() && tab == "For you") item { ArtworkHero("Your next chapter awaits", "Import your stories. Read and translate on your device.", R.drawable.home_artwork, "Import & read") { picker.launch(com.mangalens.core.reader.DocumentImporter.MIME_TYPES) } }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NeonActionTile("Open link", "Paste URL to read or play", Icons.Outlined.Link, Modifier.weight(1f)) { showLink = true }
                NeonActionTile("Downloads", "Saved files & media", Icons.Outlined.FileDownload, Modifier.weight(1f), onClick = onOpenDownloads)
            }
        }
        state.library.firstOrNull()?.takeIf { query.isBlank() && tab == "For you" }?.let { chapter ->
            item { Column(verticalArrangement = Arrangement.spacedBy(9.dp)) { Text("Continue reading", style = MaterialTheme.typography.titleMedium); ContinueCard(chapter) { onOpenSavedChapter(chapter.id) } } }
        }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (tab == "Bookmarks") "Your bookmarks" else "Recently saved", style = MaterialTheme.typography.titleMedium)
            TextButton(onOpenLibrary, contentPadding = PaddingValues(0.dp)) { Text("View all →") }
        } }
        if (chapters.isNotEmpty()) item {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(chapters.take(20), key = { it.id }) { chapter ->
                    ChapterCover(chapter, { onOpenSavedChapter(chapter.id) }, Modifier.width(112.dp))
                }
            }
        } else item {
            Panel {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.CollectionsBookmark, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(34.dp))
                    Column {
                        Text(if (query.isNotBlank()) "No matching chapters" else if (tab == "Bookmarks") "Bookmark a story you love" else "Bring your first story", style = MaterialTheme.typography.titleMedium)
                        Text("Your library becomes richer as you read.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("Images, ZIP, CBZ and PDF chapters are supported. Your saved pages appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button({ picker.launch(com.mangalens.core.reader.DocumentImporter.MIME_TYPES) }, shape = RoundedCornerShape(14.dp)) { Text("Import chapter →") }
            }
        }
        if (state.chapters.isNotEmpty()) item { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Chapters from your source", style = MaterialTheme.typography.titleMedium)
            state.chapters.take(20).forEach { chapter -> TextButton({ onOpenChapter(chapter.url) }) { Text(chapter.title.ifBlank { "Chapter" }) } }
        } }
        item {
            Panel {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(32.dp))
                    Column {
                        Text("Meet Orez 2.0", style = MaterialTheme.typography.titleMedium)
                        Text("Hybrid story assistant, OCR helper and web-backed companion.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Button(onOpenOrez, shape = RoundedCornerShape(14.dp)) { Text("Talk to Orez →") }
            }
        }
        state.videoUrl?.let {
            item {
                NeonActionTile("Resume video", "Continue in MangaLens Video", Icons.Outlined.PlayCircle, Modifier.fillMaxWidth(), onClick = onOpenVideo)
            }
        }
        state.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        if (state.loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Preparing chapter…") }
    }
}
