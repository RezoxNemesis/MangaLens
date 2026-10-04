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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mangalens.core.reader.ChapterPage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce

@OptIn(kotlinx.coroutines.FlowPreview::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun MangaContinuousReader(
    title: String,
    pages: List<ChapterPage>,
    chapterId: String = "",
    initialPosition: Int = 0,
    initialOffset: Int = 0,
    onPositionChanged: (String, Int, Int) -> Unit = { _, _, _ -> },
    loading: Boolean = false,
    translated: Boolean,
    translating: Boolean = false,
    error: String? = null,
    overlays: Map<Int, List<TranslationOverlay>>,
    targetLanguage: String = "hi",
    onTargetLanguageChanged: (String) -> Unit = {},
    translationStyle: String = "natural",
    onTranslationStyleChanged: (String) -> Unit = {},
    onBack: () -> Unit = {},
    onTranslate: () -> Unit,
    onDownload: () -> Unit,
    onMenu: () -> Unit,
    onRetry: () -> Unit = {},
    onOpenWeb: () -> Unit = {},
    onLongPressPage: (ChapterPage) -> Unit,
    modifier: Modifier = Modifier
) {
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("mangalens_reader", android.content.Context.MODE_PRIVATE)
    var textScale by remember { mutableFloatStateOf(prefs.getFloat("text_scale", 1f)) }
    var controls by remember { mutableStateOf(false) }
    var styleMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var positionRestored by rememberSaveable(chapterId) { mutableStateOf(false) }
    var originalVisible by remember { mutableStateOf(false) }
    var hudVisible by remember { mutableStateOf(true) }
    var scale by rememberSaveable(chapterId) { mutableFloatStateOf(1f) }
    var panX by rememberSaveable(chapterId) { mutableFloatStateOf(0f) }
    var panY by rememberSaveable(chapterId) { mutableFloatStateOf(0f) }
    var autoScroll by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var languageMenu by remember { mutableStateOf(false) }
    val currentPages by rememberUpdatedState(pages)
    val currentPositionCallback by rememberUpdatedState(onPositionChanged)
    val listState = rememberLazyListState()
    val modeKey = "mode_" + title.substringBefore("Chapter", title).trim().ifBlank { chapterId }
    var readingMode by rememberSaveable(chapterId) { mutableStateOf(prefs.getString(modeKey, prefs.getString("default_mode", "vertical")) ?: "vertical") }
    var activePage by rememberSaveable(chapterId) { mutableIntStateOf(initialPosition.coerceAtLeast(0)) }
    var activeOffset by rememberSaveable(chapterId) { mutableIntStateOf(initialOffset.coerceAtLeast(0)) }
    val pagerState = rememberPagerState(initialPage = activePage.coerceIn(0, pages.lastIndex.coerceAtLeast(0))) { pages.size }
    val currentMode by rememberUpdatedState(readingMode)
    fun goToPage(position: Int) {
        if (pages.isEmpty()) return
        scope.launch {
            val target = position.coerceIn(0, pages.lastIndex)
            if (readingMode == "vertical") listState.animateScrollToItem(target)
            else pagerState.animateScrollToPage(target)
        }
    }
    val transformState = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 4f)
        if (scale > 1f) {
            panX = (panX + pan.x).coerceIn(-1000f, 1000f)
            panY = (panY + pan.y).coerceIn(-1000f, 1000f)
        } else { panX = 0f; panY = 0f }
        hudVisible = true
    }

    LaunchedEffect(chapterId, readingMode, pages.isNotEmpty()) {
        if (pages.isEmpty()) return@LaunchedEffect
        val position = activePage.coerceIn(0, pages.lastIndex)
        if (readingMode == "vertical") listState.scrollToItem(position, activeOffset)
        else pagerState.scrollToPage(position)
        positionRestored = true
    }
    LaunchedEffect(chapterId, readingMode, positionRestored) {
        if (!positionRestored) return@LaunchedEffect
        snapshotFlow {
            if (readingMode == "vertical") listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            else pagerState.settledPage to 0
        }.debounce(300).collect { (position, offset) ->
            if (currentPages.isNotEmpty()) {
                activePage = position.coerceIn(0, currentPages.lastIndex)
                activeOffset = offset
                currentPositionCallback(chapterId, activePage, offset)
            }
        }
    }
    var zoomPage by rememberSaveable(chapterId) { mutableIntStateOf(activePage) }
    LaunchedEffect(pagerState.settledPage) {
        if (readingMode != "vertical" && zoomPage != pagerState.settledPage) { scale = 1f; panX = 0f; panY = 0f; zoomPage = pagerState.settledPage }
    }
    DisposableEffect(chapterId) { onDispose {
        if (positionRestored && currentPages.isNotEmpty()) {
            val position = if (currentMode == "vertical") listState.firstVisibleItemIndex else pagerState.settledPage
            currentPositionCallback(chapterId, position, if (currentMode == "vertical") listState.firstVisibleItemScrollOffset else 0)
        }
    } }
    LaunchedEffect(autoScroll, speed) {
        while (autoScroll && readingMode == "vertical") {
            listState.scrollBy(3.5f * speed)
            delay(16L)
        }
    }

    LaunchedEffect(hudVisible, controls) {
        if (hudVisible) {
            delay(4000)
            if (!controls) hudVisible = false
        }
    }

    Box(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).pointerInput(Unit) {
            detectTapGestures(onTap = { hudVisible = !hudVisible })
        }
    ) {
        if (readingMode == "vertical") {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().clipToBounds()
                    .transformable(transformState)
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = panX, translationY = panY)
            ) {
                items(pages, key = { it.sourceUrl }) { page ->
                    if (page.error != null) {
                        PageLoadError(page, onRetry)
                        return@items
                    }
                    Box(Modifier.fillMaxWidth().pointerInput(page.sourceUrl) {
                        detectTapGestures(onTap = { hudVisible = !hudVisible },
                            onDoubleTap = { scale = if (scale > 1f) 1f else 2f; panX = 0f; panY = 0f },
                            onLongPress = { onLongPressPage(page) })
                    }) {
                        AsyncImage(model = page.localPath ?: page.sourceUrl,
                            contentDescription = "Page ${page.index}", modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.FillWidth)
                        if (translated && !originalVisible) MangaTranslationOverlay(
                            overlays = overlays[page.index].orEmpty(), textScale = textScale, modifier = Modifier.matchParentSize())
                    }
                }
            }
        } else {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                HorizontalPager(state = pagerState, reverseLayout = readingMode == "rtl", beyondViewportPageCount = 1,
                    userScrollEnabled = scale <= 1f, modifier = Modifier.fillMaxSize()) { position ->
                    val page = pages[position]
                    if (page.error != null) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { PageLoadError(page, onRetry) }
                        return@HorizontalPager
                    }
                    FittedMangaPage(page, modifier = Modifier.fillMaxSize().clipToBounds()
                        .transformable(transformState)
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = panX, translationY = panY)
                        .pointerInput(page.sourceUrl, readingMode) {
                            detectTapGestures(onTap = { tap ->
                                if (scale > 1f || tap.x in size.width * .25f..size.width * .75f) hudVisible = !hudVisible
                                else {
                                    val next = (tap.x > size.width / 2) != (readingMode == "rtl")
                                    goToPage(position + if (next) 1 else -1)
                                }
                            }, onDoubleTap = { scale = if (scale > 1f) 1f else 2f; panX = 0f; panY = 0f },
                                onLongPress = { onLongPressPage(page) })
                        }) {
                        if (translated && !originalVisible) MangaTranslationOverlay(
                            overlays = overlays[page.index].orEmpty(), textScale = textScale, modifier = Modifier.matchParentSize())
                    }
                }
            }
        }

        if (pages.isEmpty()) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (loading) androidx.compose.material3.CircularProgressIndicator()
                Text(if (loading) "Loading chapter pages…" else "No pages loaded. Open a chapter URL or import images from Home.")
            }
        }
        if (error != null) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 76.dp, start = 12.dp, end = 12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(error, style = MaterialTheme.typography.bodySmall)
                    Row { TextButton(onClick = onRetry) { Text("Retry") }; TextButton(onClick = onOpenWeb) { Text("Open in Web") } }
                }
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
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    androidx.compose.material3.IconButton(onBack) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Page ${if (pages.isEmpty()) 0 else activePage + 1} / ${pages.size}", style = MaterialTheme.typography.labelSmall)
                    }
                    Row {
                        if (translated) Text("Translated", color = MaterialTheme.colorScheme.secondary)
                        TextButton(onClick = { controls = !controls }) { Text("Reader tools") }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = hudVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).navigationBarsPadding()
        ) {
            Surface(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton({ goToPage(activePage - 1) }, enabled = pages.isNotEmpty() && activePage > 0) { Text("‹ Prev") }
                        Text("${if (pages.isEmpty()) 0 else activePage + 1} / ${pages.size}", style = MaterialTheme.typography.labelMedium)
                        TextButton({ goToPage(activePage + 1) }, enabled = pages.isNotEmpty() && activePage < pages.lastIndex) { Text("Next ›") }
                    }
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(pages, key = { it.index }) { page ->
                            AsyncImage(page.localPath ?: page.sourceUrl, "Jump to page ${page.index}", Modifier.size(38.dp, 50.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(5.dp)).clickable { goToPage(pages.indexOf(page)) }, contentScale = ContentScale.Crop)
                        }
                    }
                    if (controls) {
                        Text("Reading mode", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("vertical" to "Vertical scroll", "ltr" to "Horizontal LTR", "rtl" to "Horizontal RTL").forEach { (mode, label) ->
                                androidx.compose.material3.FilterChip(selected = readingMode == mode, onClick = {
                                    // activePage is the stable logical page across pager direction changes.
                                    // reverseLayout can transiently report a different settledPage while RTL/LTR
                                    // is being recomposed, which used to jump back a page when returning vertical.
                                    if (readingMode == "vertical") {
                                        activePage = listState.firstVisibleItemIndex
                                        activeOffset = listState.firstVisibleItemScrollOffset
                                    } else {
                                        activeOffset = 0
                                    }
                                    positionRestored = false
                                    autoScroll = false
                                    scale = 1f; panX = 0f; panY = 0f
                                    readingMode = mode
                                    prefs.edit().putString(modeKey, mode).putString("default_mode", mode).apply()
                                }, label = { Text(label) })
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Translation", style = MaterialTheme.typography.titleSmall)
                            androidx.compose.material3.Switch(translated && !originalVisible, { enabled -> if (enabled) { originalVisible = false; if (!translated) onTranslate() } else originalVisible = true })
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton({ languageMenu = true }) { Text("Language: ${targetLanguage.uppercase()} ▾") }
                            Box {
                                TextButton({ styleMenu = true }) { Text("Style: $translationStyle ▾") }
                                androidx.compose.material3.DropdownMenu(styleMenu, { styleMenu = false }) {
                                    listOf("natural", "faithful", "casual", "formal", "webtoon").forEach { style -> androidx.compose.material3.DropdownMenuItem(text = { Text(style.replaceFirstChar { it.uppercase() }) }, onClick = { onTranslationStyleChanged(style); styleMenu = false }) }
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Text size", style = MaterialTheme.typography.bodySmall)
                            Slider(textScale, { textScale = it; prefs.edit().putFloat("text_scale", it).apply() }, Modifier.weight(1f), valueRange = .75f..1.5f)
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onTranslate, enabled = !translating && pages.isNotEmpty()) { Text(if (translating) "Translating…" else "Translate") }
                        TextButton({ originalVisible = !originalVisible }) { Text(if (originalVisible) "Translation" else "Original") }
                        if (readingMode == "vertical") TextButton({ autoScroll = !autoScroll }) { Text(if (autoScroll) "Pause scroll" else "Auto-scroll") }
                        if (controls) { TextButton(onDownload) { Text("Download") }; TextButton(onMenu) { Text("More settings") } }
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

/** Keep overlays in image coordinates, including letterboxing in paged mode. */
@Composable
private fun FittedMangaPage(page: ChapterPage, modifier: Modifier, overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    var ratio by remember(page.localPath) { mutableFloatStateOf(1f) }
    LaunchedEffect(page.localPath) {
        ratio = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            page.localPath?.let { android.graphics.BitmapFactory.decodeFile(it, options) }
            if (options.outWidth > 0 && options.outHeight > 0) options.outWidth.toFloat() / options.outHeight else 1f
        }
    }
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val width = minOf(maxWidth, maxHeight * ratio)
        val height = width / ratio
        Box(Modifier.size(width, height)) {
            AsyncImage(page.localPath ?: page.sourceUrl, "Page ${page.index}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            overlay()
        }
    }
}

@Composable
private fun PageLoadError(page: ChapterPage, retry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Page ${page.index} could not load", style = MaterialTheme.typography.titleMedium)
        Text(page.error.orEmpty(), style = MaterialTheme.typography.bodySmall)
        TextButton(retry) { Text("Retry page loading") }
    }
}
