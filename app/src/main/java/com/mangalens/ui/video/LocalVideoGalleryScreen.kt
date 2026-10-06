package com.mangalens.ui.video

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun LocalVideoGalleryScreen(
    onOpenPlayer: (Uri) -> Unit,
    onOpenSystem: () -> Unit,
    onOpenExternal: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mediaPermission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_VIDEO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    var hasMediaPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, mediaPermission) == PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionRequested by remember { mutableStateOf(false) }
    var videos by remember { mutableStateOf(emptyList<LocalVideoItem>()) }
    var scanError by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMediaPermission = granted
        permissionRequested = true
    }

    LaunchedEffect(Unit) {
        if (!hasMediaPermission && !permissionRequested) {
            permissionRequested = true
            permissionLauncher.launch(mediaPermission)
        }
    }

    LaunchedEffect(hasMediaPermission) {
        if (!hasMediaPermission) {
            videos = emptyList()
            return@LaunchedEffect
        }
        val result = withContext(Dispatchers.IO) {
            runCatching { LocalVideoCatalog(context).scan() }
        }
        result.onSuccess {
            videos = it
            scanError = null
        }.onFailure {
            videos = emptyList()
            scanError = it.message ?: "Unable to read the device video library."
        }
    }

    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(170.dp),
            modifier = Modifier.fillMaxSize().padding(14.dp),
            contentPadding = PaddingValues(bottom = 96.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text("Video Vault", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (hasMediaPermission) {
                            videos.size.toString() + " local videos • device + SD media"
                        } else {
                            "Grant video access for the indexed gallery, or use the system picker."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = onOpenSystem) { Text("System picker") }
                        if (!hasMediaPermission) {
                            Button(onClick = { permissionLauncher.launch(mediaPermission) }) {
                                Text("Allow video access")
                            }
                        }
                    }
                    scanError?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            items(videos, key = { it.id }) { video ->
                Card(
                    Modifier.fillMaxWidth().clickable { onOpenPlayer(video.uri) },
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .72f)
                    )
                ) {
                    Column {
                        Box(Modifier.fillMaxWidth().height(120.dp)) {
                            AsyncImage(
                                video.uri,
                                video.name,
                                Modifier.fillMaxSize().clip(
                                    RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                                ),
                                contentScale = ContentScale.Crop
                            )
                            Text(
                                formatDuration(video.durationMs),
                                Modifier.align(Alignment.BottomStart)
                                    .padding(8.dp)
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(
                                                MaterialTheme.colorScheme.surface.copy(alpha = .9f),
                                                MaterialTheme.colorScheme.surface.copy(alpha = .4f)
                                            )
                                        ),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .padding(7.dp),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Column(Modifier.padding(12.dp)) {
                            Text(video.name, maxLines = 2, style = MaterialTheme.typography.titleSmall)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                SuggestionChip(
                                    onClick = {},
                                    enabled = false,
                                    label = { Text(codecTag(video.mimeType)) }
                                )
                                Text(
                                    formatSize(video.sizeBytes),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { onOpenExternal(video.uri) }) {
                                Text("Open externally")
                            }
                        }
                    }
                }
            }
        }

        if (videos.isEmpty() && hasMediaPermission && scanError == null) {
            Card(
                Modifier.align(Alignment.Center).padding(24.dp),
                shape = RoundedCornerShape(28.dp)
            ) {
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("No indexed videos", style = MaterialTheme.typography.titleLarge)
                    Text("Use the system picker to open a video from any document provider.")
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onOpenSystem) { Text("Choose video") }
                }
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}

private fun formatSize(bytes: Long): String =
    if (bytes < 1024L * 1024L) {
        ((bytes / 1024.0).roundToInt()).toString() + " KB"
    } else {
        "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

private fun codecTag(mime: String): String = when {
    mime.contains("hevc", true) -> "HEVC"
    mime.contains("av01", true) -> "AV1"
    mime.contains("vp9", true) -> "VP9"
    mime.contains("webm", true) -> "WEBM"
    mime.contains("quicktime", true) -> "MOV"
    mime.contains("matroska", true) -> "MKV"
    else -> mime.substringAfter('/').uppercase().take(8).ifBlank { "VIDEO" }
}
