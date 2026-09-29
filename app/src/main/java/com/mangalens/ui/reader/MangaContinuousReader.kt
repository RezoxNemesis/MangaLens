package com.mangalens.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mangalens.core.reader.ChapterPage
import kotlinx.coroutines.delay

@Composable
fun MangaContinuousReader(
    title: String,
    pages: List<ChapterPage>,
    translated: Boolean,
    translating: Boolean = false,
    error: String? = null,
    overlays: Map<Int, List<TranslationOverlay>>,
    targetLanguage: String = "hi",
    onTargetLanguageChanged: (String) -> Unit = {},
    onTranslate: () -> Unit,
    onDownload: () -> Unit,
    onMenu: () -> Unit,
    onLongPressPage: (ChapterPage) -> Unit,
    modifier: Modifier = Modifier
) {
    var hudVisible by remember { mutableStateOf(true) }
    var scale by remember { mutableFloatStateOf(1f) }
    var autoScroll by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var languageMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val transformState = rememberTransformableState { zoom, _, _ ->
        scale = (scale * zoom).coerceIn(1f, 4f)
        hudVisible = true
    }

    LaunchedEffect(autoScroll, speed) {
        while (autoScroll) {
            listState.scrollBy(3.5f * speed)
            delay(16L)
        }
    }

    LaunchedEffect(hudVisible) {
        if (hudVisible) {
            delay(2000)
            hudVisible = false
        }
    }

    Box(
        modifier = modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(onTap = { hudVisible = !hudVisible })
        }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
                .transformable(transformState)
                .graphicsLayer(scaleX = scale, scaleY = scale)
        ) {
            items(pages, key = { it.index }) { page ->
                Box(
                    modifier = Modifier.fillMaxWidth().pointerInput(page.index) {
                        detectTapGestures(onLongPress = { onLongPressPage(page) })
                    }
                ) {
                    AsyncImage(
                        model = page.localPath ?: page.sourceUrl,
                        contentDescription = "Page ${page.index}",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth
                    )
                    MangaTranslationOverlay(
                        overlays = overlays[page.index].orEmpty(),
                        modifier = Modifier.matchParentSize()
                    )
                }
            }
        }

        if (error != null) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 76.dp, start = 12.dp, end = 12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(error, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }

        AnimatedVisibility(
            visible = hudVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().statusBarsPadding(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "$title • Page ${if (pages.isEmpty()) 0 else listState.firstVisibleItemIndex + 1} / ${pages.size}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Row {
                        if (translated) Text("Translated", color = MaterialTheme.colorScheme.secondary)
                        TextButton(onClick = onMenu) { Text("⋮") }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = hudVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
        ) {
            Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = onTranslate, enabled = !translating) {
                            Text(if (translating) "Translating…" else "Translate chapter")
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { languageMenu = true }) { Text("Language") }
                        Button(onClick = { autoScroll = !autoScroll }) {
                            Text(if (autoScroll) "Pause scroll" else "Auto-scroll")
                        }
                        Button(onClick = onDownload) { Text("Download chapter") }
                    }
                    if (languageMenu) {
                        androidx.compose.material3.DropdownMenu(expanded = true, onDismissRequest = { languageMenu = false }) {
                            listOf("hi" to "Hindi", "en" to "English", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese", "es" to "Spanish", "fr" to "French").forEach { (code, name) ->
                                androidx.compose.material3.DropdownMenuItem(text = { Text(name) }, onClick = { onTargetLanguageChanged(code); languageMenu = false })
                            }
                        }
                    }
                    if (autoScroll) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(speed.toInt().toString() + "x")
                            Slider(value = speed, onValueChange = { speed = it }, valueRange = 1f..5f, steps = 3)
                        }
                    }
                }
            }
        }
    }
}
