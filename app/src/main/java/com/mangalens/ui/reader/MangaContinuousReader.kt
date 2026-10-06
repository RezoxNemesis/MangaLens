package com.mangalens.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mangalens.core.reader.ChapterPage
import kotlinx.coroutines.delay

private enum class ReaderMode(val label: String) {
    VERTICAL("Vertical"),
    HORIZONTAL_LTR("Paged LTR"),
    HORIZONTAL_RTL("Paged RTL")
}

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
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var autoScroll by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var languageMenu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var modeName by rememberSaveable { mutableStateOf(ReaderMode.VERTICAL.name) }

    val readerMode = ReaderMode.entries.firstOrNull { it.name == modeName } ?: ReaderMode.VERTICAL
    val verticalState = rememberLazyListState()
    val horizontalState = rememberLazyListState()
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp

    val transformState = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 4f)
        if (scale <= 1.01f) {
            panX = 0f
            panY = 0f
        } else {
            panX = (panX + pan.x).coerceIn(-1600f, 1600f)
            panY = (panY + pan.y).coerceIn(-1600f, 1600f)
        }
        hudVisible = true
    }

    LaunchedEffect(autoScroll, speed, readerMode) {
        while (autoScroll) {
            when (readerMode) {
                ReaderMode.VERTICAL -> verticalState.scrollBy(3.5f * speed)
                ReaderMode.HORIZONTAL_LTR,
                ReaderMode.HORIZONTAL_RTL -> horizontalState.scrollBy(3.5f * speed)
            }
            delay(16L)
        }
    }

    LaunchedEffect(hudVisible) {
        if (hudVisible) {
            delay(2400L)
            hudVisible = false
        }
    }

    LaunchedEffect(readerMode) {
        scale = 1f
        panX = 0f
        panY = 0f
        autoScroll = false
    }

    val currentPage = when (readerMode) {
        ReaderMode.VERTICAL -> verticalState.firstVisibleItemIndex
        ReaderMode.HORIZONTAL_LTR,
        ReaderMode.HORIZONTAL_RTL -> horizontalState.firstVisibleItemIndex
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { hudVisible = !hudVisible })
            }
    ) {
        val readerTransform = Modifier
            .fillMaxSize()
            .transformable(transformState)
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = panX,
                translationY = panY
            )

        when (readerMode) {
            ReaderMode.VERTICAL -> {
                LazyColumn(
                    state = verticalState,
                    modifier = readerTransform
                ) {
                    items(pages, key = { it.index }) { page ->
                        VerticalPage(
                            page = page,
                            overlays = overlays[page.index].orEmpty(),
                            onLongPress = { onLongPressPage(page) }
                        )
                    }
                }
            }

            ReaderMode.HORIZONTAL_LTR,
            ReaderMode.HORIZONTAL_RTL -> {
                LazyRow(
                    state = horizontalState,
                    reverseLayout = readerMode == ReaderMode.HORIZONTAL_RTL,
                    modifier = readerTransform
                ) {
                    items(pages, key = { it.index }) { page ->
                        Box(
                            modifier = Modifier
                                .width(screenWidth)
                                .fillParentMaxHeight()
                                .pointerInput(page.index) {
                                    detectTapGestures(
                                        onLongPress = { onLongPressPage(page) }
                                    )
                                }
                        ) {
                            AsyncImage(
                                model = page.localPath ?: page.sourceUrl,
                                contentDescription = "Page " + page.index,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit
                            )
                            MangaTranslationOverlay(
                                overlays = overlays[page.index].orEmpty(),
                                modifier = Modifier.matchParentSize()
                            )
                        }
                    }
                }
            }
        }

        if (pages.isEmpty() && error == null) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                shape = MaterialTheme.shapes.large,
                tonalElevation = 3.dp
            ) {
                Column(
                    Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(10.dp))
                    Text("Waiting for chapter pages…")
                }
            }
        }

        if (error != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 76.dp, start = 12.dp, end = 12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    error,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
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
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                        Text(
                            "Page " + (if (pages.isEmpty()) 0 else currentPage + 1) +
                                " / " + pages.size + " • " + readerMode.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (translated) {
                            Text("Translated", color = MaterialTheme.colorScheme.secondary)
                        }
                        TextButton(onClick = onMenu) { Text("⋮") }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = hudVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp)
        ) {
            Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
                Column(
                    Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Button(
                            onClick = onTranslate,
                            enabled = !translating
                        ) {
                            Text(if (translating) "Translating…" else "Translate")
                        }
                        TextButton(onClick = { languageMenu = true }) {
                            Text(targetLanguage.uppercase())
                        }
                        TextButton(onClick = { modeMenu = true }) {
                            Text(readerMode.label)
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TextButton(onClick = { autoScroll = !autoScroll }) {
                            Text(if (autoScroll) "Pause auto" else "Auto-scroll")
                        }
                        TextButton(onClick = {
                            scale = 1f
                            panX = 0f
                            panY = 0f
                        }) {
                            Text("Reset zoom")
                        }
                        TextButton(onClick = onDownload) {
                            Text("Download")
                        }
                    }

                    if (languageMenu) {
                        DropdownMenu(
                            expanded = true,
                            onDismissRequest = { languageMenu = false }
                        ) {
                            listOf(
                                "hi" to "Hindi",
                                "en" to "English",
                                "ja" to "Japanese",
                                "ko" to "Korean",
                                "zh" to "Chinese",
                                "es" to "Spanish",
                                "fr" to "French"
                            ).forEach { (code, name) ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        onTargetLanguageChanged(code)
                                        languageMenu = false
                                    }
                                )
                            }
                        }
                    }

                    if (modeMenu) {
                        DropdownMenu(
                            expanded = true,
                            onDismissRequest = { modeMenu = false }
                        ) {
                            ReaderMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label) },
                                    onClick = {
                                        modeName = mode.name
                                        modeMenu = false
                                    }
                                )
                            }
                        }
                    }

                    if (autoScroll) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(speed.toInt().toString() + "x")
                            Slider(
                                value = speed,
                                onValueChange = { speed = it },
                                valueRange = 1f..5f,
                                steps = 3,
                                modifier = Modifier.width(150.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VerticalPage(
    page: ChapterPage,
    overlays: List<TranslationOverlay>,
    onLongPress: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(page.index) {
                detectTapGestures(onLongPress = { onLongPress() })
            }
    ) {
        AsyncImage(
            model = page.localPath ?: page.sourceUrl,
            contentDescription = "Page " + page.index,
            modifier = Modifier.fillMaxWidth(),
            contentScale = ContentScale.FillWidth
        )
        MangaTranslationOverlay(
            overlays = overlays,
            modifier = Modifier.matchParentSize()
        )
    }
}
