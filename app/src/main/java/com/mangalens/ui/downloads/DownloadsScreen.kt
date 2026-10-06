package com.mangalens.ui.downloads

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
            manager.enqueue(url, "MangaLens media", quality = quality)
        }.exceptionOrNull()?.message
    }

    fun pause(id: String) = viewModelScope.launch { runCatching { manager.pause(id) }.onFailure { error = it.message } }
    fun resume(id: String) = viewModelScope.launch { runCatching { manager.resume(id) }.onFailure { error = it.message } }
    fun cancel(id: String) = viewModelScope.launch { manager.cancel(id) }
    fun remove(id: String) = viewModelScope.launch { manager.remove(id) }
    fun clearError() { error = null }
}

@Composable
fun DownloadsScreen(onBack: () -> Unit, vm: DownloadsViewModel = viewModel()) {
    val context = LocalContext.current
    val items by vm.items.collectAsState()
    var url by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf(DownloadQuality.P2160) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Download Room", style = MaterialTheme.typography.headlineMedium)
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text(
            "Paste a YouTube, Instagram, direct video, image or media-page URL. MangaLens selects the best source-supported quality up to your selected ceiling.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Video / image / page URL") }
        )

        Spacer(Modifier.height(10.dp))
        Text("QUALITY CEILING", style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DownloadQuality.selectable.forEach { option ->
                FilterChip(
                    selected = quality == option,
                    onClick = { quality = option },
                    label = { Text(option.label) }
                )
            }
        }
        Text(
            "480p → 4K • YouTube/Instagram extraction runs locally • source-limited automatically • no DRM/private/paywall bypass",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        Button(
            onClick = {
                vm.enqueue(url.trim(), quality)
                url = ""
            },
            enabled = url.trim().startsWith("http://") || url.trim().startsWith("https://"),
            modifier = Modifier.fillMaxWidth()
        ) { Text("DOWNLOAD") }

        vm.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(items, key = { it.id }) { item ->
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                        Text(item.mimeType, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(item.state.name, color = MaterialTheme.colorScheme.onSurfaceVariant)

                        if (item.progressPercent >= 0f && item.state != DownloadState.COMPLETED) {
                            LinearProgressIndicator(
                                progress = { item.progress },
                                Modifier.fillMaxWidth().padding(top = 8.dp)
                            )
                            Text(
                                "%.0f%% • extracting/downloading best available media".format(item.progressPercent.coerceIn(0f, 100f)),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else if (item.totalBytes > 0L) {
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

                        item.error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error)
                        }

                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            when (item.state) {
                                DownloadState.DOWNLOADING, DownloadState.QUEUED ->
                                    TextButton(onClick = { vm.pause(item.id) }) { Text("Pause") }
                                DownloadState.PAUSED, DownloadState.FAILED ->
                                    TextButton(onClick = { vm.resume(item.id) }) { Text("Resume") }
                                DownloadState.COMPLETED -> {
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
