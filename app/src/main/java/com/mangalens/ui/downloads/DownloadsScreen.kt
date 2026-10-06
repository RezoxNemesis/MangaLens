package com.mangalens.ui.downloads

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mangalens.download.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DownloadsViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val manager = MediaDownloadManager(app)
    val items = manager.downloads.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    var error by mutableStateOf<String?>(null)
        private set

    fun enqueue(url: String, quality: DownloadQuality) = viewModelScope.launch {
        error = runCatching {
            manager.enqueue(url, title = null, quality = quality)
        }.exceptionOrNull()?.message
    }

    fun pause(id: String) = viewModelScope.launch { runCatching { manager.pause(id) }.onFailure { error = it.message } }
    fun resume(id: String) = viewModelScope.launch { runCatching { manager.resume(id) }.onFailure { error = it.message } }
    fun cancel(id: String) = viewModelScope.launch { manager.cancel(id) }
    fun remove(id: String) = viewModelScope.launch { manager.remove(id) }
    fun clearError() { error = null }
}

@Composable
fun DownloadsScreen(onBack: () -> Unit, appState: com.mangalens.ui.MangaLensUiState, onImport: (List<Uri>) -> Unit, onOpenLibrary: () -> Unit, onOpenTools: () -> Unit, onPlayVideo: (String) -> Unit, onPauseTranslation: (Boolean) -> Unit, onCancelTranslation: () -> Unit, vm: DownloadsViewModel = viewModel()) {
    val context = LocalContext.current
    val items by vm.items.collectAsState()
    var url by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf(DownloadQuality.BEST) }

    var tab by remember { mutableStateOf("Downloads") }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) onImport(it) }
    Column(
        Modifier.fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = .045f),
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        com.mangalens.ui.components.BrandHeader("Download Room", "BEST AVAILABLE QUALITY • RESUMABLE") {
            TextButton(onClick = onBack) { Text("Back") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                "Downloads" to "Media",
                "Local files" to "Files",
                "Offline packs" to "Offline"
            ).forEach { (value, label) ->
                FilterChip(
                    selected = tab == value,
                    onClick = { tab = value },
                    label = { Text(label, maxLines = 1) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        com.mangalens.ui.components.Panel(Modifier.fillMaxWidth()) {
            Text("Import & translate", style = MaterialTheme.typography.titleMedium)
            Text("Images · ZIP · CBZ · PDF", color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({ picker.launch(com.mangalens.core.reader.DocumentImporter.MIME_TYPES) }) { Text("Choose a chapter →") }
        }
        if (appState.translating) {
            com.mangalens.ui.components.Panel(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Translation queue", style = MaterialTheme.typography.titleMedium)
                Text((appState.activeChapter?.title ?: "Chapter") + " · ${appState.translationDone}/${appState.translationTotal} pages processed")
                LinearProgressIndicator(progress = { appState.translationDone.toFloat() / appState.translationTotal.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                if (appState.translating) Row {
                    TextButton({ onPauseTranslation(!appState.translationPaused) }) { Text(if (appState.translationPaused) "Resume" else "Pause after page") }
                    TextButton(onCancelTranslation) { Text("Cancel") }
                }
            }
        }
        if (tab == "Local files") {
            com.mangalens.ui.components.Panel(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text("${appState.library.size} saved chapters · ${appState.library.sumOf { it.pages.size }} pages")
                TextButton(onOpenLibrary) { Text("Open library →") }
            }
            return@Column
        }
        if (tab == "Offline packs") {
            com.mangalens.ui.components.Panel(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text("On-device OCR is included. Translation languages download when first used.")
                TextButton(onOpenTools) { Text("Manage Orez data and translation →") }
            }
            return@Column
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Video / image / page URL") }
        )

        Spacer(Modifier.height(10.dp))
        var qualityMenu by remember { mutableStateOf(false) }
        Box {
            TextButton({ qualityMenu = true }) { Text("Quality ceiling: ${quality.label} ▾") }
            DropdownMenu(qualityMenu, { qualityMenu = false }) {
                DownloadQuality.selectable.forEach { option -> DropdownMenuItem(text = { Text(option.label) }, onClick = { quality = option; qualityMenu = false }) }
            }
        }
        Button(
            onClick = {
                vm.enqueue(url.trim(), quality)
                url = ""
            },
            enabled = url.trim().startsWith("http://") || url.trim().startsWith("https://"),
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
        ) { Text(if (quality == DownloadQuality.BEST) "DOWNLOAD BEST AVAILABLE" else "DOWNLOAD " + quality.label) }

        vm.error?.let {
            val friendly = when {
                it.contains("could not find an accessible video/audio source", ignoreCase = true) ->
                    "Couldn’t resolve a downloadable media stream yet. If the video plays in Web, start it briefly and use Download there so MangaLens can reuse the live website session."
                it.contains("HTTP 403", ignoreCase = true) ->
                    "The media link expired or rejected this request. Resume to refresh the source, or reopen the page and download again."
                else -> it
            }
            Text(friendly, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            TextButton(onClick = vm::clearError) { Text("Dismiss") }
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (items.isEmpty()) item { Text("No media downloads yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(items, key = { it.id }) { item ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                        val sourceHost = remember(item.sourcePageUrl, item.sourceUrl) {
                            runCatching {
                                java.net.URI(item.sourcePageUrl ?: item.sourceUrl).host?.removePrefix("www.")
                            }.getOrNull().orEmpty()
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AssistChip(
                                onClick = {},
                                enabled = false,
                                label = { Text(item.provider.ifBlank { "media" }) }
                            )
                            if (sourceHost.isNotBlank()) {
                                AssistChip(onClick = {}, enabled = false, label = { Text(sourceHost, maxLines = 1) })
                            }
                            item.requestedHeight?.let { requested ->
                                AssistChip(
                                    onClick = {},
                                    enabled = false,
                                    label = { Text(if (requested >= 9_000) "Best available" else "${requested}p") }
                                )
                            }
                        }
                        Text(item.mimeType, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        item.stage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        item.actualHeight?.let { Text("Verified output: ${it}p", style = MaterialTheme.typography.labelMedium) }
                        Text(item.state.name, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        if (item.totalBytes > 0L) {
                            LinearProgressIndicator(
                                progress = { item.progress },
                                Modifier.fillMaxWidth().padding(top = 8.dp)
                            )
                            Text(
                                formatBytes(item.bytesDownloaded) + " / " + formatBytes(item.totalBytes) +
                                    " • remaining " + formatBytes((item.totalBytes - item.bytesDownloaded).coerceAtLeast(0L))
                            )
                        } else {
                            Text(formatBytes(item.bytesDownloaded) + " downloaded")
                        }

                        item.error?.let { failure ->
                            val friendly = when {
                                failure.contains("HTTP 403", ignoreCase = true) ->
                                    "Source expired or rejected the transfer. Resume to refresh it."
                                failure.contains("HTML", ignoreCase = true) ->
                                    "The source returned a webpage instead of media. Reopen the source and retry."
                                else -> failure
                            }
                            Text(friendly, color = MaterialTheme.colorScheme.error)
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            when (item.state) {
                                DownloadState.DOWNLOADING, DownloadState.QUEUED ->
                                    TextButton(onClick = { vm.pause(item.id) }) { Text("Pause") }
                                DownloadState.PAUSED, DownloadState.FAILED ->
                                    TextButton(onClick = { vm.resume(item.id) }) { Text("Resume") }
                                DownloadState.COMPLETED -> {
                                    if (item.isAdaptive) {
                                        TextButton(onClick = { onPlayVideo(item.sourceUrl) }) { Text("Play offline") }
                                    } else {
                                        item.destination?.let { destination ->
                                            TextButton(onClick = {
                                                val uri = Uri.parse(destination)
                                                val type = if (item.isVideo) "video/*" else if (item.mimeType.startsWith("image/")) "image/*" else "*/*"
                                                val intent = Intent(Intent.ACTION_VIEW).apply {
                                                    setDataAndType(uri, type)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                runCatching {
                                                    context.startActivity(Intent.createChooser(intent, "Open with…"))
                                                }
                                            }) { Text("Open") }
                                        }
                                    }
                                }
                                else -> Unit
                            }
                            if (item.state != DownloadState.COMPLETED) {
                                TextButton(onClick = { vm.cancel(item.id) }) { Text("Cancel") }
                            }
                            TextButton(onClick = { vm.remove(item.id) }) { Text("Remove") }
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return bytes.toString() + " B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return "%.1f %s".format(value, units[index])
}
