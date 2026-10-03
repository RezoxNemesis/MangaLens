package com.mangalens.ui.home

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import com.mangalens.R
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import androidx.compose.ui.unit.dp
import com.mangalens.core.model.ContentType
import com.mangalens.ui.MangaLensUiState

@Composable
fun HomeScreen(
    state: MangaLensUiState,
    onUrlChanged: (String) -> Unit,
    onPaste: () -> Unit,
    onModeSelected: (ContentType) -> Unit,
    onIngest: () -> Unit,
    onOpenReader: () -> Unit,
    onOpenVideo: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenChapter: (String) -> Unit,
    onImportImages: (List<Uri>) -> Unit
) {
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
            }
            onImportImages(uris)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(painterResource(R.drawable.mangalens_logo), contentDescription = "MangaLens logo", modifier = Modifier.size(58.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "MangaLens",
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    "Read beyond language.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(50)
            ) {
                Text(
                    "OCR  ·  TRANSLATE",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        state.activeChapter?.takeIf { state.pages.isNotEmpty() }?.let { chapter ->
            Card(onClick = onOpenReader, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp)) {
                Box(Modifier.fillMaxWidth().height(210.dp)) {
                    AsyncImage(model = state.pages.firstOrNull()?.localPath, contentDescription = null,
                        contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                    Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF208080B)))))
                    Column(Modifier.align(Alignment.BottomStart).padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("CONTINUE READING", color = Color(0xFFFF667D), style = MaterialTheme.typography.labelLarge)
                        Text(chapter.title, color = Color.White, style = MaterialTheme.typography.headlineSmall, maxLines = 2)
                        Text("Page ${(chapter.position + 1).coerceAtMost(state.pages.size)} of ${state.pages.size} • Saved offline", color = Color.White)
                    }
                }
            }
        }

        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Open a chapter or media link", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = state.url,
                    onValueChange = onUrlChanged,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Paste a chapter, video or web URL") },
                    trailingIcon = {
                        TextButton(onClick = onPaste) { Text("Paste") }
                    }
                )
                Text("Open as", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        ContentType.IMAGE_CHAPTER to "Manga / Manhwa",
                        ContentType.VIDEO_STREAM to "Video",
                        ContentType.GENERIC_WEB to "Web page"
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = state.mode == mode,
                            onClick = { onModeSelected(mode) },
                            label = { Text(label) }
                        )
                    }
                }
                Button(
                    onClick = onIngest,
                    enabled = state.url.isNotBlank() && !state.loading,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (state.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Opening…")
                    } else {
                        Text("Open content")
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeading("Quick actions", "Choose how you want to start")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickActionCard(
                    title = "Import images",
                    detail = "OCR and translate",
                    modifier = Modifier.weight(1f),
                    onClick = { imagePicker.launch(arrayOf("image/*")) }
                )
                QuickActionCard(
                    title = "Downloads",
                    detail = "Manage media",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenDownloads
                )
            }
        }

        if (state.chapters.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeading("Discovered chapters", "${state.chapters.size} available")
                state.chapters.take(20).forEach { chapter ->
                    ElevatedCard(
                        onClick = { onOpenChapter(chapter.url) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(chapter.title.ifBlank { "Chapter" }, fontWeight = FontWeight.SemiBold)
                            Text(
                                chapter.url,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionHeading("Continue", "Pick up where you left off")
            if (state.pages.isNotEmpty()) {
                ElevatedCard(
                    onClick = onOpenReader,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Row(
                        Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Chapter reader", fontWeight = FontWeight.Bold)
                            Text(
                                "${state.pages.size} pages ready",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Button(onClick = onOpenReader) { Text("Read") }
                    }
                }
            }
            state.videoUrl?.let {
                OutlinedButton(onClick = onOpenVideo, modifier = Modifier.fillMaxWidth()) {
                    Text("Resume video")
                }
            }
            if (state.pages.isEmpty() && state.videoUrl == null) {
                Text(
                    "Your imported chapters and media will appear here.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }

        state.error?.let { message ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    message,
                    modifier = Modifier.padding(14.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QuickActionCard(
    title: String,
    detail: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    ElevatedCard(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
