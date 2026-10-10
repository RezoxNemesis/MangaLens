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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import coil.compose.AsyncImage
import androidx.compose.ui.unit.dp
import com.mangalens.core.reader.ChapterPage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull

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
    promoPages: Set<Int> = emptySet(),
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
    modifier: Modifier = Modifier,
    translatedBackgrounds: Map<Int, String> = emptyMap(),
    translationDone: Int = 0,
    translationTotal: Int = 0,
    translationPaused: Boolean = false,
    onTranslationPaused: (Boolean) -> Unit = {},
    onTranslationCancelled: () -> Unit = {},
    onRetryPage: (ChapterPage) -> Unit = { onRetry() },
    onVisiblePage: (Int?) -> Unit = {},
    onCorrectPage: ((Int) -> Unit)? = null,
    personalOverlays: Map<Int, Map<Int, com.mangalens.core.translation.PersonalMangaLettering>> = emptyMap(),
    onSavedBubble: ((Int, Int, com.mangalens.core.translation.SavedMangaLettering) -> Unit)? = null,
    onVisiblePages: (Set<Int>) -> Unit = {},
    readerPresentationEpoch: Long? = null,
    onManuallyHiddenPages: (Set<Int>) -> Unit = {},
    ocrDiagnostics: Map<Int, com.mangalens.core.translation.SavedPageOcrDiagnostics> = emptyMap()
) {
    val density = LocalDensity.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferenceScope = rememberCoroutineScope()
    val displayPreferences = remember(context.applicationContext) { ReaderDisplayPreferences(context.applicationContext) }
    var displayOptions by rememberSaveable(chapterId, stateSaver = mapSaver<ReaderDisplayOptions>(
        save = { ReaderDisplayOptionsCodec.encode(it) }, restore = { ReaderDisplayOptionsCodec.decode(it) })) {
        mutableStateOf(ReaderDisplayOptions())
    }
    var displayPreferencesApplied by rememberSaveable(chapterId) { mutableStateOf(false) }
    LaunchedEffect(chapterId) {
        if (!displayPreferencesApplied) {
            val loaded = displayPreferences.load()
            if (!displayPreferencesApplied) { displayOptions = loaded; displayPreferencesApplied = true }
        }
    }
    var peekOriginal by remember(chapterId) { mutableStateOf(false) }
    val prefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("mangalens_reader", android.content.Context.MODE_PRIVATE)
    var textScale by remember { mutableFloatStateOf(prefs.getFloat("text_scale", 1f)) }
    var controls by remember { mutableStateOf(false) }
    var showOcrDiagnostics by remember(chapterId) { mutableStateOf(false) }
    fun recordedDiagnostics(page: ChapterPage) = ocrDiagnostics[page.index]?.takeIf { showOcrDiagnostics && ReaderOcrDiagnosticsPolicy.matchesRevision(it, page.contentRevision) }
    var styleMenu by remember { mutableStateOf(false) }
    var originalVisible by rememberSaveable(chapterId) { mutableStateOf(false) }
    var hudVisible by remember { mutableStateOf(true) }
    var headerHeight by remember { mutableStateOf(76.dp) }
    val translationActive = translating || translationPaused
    var scale by rememberSaveable(chapterId) { mutableFloatStateOf(1f) }
    var panX by rememberSaveable(chapterId) { mutableFloatStateOf(0f) }
    var panY by rememberSaveable(chapterId) { mutableFloatStateOf(0f) }
    var autoScroll by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var languageMenu by remember { mutableStateOf(false) }
    val detectedPromoPages = promoPages + pages.filter { page -> page.promotionHint?.matches(page) == true }.map { it.index }
    var hidePromos by rememberSaveable(chapterId) { mutableStateOf(prefs.getBoolean("hide_promos", true)) }
    var revealedPromoPages by remember(chapterId) { mutableStateOf(emptySet<Int>()) }
    // Explicit personal presentation only; leaves originals and Native journals untouched.
    var manuallyHiddenPages by remember(chapterId) { mutableStateOf(emptyMap<Int, ReaderManualPageHide>()) }
    val currentPages by key(chapterId) { rememberUpdatedState(pages) }
    fun manuallyHidden(page: ChapterPage) = manuallyHiddenPages[page.index]?.matches(chapterId, page) == true
    fun pageHidden(page: ChapterPage) = manuallyHidden(page) ||
        hidePromos && page.index in detectedPromoPages && page.index !in revealedPromoPages
    val manualHidingCallback by rememberUpdatedState(onManuallyHiddenPages)
    fun revealPage(page: ChapterPage) {
        if (displayOptions.controlsLocked) return
        val selected = manuallyHiddenPages[page.index]
        val current = currentPages.singleOrNull { it.index == page.index }
        if (selected != null && current != null && selected.matches(chapterId, page) && selected.matches(chapterId, current)) {
            manuallyHiddenPages = manuallyHiddenPages - page.index
            manualHidingCallback(currentPages.filter(::manuallyHidden).map { it.index }.toSet())
        }
        // Automatic-promo reveal keeps its existing behavior; it cannot override a different manual selection.
        revealedPromoPages = revealedPromoPages + page.index
    }
    val manuallyHiddenPageIndices = pages.filter(::manuallyHidden).map { it.index }.toSet()
    LaunchedEffect(chapterId, manuallyHiddenPageIndices) { manualHidingCallback(manuallyHiddenPageIndices) }
    val originalShown = originalVisible || peekOriginal || displayOptions.comparison == ReaderComparison.ORIGINAL
    val comparison = if (originalShown || !translated) ReaderComparison.ORIGINAL else displayOptions.comparison
    val customDisplay = displayOptions.marginCrop > 0f || comparison == ReaderComparison.SIDE_BY_SIDE || comparison == ReaderComparison.SPLIT
    val currentPositionCallback by key(chapterId) { rememberUpdatedState(onPositionChanged) }
    // Metadata belongs to the chapter, so a layout-mode switch cannot recreate zero-height pages.
    // Only bounds/managed-surface checks run here; bitmap decoding stays with visible images.
    val pageSurfaces = pages.associate { page ->
        page.index to key(chapterId, page.index, page.sourceUrl) {
            rememberReaderPageSurface(page, translatedBackgrounds[page.index], translated && !originalShown)
        }
    }
    val geometryChecked = pages.isNotEmpty() && pageSurfaces.values.all { it.geometryChecked }
    val currentGeometryChecked by key(chapterId) { rememberUpdatedState(geometryChecked) }
    val listState = rememberLazyListState()
    val horizontalListState = rememberLazyListState()
    val modeKey = "mode_" + title.substringBefore("Chapter", title).trim().ifBlank { chapterId }
    var positionState by rememberSaveable(chapterId, stateSaver = listSaver<ReaderPositionState, Any>(
        save = { listOf(it.mode, it.page, it.offset) },
        restore = { ReaderPositionState.initial(it[0] as String, it[1] as Int, it[2] as Int) }
    )) {
        mutableStateOf(ReaderPositionState.initial(
            prefs.getString(modeKey, prefs.getString("default_mode", "vertical")) ?: "vertical",
            initialPosition, initialOffset))
    }
    val readingMode = positionState.mode
    val configuration = LocalConfiguration.current
    val layout = ReaderPageLayout(readingMode, pages.size, configuration.screenWidthDp >= configuration.screenHeightDp, displayOptions.spreadRtl)
    ReaderWindowEffect(chapterId, displayOptions.effectiveWindow(readingMode))
    val activePage = positionState.page
    var guidedLastPage by remember(chapterId) { mutableStateOf<Int?>(null) }
    val guidedOwner = if (readingMode == "guided") pages.getOrNull(activePage)?.takeIf { it.localPath != null && !manuallyHidden(it) }?.let { page ->
        ReaderGuidedOwner(chapterId, page.index, requireNotNull(page.localPath), page.contentRevision,
            readerPresentationEpoch, displayOptions.spreadRtl)
    } else null
    val guidedSession = rememberReaderGuidedSession(guidedOwner, guidedLastPage == activePage) {
        if (guidedLastPage == activePage) guidedLastPage = null
    }
    val positionRestored = !positionState.restoring
    // Each direction gets a fresh layout, seeded by the accepted logical page.
    val pagerState = key(chapterId, readingMode, if (readingMode == "spread") layout.columns else 1) {
        rememberPagerState(initialPage = layout.physicalForLogical(activePage)) { layout.physicalCount }
    }
    val currentViewport by key(chapterId) { rememberUpdatedState({
        when (readingMode) {
            "vertical" -> listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            "horizontal" -> horizontalListState.firstVisibleItemIndex to horizontalListState.firstVisibleItemScrollOffset
            else -> layout.logicalForPhysical(pagerState.settledPage, positionState.page) to 0
        }
    }) }
    fun changeDisplay(next: ReaderDisplayOptions) {
        val previous = displayOptions
        displayPreferencesApplied = true
        displayOptions = next
        if (previous.marginCrop != next.marginCrop || previous.comparison != next.comparison || previous.pageSpacingDp != next.pageSpacingDp) {
            val physical = currentViewport()
            positionState = positionState.relayout(physical.first, physical.second, pages.size)
        }
        if (next.controlsLocked) { controls = false; hudVisible = false; languageMenu = false; styleMenu = false; autoScroll = false; peekOriginal = false }
        preferenceScope.launch(start = CoroutineStart.UNDISPATCHED) { displayPreferences.save(next) }
    }
    LaunchedEffect(chapterId, readingMode, layout.columns) {
        if (readingMode == "spread") positionState = positionState.relayout(positionState.page, 0, pages.size)
    }
    fun observeReader(action: String, expectedRequest: Long? = null, targetPage: Int? = null,
                      physicalOverride: Pair<Int, Int>? = null, physicalMode: String = readingMode) {
        if (!ReaderNavigationDiagnostics.enabled) return
        runCatching {
            val physical = physicalOverride ?: currentViewport()
            ReaderNavigationDiagnostics.observe(ReaderNavigationObservation(action, chapterId, positionState,
                physicalMode, physical.first, physical.second, geometryChecked, controls, hudVisible,
                expectedRequest, targetPage))
        }
    }
    // Scalar-only fixture diagnostics. The refreshed closure keeps long-lived input
    // handlers from reporting an earlier chapter/mode/geometry snapshot.
    val hudReaderInstance = remember(chapterId, title) { ReaderHudDiagnostics.readerInstance(chapterId, title) }
    val hudTimerSequence = remember(chapterId) { java.util.concurrent.atomic.AtomicLong() }
    val latestHudReporter by rememberUpdatedState({ phase: ReaderHudPhase, writer: ReaderHudWriter,
        previousHud: Boolean?, previousTools: Boolean?, timerSequence: Long,
        zoomDelta: Float?, panDeltaX: Float?, panDeltaY: Float?, component: ReaderHudComponent,
        animationCurrent: ReaderHudAnimation, animationTarget: ReaderHudAnimation, animationRunning: Boolean? ->
        if (ReaderHudDiagnostics.enabled) {
            val position = positionState
            val physical = currentViewport()
            val snapshot = ReaderHudSnapshot(ReaderHudMode.from(position.mode), position.page,
                physical.second, position.request, position.restoring, currentGeometryChecked,
                controls, hudVisible, translating || translationPaused, scale, panX, panY, hudReaderInstance, physical.first)
            ReaderHudDiagnostics.observe(chapterId, title, ReaderHudObservation(phase, snapshot, writer,
                previousHud, previousTools, timerSequence, zoomDelta, panDeltaX, panDeltaY,
                component, animationCurrent, animationTarget, animationRunning))
        }
    })
    fun observeHud(phase: ReaderHudPhase, writer: ReaderHudWriter = ReaderHudWriter.NONE,
        previousHud: Boolean? = null, previousTools: Boolean? = null, timerSequence: Long = 0,
        zoomDelta: Float? = null, panDeltaX: Float? = null, panDeltaY: Float? = null,
        component: ReaderHudComponent = ReaderHudComponent.NONE,
        animationCurrent: ReaderHudAnimation = ReaderHudAnimation.NONE,
        animationTarget: ReaderHudAnimation = ReaderHudAnimation.NONE, animationRunning: Boolean? = null) {
        if (!ReaderHudDiagnostics.enabled) return
        runCatching { latestHudReporter(phase, writer, previousHud, previousTools, timerSequence,
            zoomDelta, panDeltaX, panDeltaY, component, animationCurrent, animationTarget, animationRunning) }
    }

    fun goToPage(position: Int, action: String = "page_command") {
        observeReader(action, targetPage = position)
        positionState = positionState.navigate(position, pages.size)
        observeReader("page_command_accepted", targetPage = position)
    }
    val currentPageCommand by rememberUpdatedState({ ordinal: Int, action: String -> goToPage(ordinal, action) })
    fun advanceGuided(delta: Int) {
        if (displayOptions.controlsLocked) return
        val step = guidedSession?.advance(delta) ?: ReaderGuidedStep(0, delta)
        if (step.pageDelta != 0) {
            val destination = activePage + step.pageDelta
            if (destination in pages.indices) {
                guidedLastPage = destination.takeIf { step.pageDelta < 0 }
                goToPage(destination, "guided_panel_page")
            }
        }
        scale = 1f; panX = 0f; panY = 0f
    }
    fun toggleGuidedWhole() {
        if (displayOptions.controlsLocked) return
        guidedSession?.let { if (it.wholePage) it.showPanel() else it.showWhole() }
        scale = 1f; panX = 0f; panY = 0f
    }
    val transformState = rememberTransformableState { zoom, pan, _ ->
        if (displayOptions.controlsLocked) return@rememberTransformableState
        val previousHud = hudVisible
        scale = (scale * zoom).coerceIn(1f, 4f)
        if (scale > 1f) {
            panX = (panX + pan.x).coerceIn(-1000f, 1000f)
            panY = (panY + pan.y).coerceIn(-1000f, 1000f)
        } else { panX = 0f; panY = 0f }
        hudVisible = true
        observeHud(ReaderHudPhase.WRITE, ReaderHudWriter.TRANSFORM, previousHud = previousHud,
            zoomDelta = zoom, panDeltaX = pan.x, panDeltaY = pan.y)
    }

    LaunchedEffect(chapterId, readingMode, positionState.request, pages.isNotEmpty(), geometryChecked) {
        if (pages.isEmpty() || !geometryChecked) return@LaunchedEffect
        val captured = positionState
        val target = captured.page.coerceIn(0, pages.lastIndex)
        observeReader("restoration_started", captured.request, target)
        try {
            if (captured.mode == "vertical") {
                if (captured.animate) listState.animateScrollToItem(target, captured.offset)
                else listState.scrollToItem(target, captured.offset)
            } else if (captured.mode == "horizontal") {
                if (captured.animate) horizontalListState.animateScrollToItem(target, captured.offset)
                else horizontalListState.scrollToItem(target, captured.offset)
            } else {
                val physicalTarget = layout.physicalForLogical(target)
                if (captured.animate) pagerState.animateScrollToPage(physicalTarget)
                else pagerState.scrollToPage(physicalTarget)
            }
        } finally {
            // A user gesture may cancel animation. A newer command may cancel this effect.
            // Only the matching request can accept the actual viewport in either case.
            val physical = when (captured.mode) {
                "vertical" -> listState.firstVisibleItemIndex
                "horizontal" -> horizontalListState.firstVisibleItemIndex
                else -> layout.logicalForPhysical(pagerState.settledPage, captured.page)
            }
            val offset = when (captured.mode) { "vertical" -> listState.firstVisibleItemScrollOffset; "horizontal" -> horizontalListState.firstVisibleItemScrollOffset; else -> 0 }
            positionState = positionState.restored(captured, physical, offset, currentPages.size,
                geometryChecked = currentGeometryChecked)
            observeReader("restoration_finished", captured.request, target, physical to offset, captured.mode)
        }
    }
    LaunchedEffect(chapterId, readingMode, positionState.request, positionRestored, geometryChecked) {
        if (!positionRestored || !geometryChecked) return@LaunchedEffect
        val captured = positionState
        snapshotFlow {
            currentViewport()
        }.collect { (position, offset) ->
            val previous = positionState
            positionState = positionState.observed(captured.request, captured.mode, position, offset, currentPages.size)
            if (positionState != previous) observeReader("viewport_observed", captured.request)
        }
    }
    LaunchedEffect(chapterId) {
        snapshotFlow { positionState.checkpoint() }.filterNotNull().debounce(300).collect { checkpoint ->
            if (positionState.acceptsCheckpoint(checkpoint) && currentPages.isNotEmpty()) {
                currentPositionCallback(chapterId, checkpoint.page, checkpoint.offset)
            }
        }
    }
    var zoomPage by rememberSaveable(chapterId) { mutableIntStateOf(activePage) }
    LaunchedEffect(pagerState.settledPage, horizontalListState.firstVisibleItemIndex) {
        val page = if (readingMode == "horizontal") horizontalListState.firstVisibleItemIndex else layout.logicalForPhysical(pagerState.settledPage, positionState.page)
        if (readingMode != "vertical" && zoomPage != page) { scale = 1f; panX = 0f; panY = 0f; zoomPage = page }
    }
    DisposableEffect(chapterId) { onDispose {
        if (currentPages.isNotEmpty()) {
            // An accepted command is saved even if route disposal interrupts its animation.
            val position = positionState
            val (physical, offset) = currentViewport()
            currentPositionCallback(chapterId,
                if (position.restoring || !currentGeometryChecked) position.page.coerceIn(0, currentPages.lastIndex) else physical,
                if (position.restoring || !currentGeometryChecked) position.offset else offset)
        }
    } }
    val visiblePageCallback by rememberUpdatedState(onVisiblePage)
    LaunchedEffect(chapterId, activePage, pages.map { it.index }, manuallyHiddenPageIndices) {
        visiblePageCallback(pages.getOrNull(activePage)?.index?.takeUnless { it in manuallyHiddenPageIndices })
    }

    // Notes are fixed reading UI; source restoration remains in each image's native Canvas.
    val hasSfxNotes = personalOverlays.values.any { page -> page.values.any(com.mangalens.core.translation.ReaderSfxRestorationPlan::needsOriginal) }
    val sfxNotes = if (hasSfxNotes) remember(chapterId, readerPresentationEpoch) { ReaderSfxNoteRegistry() } else null
    DisposableEffect(sfxNotes) { onDispose { sfxNotes?.close() } }
    val sfxVisiblePages = if (!hasSfxNotes) emptySet() else when (readingMode) {
        "spread" -> layout.ordinals(pagerState.settledPage)
        "horizontal" -> horizontalListState.layoutInfo.visibleItemsInfo.map { it.index }.take(2)
        else -> listOf(positionState.page)
    }.mapNotNull { pages.getOrNull(it)?.index?.takeUnless { index -> index in manuallyHiddenPageIndices } }.toSet()

    val visiblePagesCallback by rememberUpdatedState(onVisiblePages)
    LaunchedEffect(chapterId, readingMode, layout.columns, pages.map { it.index }, manuallyHiddenPageIndices) {
        snapshotFlow {
            val ordinals = when (readingMode) {
                "spread" -> layout.ordinals(pagerState.settledPage)
                "horizontal" -> horizontalListState.layoutInfo.visibleItemsInfo.map { it.index }.take(2)
                else -> listOf(positionState.page)
            }
            ordinals.mapNotNull { pages.getOrNull(it)?.index?.takeUnless { index -> index in manuallyHiddenPageIndices } }.toSet()
        }.collect { visiblePagesCallback(it) }
    }

    LaunchedEffect(autoScroll, speed, readingMode, displayOptions.controlsLocked) {
        while (autoScroll && readingMode == "vertical" && !displayOptions.controlsLocked) {
            listState.scrollBy(3.5f * speed)
            delay(16L)
        }
    }

    // Loading and viewport restoration consume no part of the user's four-second
    // control window. A new navigation request cancels the earlier timer.
    LaunchedEffect(hudVisible, controls, geometryChecked, positionRestored) {
        if (hudVisible && geometryChecked && positionRestored) {
            val timerSequence = if (ReaderHudDiagnostics.enabled) hudTimerSequence.incrementAndGet() else 0
            observeHud(ReaderHudPhase.TIMER_ARM, timerSequence = timerSequence)
            var finished = false
            try {
                delay(4000)
                observeHud(ReaderHudPhase.TIMER_FIRE, timerSequence = timerSequence)
                if (!controls) {
                    val previousHud = hudVisible
                    hudVisible = false
                    observeReader("hud_expired")
                    observeHud(ReaderHudPhase.WRITE, ReaderHudWriter.TIMER, previousHud = previousHud,
                        timerSequence = timerSequence)
                }
                finished = true
            } finally {
                observeHud(if (finished) ReaderHudPhase.TIMER_FINISH else ReaderHudPhase.TIMER_CANCEL,
                    timerSequence = timerSequence)
            }
        }
    }

    CompositionLocalProvider(LocalReaderSfxNotes provides sfxNotes) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).pointerInput(displayOptions.controlsLocked) {
            detectTapGestures(onTap = { if (displayOptions.controlsLocked) return@detectTapGestures; val previousHud = hudVisible; hudVisible = !hudVisible; observeReader("surface_hud_tap");
                observeHud(ReaderHudPhase.WRITE, ReaderHudWriter.PARENT_TAP, previousHud = previousHud) })
        }
    ) {
        SideEffect { observeReader("content_composed_" + readingMode, physicalMode = readingMode); observeHud(ReaderHudPhase.COMMITTED) }
        val navigationHeight = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
        val toolsHeight = readerHudToolsHeight(maxHeight.value, headerHeight.value, navigationHeight.value).dp
        if (readingMode == "vertical") {
            LazyColumn(
                state = listState, userScrollEnabled = !displayOptions.controlsLocked,
                verticalArrangement = Arrangement.spacedBy(displayOptions.pageSpacingDp.dp),
                modifier = Modifier.fillMaxSize().clipToBounds()
                    .transformable(transformState, enabled = !displayOptions.controlsLocked)
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = panX, translationY = panY)
            ) {
                items(pages, key = { it.index }) { page ->
                    val surface = pageSurfaces.getValue(page.index)
                    if (page.error != null || surface.sourceUnreadable) {
                        PageLoadError(page.copy(error = page.error ?: "The saved image could not be read. Retry its source or import the original again.")) { if (!displayOptions.controlsLocked) onRetryPage(page) }
                        return@items
                    }
                    val promoHidden = pageHidden(page)
                    if (promoHidden) {
                        PromoPagePlaceholder(
                            page = page,
                            onShow = { revealPage(page) }, manuallyHidden = manuallyHidden(page)
                        )
                        return@items
                    }
                    val bubbleCapture = rememberBubbleDrawCapture(page, overlays[page.index].orEmpty(),
                        translated && !originalShown && surface.cleaned, onSavedBubble,
                        if (customDisplay) listOf(comparison, displayOptions.marginCrop, displayOptions.splitFraction) else null)
                    var bubbleFrame by remember(bubbleCapture) { mutableStateOf<ReaderBubbleCanvasFrame?>(null) }
                    Box(Modifier.fillMaxWidth().pointerInput(page.index, page.sourceUrl, page.contentRevision, onSavedBubble, bubbleCapture, bubbleFrame, displayOptions.controlsLocked, displayOptions.marginCrop) {
                        detectTapGestures(onTap = { tap ->
                            if (displayOptions.controlsLocked) return@detectTapGestures
                            val selectedBubble = bubbleCapture?.hit(bubbleFrame, tap.x, tap.y, displayOptions.marginCrop)
                            if (selectedBubble != null && onSavedBubble != null) {
                                onSavedBubble(selectedBubble.pageIndex, selectedBubble.letteringIndex, selectedBubble.expectedNative)
                            } else { val previousHud = hudVisible; hudVisible = !hudVisible; observeReader("vertical_hud_tap");
                observeHud(ReaderHudPhase.WRITE, ReaderHudWriter.VERTICAL_TAP, previousHud = previousHud) } },
                            onDoubleTap = { if (!displayOptions.controlsLocked) { scale = if (scale > 1f) 1f else 2f; panX = 0f; panY = 0f } },
                            onLongPress = { if (!displayOptions.controlsLocked) onLongPressPage(page) })
                    }) {
                        if (customDisplay) ReaderComparedMangaPage(page, surface.model.takeIf { surface.cleaned }, surface.aspectRatio,
                            displayOptions, comparison, applyPersonalReaderOverlays(overlays[page.index].orEmpty(), personalOverlays[page.index].orEmpty()).filter { surface.cleaned || it.lettering == null },
                            textScale, bubbleCapture, { bubbleFrame = it }, surface.onOriginalError, surface.onTranslatedError,
                            Modifier.fillMaxWidth().aspectRatio((surface.aspectRatio ?: 1f) * if (comparison == ReaderComparison.SIDE_BY_SIDE && surface.cleaned) 2f else 1f),
                            fitted = false, viewportTransform = Triple(scale, panX, panY), diagnostics = recordedDiagnostics(page))
                        else {
                        val imageModifier = surface.aspectRatio?.let { Modifier.fillMaxWidth().aspectRatio(it) } ?: Modifier.fillMaxWidth()
                        ReaderMangaImage(model = surface.model,
                            description = "Page ${page.index}", modifier = imageModifier,
                            contentScale = ContentScale.FillWidth, viewportTransform = Triple(scale, panX, panY),
                            contentRevision = page.contentRevision, onLoadError = surface.onDecodeError)
                        if (translated && !originalShown) MangaTranslationOverlay(
                            overlays = applyPersonalReaderOverlays(overlays[page.index].orEmpty(), personalOverlays[page.index].orEmpty()).filter { surface.cleaned || it.lettering == null },
                            textScale = textScale, modifier = Modifier.matchParentSize().then(if (bubbleCapture == null) Modifier else Modifier
                                .onSizeChanged { bubbleFrame = ReaderBubbleCanvasFrame(0f, 0f, it.width.toFloat(), it.height.toFloat()) }
                                .drawWithContent { drawContent(); bubbleCapture.painted(size.width, size.height) }))
                        ReaderOcrDiagnosticsOverlay(recordedDiagnostics(page), Modifier.matchParentSize())
                        }
                    }
                }
            }
        } else if (readingMode == "horizontal") {
            LazyRow(state = horizontalListState, horizontalArrangement = Arrangement.spacedBy(displayOptions.pageSpacingDp.dp),
                userScrollEnabled = !displayOptions.controlsLocked && scale <= 1f,
                modifier = Modifier.fillMaxSize().clipToBounds().transformable(transformState, enabled = !displayOptions.controlsLocked)
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = panX, translationY = panY)) {
                items(pages, key = { it.index }) { page ->
                    val surface = pageSurfaces.getValue(page.index)
                    val itemWidth = (maxHeight * (surface.aspectRatio ?: 1f)).coerceIn(maxWidth * .5f, maxWidth * 2f)
                    OptionalReaderPage(page, surface, displayOptions, comparison, customDisplay, translated && !originalShown,
                        overlays[page.index].orEmpty(), personalOverlays[page.index].orEmpty(), textScale, onSavedBubble,
                        pageHidden(page),
                        { revealPage(page) }, { if (!displayOptions.controlsLocked) onRetryPage(page) },
                        { val previous = hudVisible; hudVisible = !hudVisible; observeHud(ReaderHudPhase.WRITE, ReaderHudWriter.PAGER_TAP, previousHud = previous) },
                        { scale = if (scale > 1f) 1f else 2f; panX = 0f; panY = 0f }, { onLongPressPage(page) },
                        Modifier.width(itemWidth).fillMaxSize(), Triple(scale, panX, panY), diagnostics = recordedDiagnostics(page), manuallyHidden = manuallyHidden(page))
                }
            }
        } else {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                HorizontalPager(state = pagerState, reverseLayout = layout.reversed, beyondViewportPageCount = 1,
                    userScrollEnabled = scale <= 1f && !displayOptions.controlsLocked, pageSpacing = displayOptions.pageSpacingDp.dp, modifier = Modifier.fillMaxSize().onSizeChanged {
                        observeReader("pager_measured_" + readingMode, physicalMode = readingMode)
                    }) { position ->
                    if (readingMode == "spread") {
                        Row(Modifier.fillMaxSize().clipToBounds().transformable(transformState, enabled = !displayOptions.controlsLocked)
                            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = panX, translationY = panY),
                            horizontalArrangement = Arrangement.spacedBy(displayOptions.pageSpacingDp.dp)) {
                            layout.ordinals(position).forEach { ordinal ->
                                val member = pages[ordinal]
                                val spreadBubbleCallback = remember(chapterId, ordinal, member.index, onSavedBubble) {
                                    onSavedBubble?.let { callback -> { nativePage: Int, nativeIndex: Int, native: com.mangalens.core.translation.SavedMangaLettering ->
                                        currentPageCommand(ordinal, "spread_bubble"); callback(nativePage, nativeIndex, native)
                                    } }
                                }
                                OptionalReaderPage(member, pageSurfaces.getValue(member.index), displayOptions, comparison, customDisplay,
                                    translated && !originalShown, overlays[member.index].orEmpty(), personalOverlays[member.index].orEmpty(), textScale,
                                    spreadBubbleCallback,
                                    pageHidden(member),
                                    { revealPage(member) }, { if (!displayOptions.controlsLocked) onRetryPage(member) },
                                    { val previous = hudVisible; hudVisible = !hudVisible; observeHud(ReaderHudPhase.WRITE, ReaderHudWriter.PAGER_TAP, previousHud = previous) },
                                    { scale = if (scale > 1f) 1f else 2f; panX = 0f; panY = 0f }, { onLongPressPage(member) },
                                    Modifier.weight(1f).fillMaxSize(), Triple(scale, panX, panY), diagnostics = recordedDiagnostics(member), manuallyHidden = manuallyHidden(member))
                            }
                        }
                        return@HorizontalPager
                    }
                    val page = pages[position]
                    val surface = pageSurfaces.getValue(page.index)
                    if (page.error != null || surface.sourceUnreadable) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            PageLoadError(page.copy(error = page.error ?: "The saved image could not be read. Retry its source or import the original again.")) { if (!displayOptions.controlsLocked) onRetryPage(page) }
                        }
                        return@HorizontalPager
                    }
                    val promoHidden = pageHidden(page)
                    if (promoHidden) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            PromoPagePlaceholder(
                                page = page,
                                onShow = { revealPage(page) },
                                onSkip = { if (!displayOptions.controlsLocked) goToPage(position + 1) },
                                manuallyHidden = manuallyHidden(page)
                            )
                        }
                        return@HorizontalPager
                    }
                    val bubbleCapture = rememberBubbleDrawCapture(page, overlays[page.index].orEmpty(),
                        translated && !originalShown && surface.cleaned, onSavedBubble,
                        if (customDisplay) listOf(comparison, displayOptions.marginCrop, displayOptions.splitFraction) else null)
                    var bubbleFrame by remember(bubbleCapture) { mutableStateOf<ReaderBubbleCanvasFrame?>(null) }
                    if (readingMode == "guided") {
                        val panel = guidedSession?.panelFor(page, readerPresentationEpoch) ?: ReaderGuidedViewPolicy.WHOLE_PAGE
                        val fitted = ReaderGuidedViewPolicy.transform(with(density) { maxWidth.toPx() }, with(density) { maxHeight.toPx() },
                            surface.aspectRatio ?: 1f, panel)
                        val guidedComparison = if (comparison == ReaderComparison.SIDE_BY_SIDE) ReaderComparison.TRANSLATED else comparison
                        val guidedOptions = displayOptions.copy(marginCrop = 0f, comparison = guidedComparison)
                        val guidedBubbleCallback = remember(chapterId, position, page.index, onSavedBubble) {
                            onSavedBubble?.let { callback -> { nativePage: Int, nativeIndex: Int, native: com.mangalens.core.translation.SavedMangaLettering ->
                                currentPageCommand(position, "guided_bubble"); callback(nativePage, nativeIndex, native)
                            } }
                        }
                        OptionalReaderPage(page, surface, guidedOptions, guidedComparison, customDisplay = true,
                            translatedVisible = translated && !originalShown, nativeOverlays = overlays[page.index].orEmpty(),
                            personal = personalOverlays[page.index].orEmpty(), textScale = textScale,
                            onSavedBubble = guidedBubbleCallback,
                            promoHidden = false, onShowPromo = {}, onRetry = { onRetryPage(page) },
                            onHud = { val previous = hudVisible; hudVisible = !hudVisible; observeHud(ReaderHudPhase.WRITE, ReaderHudWriter.PAGER_TAP, previousHud = previous) },
                            onZoom = { scale = if (scale > 1f) 1f else 2f; panX = 0f; panY = 0f }, onLongPress = { onLongPressPage(page) },
                            modifier = Modifier.fillMaxSize().clipToBounds().transformable(transformState, enabled = !displayOptions.controlsLocked)
                                .graphicsLayer(scaleX = scale, scaleY = scale, translationX = panX, translationY = panY)
                                .graphicsLayer(scaleX = fitted.scale, scaleY = fitted.scale, translationX = fitted.x, translationY = fitted.y),
                            viewportTransform = listOf(scale, panX, panY, panel),
                            diagnostics = recordedDiagnostics(page),
                            onUnselectedTap = { x, width ->
                                val physicalX = (x - width * .5f) * fitted.scale + width * .5f + fitted.x
                                if (scale > 1f || physicalX in width * .25f..width * .75f || guidedSession?.busy == true) {
                                    val previous = hudVisible; hudVisible = !hudVisible
                                    observeHud(ReaderHudPhase.WRITE, ReaderHudWriter.PAGER_TAP, previousHud = previous)
                                } else advanceGuided(if ((physicalX > width / 2f) != displayOptions.spreadRtl) 1 else -1)
                            })
                        return@HorizontalPager
                    }
                    val fittedModifier = Modifier.fillMaxSize().clipToBounds()
                        .transformable(transformState, enabled = !displayOptions.controlsLocked)
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = panX, translationY = panY)
                        .pointerInput(page.index, page.sourceUrl, page.contentRevision, readingMode, onSavedBubble, bubbleCapture, bubbleFrame, displayOptions.controlsLocked, displayOptions.marginCrop) {
                            detectTapGestures(onTap = { tap ->
                                if (displayOptions.controlsLocked) return@detectTapGestures
                            val selectedBubble = bubbleCapture?.hit(bubbleFrame, tap.x, tap.y, displayOptions.marginCrop)
                                if (selectedBubble != null && onSavedBubble != null) {
                                    onSavedBubble(selectedBubble.pageIndex, selectedBubble.letteringIndex, selectedBubble.expectedNative)
                                } else if (scale > 1f || tap.x in size.width * .25f..size.width * .75f) {
                                    val previousHud = hudVisible
                                    hudVisible = !hudVisible
                                    observeReader("pager_hud_tap")
                                    observeHud(ReaderHudPhase.WRITE, ReaderHudWriter.PAGER_TAP, previousHud = previousHud)
                                } else {
                                    val next = (tap.x > size.width / 2) != (readingMode == "rtl")
                                    goToPage(positionState.page + if (next) 1 else -1, "pager_edge_tap")
                                }
                            }, onDoubleTap = { if (!displayOptions.controlsLocked) { scale = if (scale > 1f) 1f else 2f; panX = 0f; panY = 0f } },
                                onLongPress = { if (!displayOptions.controlsLocked) onLongPressPage(page) })
                        }
                    if (customDisplay) ReaderComparedMangaPage(page, surface.model.takeIf { surface.cleaned }, surface.aspectRatio,
                        displayOptions, comparison, applyPersonalReaderOverlays(overlays[page.index].orEmpty(), personalOverlays[page.index].orEmpty()).filter { surface.cleaned || it.lettering == null },
                        textScale, bubbleCapture, { bubbleFrame = it }, surface.onOriginalError, surface.onTranslatedError,
                        fittedModifier, fitted = true, viewportTransform = Triple(scale, panX, panY), diagnostics = recordedDiagnostics(page))
                    else FittedMangaPage(page, surface, onOverlayFrame = if (bubbleCapture == null) null else { frame -> bubbleFrame = frame },
                        modifier = fittedModifier) {
                        if (translated && !originalShown) MangaTranslationOverlay(
                            overlays = applyPersonalReaderOverlays(overlays[page.index].orEmpty(), personalOverlays[page.index].orEmpty()).filter { surface.cleaned || it.lettering == null },
                            textScale = textScale, modifier = Modifier.matchParentSize().then(if (bubbleCapture == null) Modifier else Modifier
                                .drawWithContent { drawContent(); bubbleCapture.painted(size.width, size.height) }))
                        ReaderOcrDiagnosticsOverlay(recordedDiagnostics(page), Modifier.matchParentSize())
                    }
                }
            }
        }

        ReaderSfxNotesHost(sfxNotes, sfxVisiblePages, translated && !originalShown && !displayOptions.controlsLocked,
            Modifier.align(Alignment.TopEnd).padding(end = 8.dp,
                top = if (hudVisible || translationActive) headerHeight + 8.dp else 8.dp)
                .then(if (hudVisible || translationActive) Modifier else Modifier.statusBarsPadding()))

        if (displayOptions.controlsLocked) Surface(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
            shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
            TextButton({ changeDisplay(displayOptions.copy(controlsLocked = false)); hudVisible = true },
                modifier = Modifier.semantics { contentDescription = "Unlock Reader screen controls" }) { Text("Unlock Reader") }
        }
        if (pages.isEmpty()) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (loading) androidx.compose.material3.CircularProgressIndicator()
                Text(if (loading) "Loading chapter pages…" else "No pages loaded. Open a chapter URL or import images from Home.")
            }
        }
        if (error != null) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = if (hudVisible || translationActive) headerHeight + 8.dp else 76.dp, start = 12.dp, end = 12.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.medium
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(error, style = MaterialTheme.typography.bodySmall)
                    Row { TextButton(onClick = { if (!displayOptions.controlsLocked) onRetry() }) { Text("Retry") }; TextButton(onClick = { if (!displayOptions.controlsLocked) onOpenWeb() }) { Text("Open in Web") } }
                }
            }
        }

        AnimatedVisibility(
            visible = !displayOptions.controlsLocked && (hudVisible || translationActive),
            enter = fadeIn(androidx.compose.animation.core.tween(220)) +
                androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(260)) { -it / 5 },
            exit = fadeOut(androidx.compose.animation.core.tween(180)) +
                androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(220)) { -it / 6 },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth().onSizeChanged { headerHeight = with(density) { it.height.toDp() } }.statusBarsPadding()
                    .then(readerHudDrawProbe(ReaderHudComponent.HEADER) { phase, component, current, target, running ->
                        observeHud(phase, component = component, animationCurrent = current,
                            animationTarget = target, animationRunning = running)
                    }),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = .28f)
                )
            ) {
                Column {
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
                            if (translated) Text(if (originalShown) "Original" else "Translated", color = MaterialTheme.colorScheme.secondary)
                            TextButton(onClick = { val previousHud = hudVisible; val previousTools = controls
                                controls = !controls; hudVisible = true; observeReader("tools_toggle")
                                observeHud(ReaderHudPhase.WRITE, ReaderHudWriter.TOOLS_TOGGLE,
                                    previousHud = previousHud, previousTools = previousTools) }) { Text("Reader tools") }
                        }
                    }
                    if (translationTotal > 0) {
                        val processed = translationDone.coerceIn(0, translationTotal)
                        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text("$processed / $translationTotal pages processed", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                                if (translationActive) {
                                    TextButton(onClick = { onTranslationPaused(!translationPaused) }, modifier = Modifier.semantics {
                                        contentDescription = if (translationPaused) "Resume chapter translation" else "Pause chapter translation"
                                    }) { Text(if (translationPaused) "Resume" else "Pause") }
                                    TextButton(onClick = onTranslationCancelled, modifier = Modifier.semantics { contentDescription = "Cancel chapter translation" }) { Text("Cancel") }
                                }
                            }
                            androidx.compose.material3.LinearProgressIndicator(progress = { processed.toFloat() / translationTotal }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = !displayOptions.controlsLocked && hudVisible,
            enter = fadeIn(androidx.compose.animation.core.tween(240)) +
                androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(300)) { it / 3 },
            exit = fadeOut(androidx.compose.animation.core.tween(180)) +
                androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(240)) { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp).navigationBarsPadding()
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth()
                    .then(readerHudDrawProbe(ReaderHudComponent.DOCK) { phase, component, current, target, running ->
                        observeHud(phase, component = component, animationCurrent = current,
                            animationTarget = target, animationRunning = running)
                    }),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = .96f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = .48f)
                ),
                shadowElevation = 14.dp,
                tonalElevation = 2.dp
            ) {
                Column(Modifier.heightIn(max = toolsHeight).verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton({ goToPage(layout.advance(positionState.page, -1), "previous_button") }, enabled = layout.canAdvance(activePage, -1)) { Text("‹ Prev") }
                        Text("${if (pages.isEmpty()) 0 else activePage + 1} / ${pages.size}", style = MaterialTheme.typography.labelMedium)
                        TextButton({ goToPage(layout.advance(positionState.page, 1), "next_button") }, enabled = layout.canAdvance(activePage, 1)) { Text("Next ›") }
                    }
                    if (readingMode == "guided") ReaderGuidedControls(guidedSession, activePage > 0, activePage < pages.lastIndex,
                        onAdvance = ::advanceGuided, onWhole = ::toggleGuidedWhole)
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(pages, key = { it.index }) { page ->
                            AsyncImage(rememberMangaThumbnailRequest(page.localPath ?: page.sourceUrl, page.contentRevision), "Jump to page ${page.index}", Modifier.size(38.dp, 50.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(5.dp)).clickable { goToPage(pages.indexOf(page), "thumbnail") }, contentScale = ContentScale.Crop)
                        }
                    }
                    if (controls) {
                        if (onCorrectPage != null) TextButton({ pages.getOrNull(activePage)?.takeUnless(::manuallyHidden)?.index?.let(onCorrectPage) },
                            enabled = pages.getOrNull(activePage)?.let { !manuallyHidden(it) } == true,
                            modifier = Modifier.semantics { contentDescription = "Correct current page" }) { Text("Personal corrections") }
                        val selectedHidePage = pages.getOrNull(activePage)
                        val selectedManuallyHidden = selectedHidePage?.let(::manuallyHidden) == true
                        TextButton({
                            val capturedPage = selectedHidePage ?: return@TextButton
                            if (displayOptions.controlsLocked) return@TextButton
                            // A delayed click cannot hide a replacement page presentation.
                            val currentPage = currentPages.singleOrNull { it.index == capturedPage.index } ?: return@TextButton
                            val selected = ReaderManualPageHide.capture(chapterId, capturedPage) ?: return@TextButton
                            if (!selected.matches(chapterId, currentPage)) return@TextButton
                            if (selectedManuallyHidden) revealPage(currentPage)
                            else if (manuallyHiddenPages.size < ReaderManualPageHide.MAX_PAGES || currentPage.index in manuallyHiddenPages) {
                                // Retire the old Reader authority before hiding any source, including a spread's secondary page.
                                manualHidingCallback(manuallyHiddenPageIndices + currentPage.index)
                                manuallyHiddenPages = manuallyHiddenPages + (currentPage.index to selected)
                            }
                        }, enabled = selectedHidePage?.let { ReaderManualPageHide.capture(chapterId, it) } != null &&
                            (selectedManuallyHidden || manuallyHiddenPages.size < ReaderManualPageHide.MAX_PAGES),
                            modifier = Modifier.semantics { contentDescription = if (selectedManuallyHidden)
                                "Show current page hidden by you" else "Hide current page for this Reader session" }) {
                            Text(if (selectedManuallyHidden) "Show current page" else "Hide current page for this session")
                        }
                        Text("Reading mode", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("vertical" to "Vertical scroll", "ltr" to "Horizontal LTR", "rtl" to "Horizontal RTL",
                                "single" to "Single page", "horizontal" to "Continuous horizontal", "spread" to "Two-page landscape", "guided" to "Guided panels").forEach { (mode, label) ->
                                androidx.compose.material3.FilterChip(selected = readingMode == mode, onClick = {
                                    observeReader("mode_click_" + mode)
                                    if (mode != positionState.mode) {
                                        val viewport = currentViewport()
                                        val physical = if (!geometryChecked) positionState.page else viewport.first
                                        val offset = if (!geometryChecked) positionState.offset else viewport.second
                                        if (mode in setOf("spread", "guided") && readingMode == "rtl") changeDisplay(displayOptions.copy(spreadRtl = true))
                                        positionState = positionState.switchMode(mode, physical, offset, pages.size)
                                        autoScroll = false
                                        scale = 1f; panX = 0f; panY = 0f
                                        prefs.edit().putString(modeKey, mode).putString("default_mode", mode).apply()
                                        observeReader("mode_accepted_" + mode)
                                    }
                                }, label = { Text(label) })
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Translation", style = MaterialTheme.typography.titleSmall)
                            androidx.compose.material3.Switch(translated && !originalShown, { enabled -> if (enabled) { originalVisible = false; changeDisplay(displayOptions.copy(comparison = ReaderComparison.TRANSLATED)); if (!translated) onTranslate() } else originalVisible = true })
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text("Hide promotional pages", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (detectedPromoPages.isEmpty()) "No promo pages detected yet" else "${detectedPromoPages.size} detected • tap a placeholder to reveal",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            androidx.compose.material3.Switch(hidePromos, {
                                hidePromos = it
                                prefs.edit().putBoolean("hide_promos", it).apply()
                            })
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton({ languageMenu = true }) { Text("Language: ${if (com.mangalens.core.translation.HindiRomanization.isTarget(targetLanguage)) "Hinglish" else targetLanguage.uppercase()} ▾", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) }
                            Box {
                                TextButton({ styleMenu = true }) { Text("Style: $translationStyle ▾", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) }
                                androidx.compose.material3.DropdownMenu(styleMenu, { styleMenu = false }) {
                                    listOf("natural", "faithful", "casual", "formal", "manga", "webtoon", "literal").forEach { style -> androidx.compose.material3.DropdownMenuItem(text = { Text(style.replaceFirstChar { it.uppercase() }) }, onClick = { onTranslationStyleChanged(style); styleMenu = false }) }
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Text size", style = MaterialTheme.typography.bodySmall)
                            Slider(textScale, { textScale = it; prefs.edit().putFloat("text_scale", it).apply() }, Modifier.weight(1f), valueRange = .75f..1.5f)
                        }
                        val diagnosticPage = pages.getOrNull(activePage)
                        ReaderOcrDiagnosticsControls(diagnosticPage?.let { ocrDiagnostics[it.index]?.takeIf { value -> ReaderOcrDiagnosticsPolicy.matchesRevision(value, it.contentRevision) } },
                            diagnosticPage?.index, readerPresentationEpoch, showOcrDiagnostics, { showOcrDiagnostics = it },
                            { diagnosticPage?.let(onLongPressPage) })
                        ReaderDisplayControls(displayOptions, readingMode, translated, { next ->
                            if (next.comparison != displayOptions.comparison) originalVisible = false; changeDisplay(next)
                        }, { peekOriginal = it })
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onTranslate,
                            enabled = !translationActive && pages.isNotEmpty(),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
                        ) { Text(if (translationPaused) "Paused" else if (translating) "Translating…" else "Translate", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
                        TextButton({ originalVisible = !originalShown; if (!originalVisible) changeDisplay(displayOptions.copy(comparison = ReaderComparison.TRANSLATED)) }, Modifier.semantics {
                            contentDescription = if (originalShown) "Show translated page" else "Show original page"
                        }) { Text(if (originalShown) "Translation" else "Original") }
                        if (readingMode == "vertical") TextButton({ autoScroll = !autoScroll }) { Text(if (autoScroll) "Pause scroll" else "Auto-scroll") }
                        if (controls) { TextButton(onDownload) { Text("Download") }; TextButton(onMenu) { Text("More settings") } }
                    }
                    if (languageMenu) {
                        androidx.compose.material3.DropdownMenu(expanded = true, onDismissRequest = { languageMenu = false }) {
                            listOf("hi" to "Hindi", "hi-latn" to "Hinglish (Roman Hindi)", "en" to "English", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese", "es" to "Spanish", "fr" to "French").forEach { (code, name) ->
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
}

@Composable
private fun PromoPagePlaceholder(
    page: ChapterPage,
    onShow: () -> Unit,
    onSkip: (() -> Unit)? = null,
    manuallyHidden: Boolean = false
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .72f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .35f))
    ) {
        Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(if (manuallyHidden) "Page hidden by you" else "Promotional page hidden", style = MaterialTheme.typography.titleMedium)
            Text(if (manuallyHidden) "Page ${page.index + 1} is hidden for this Reader session. Nothing was deleted."
                else "Page ${page.index + 1} was classified as an announcement, ad-free upsell or scan-credit page. Nothing was deleted.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onShow) { Text("Show this page") }
                onSkip?.let { TextButton(it) { Text("Next page") } }
            }
        }
    }
}

/** New continuous/spread modes retain saved native indices before personal rendering. */
@Composable
private fun OptionalReaderPage(page: ChapterPage, surface: ReaderPageSurface, options: ReaderDisplayOptions,
    comparison: ReaderComparison, customDisplay: Boolean, translatedVisible: Boolean,
    nativeOverlays: List<TranslationOverlay>, personal: Map<Int, com.mangalens.core.translation.PersonalMangaLettering>,
    textScale: Float, onSavedBubble: ((Int, Int, com.mangalens.core.translation.SavedMangaLettering) -> Unit)?,
    promoHidden: Boolean, onShowPromo: () -> Unit, onRetry: () -> Unit,
    onHud: () -> Unit, onZoom: () -> Unit, onLongPress: () -> Unit, modifier: Modifier, viewportTransform: Any?,
    onUnselectedTap: ((Float, Int) -> Unit)? = null, diagnostics: com.mangalens.core.translation.SavedPageOcrDiagnostics? = null,
    manuallyHidden: Boolean = false) {
    if (page.error != null || surface.sourceUnreadable) {
        Box(modifier, contentAlignment = Alignment.Center) {
            PageLoadError(page.copy(error = page.error ?: "The saved image could not be read. Retry its source or import the original again.")) {
                if (!options.controlsLocked) onRetry()
            }
        }
        return
    }
    if (promoHidden) {
        Box(modifier, contentAlignment = Alignment.Center) {
            PromoPagePlaceholder(page, { if (!options.controlsLocked) onShowPromo() }, manuallyHidden = manuallyHidden)
        }
        return
    }
    val capture = rememberBubbleDrawCapture(page, nativeOverlays, translatedVisible && surface.cleaned, onSavedBubble,
        if (customDisplay) listOf(comparison, options.marginCrop, options.splitFraction) else null)
    var frame by remember(capture) { mutableStateOf<ReaderBubbleCanvasFrame?>(null) }
    val imageModifier = modifier.pointerInput(page.index, page.sourceUrl, page.contentRevision, capture, frame,
        onSavedBubble, options.controlsLocked, options.marginCrop, onUnselectedTap) {
        detectTapGestures(onTap = { tap ->
            if (!options.controlsLocked) {
                val selected = capture?.hit(frame, tap.x, tap.y, options.marginCrop)
                if (selected != null && onSavedBubble != null) onSavedBubble(selected.pageIndex, selected.letteringIndex, selected.expectedNative)
                else if (onUnselectedTap != null) onUnselectedTap(tap.x, size.width) else onHud()
            }
        }, onDoubleTap = { if (!options.controlsLocked) onZoom() }, onLongPress = { if (!options.controlsLocked) onLongPress() })
    }
    val displayed = applyPersonalReaderOverlays(nativeOverlays, personal).filter { surface.cleaned || it.lettering == null }
    if (customDisplay) ReaderComparedMangaPage(page, surface.model.takeIf { surface.cleaned }, surface.aspectRatio,
        options, comparison, displayed, textScale, capture, { frame = it }, surface.onOriginalError,
        surface.onTranslatedError, imageModifier, fitted = true, viewportTransform = viewportTransform, diagnostics = diagnostics)
    else FittedMangaPage(page, surface, imageModifier,
        onOverlayFrame = if (capture == null) null else { value: ReaderBubbleCanvasFrame -> frame = value }) {
        if (translatedVisible) MangaTranslationOverlay(displayed, textScale,
            Modifier.matchParentSize().then(if (capture == null) Modifier else Modifier.drawWithContent {
                drawContent(); capture.painted(size.width, size.height)
            }))
        ReaderOcrDiagnosticsOverlay(diagnostics, Modifier.matchParentSize())
    }
}

/** Keep overlays in image coordinates, including letterboxing in paged mode. */
@Composable
private fun FittedMangaPage(page: ChapterPage, surface: ReaderPageSurface, modifier: Modifier,
    onOverlayFrame: ((ReaderBubbleCanvasFrame) -> Unit)? = null,
    overlay: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val ratio = surface.aspectRatio ?: 1f
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val width = minOf(maxWidth, maxHeight * ratio)
        val height = width / ratio
        Box(Modifier.size(width, height).then(if (onOverlayFrame == null) Modifier else Modifier.onGloballyPositioned { coordinates ->
            val position = coordinates.positionInParent()
            onOverlayFrame(ReaderBubbleCanvasFrame(position.x, position.y, coordinates.size.width.toFloat(), coordinates.size.height.toFloat()))
        })) {
            ReaderMangaImage(surface.model, "Page ${page.index}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
                contentRevision = page.contentRevision, onLoadError = surface.onDecodeError)
            overlay()
        }
    }
}

private data class ReaderPageSurface(val model: String, val aspectRatio: Float?, val cleaned: Boolean = false,
    val geometryChecked: Boolean = false, val sourceUnreadable: Boolean = false,
    val onDecodeError: () -> Unit = {}, val onOriginalError: () -> Unit = {}, val onTranslatedError: () -> Unit = {})

/** The caller supplies checksum-verified journal paths; IO rechecks decodability and geometry. */
@Composable
private fun rememberReaderPageSurface(page: ChapterPage, cleanedPath: String?, showTranslation: Boolean): ReaderPageSurface {
    val original = page.localPath ?: page.sourceUrl
    val originalDecodeFailed = remember(original, page.contentRevision) { mutableStateOf(false) }
    val cleanedDecodeFailed = remember(original, page.contentRevision, cleanedPath) { mutableStateOf(false) }
    var sourceRatio by remember(original, page.contentRevision) { mutableStateOf<Float?>(null) }
    var usableCleaned by remember(original, page.contentRevision, cleanedPath) { mutableStateOf<Pair<String, Float>?>(null) }
    var geometryChecked by remember(original, page.contentRevision, cleanedPath) { mutableStateOf(false) }
    LaunchedEffect(original, page.contentRevision, cleanedPath) {
        val checked = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val sourceBounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            page.localPath?.let { path ->
                if (java.io.File(path).length() in 1..40L * 1024 * 1024)
                    android.graphics.BitmapFactory.decodeFile(path, sourceBounds)
            }
            val ratio = if (sourceBounds.outWidth > 0 && sourceBounds.outHeight > 0 &&
                sourceBounds.outWidth.toLong() * sourceBounds.outHeight <= 100_000_000L)
                sourceBounds.outWidth.toFloat() / sourceBounds.outHeight else null
            val usable = runCatching {
                val candidate = cleanedPath?.let { java.io.File(it) } ?: return@runCatching null
                if (!candidate.isFile || candidate.length() !in 1..com.mangalens.core.translation.ChapterTranslationStore.MAX_SURFACE_BYTES ||
                    candidate.canonicalPath == page.localPath?.let { java.io.File(it).canonicalPath }) return@runCatching null
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(candidate.absolutePath, bounds)
                if (bounds.outMimeType != "image/png" || bounds.outWidth <= 0 || bounds.outHeight <= 0 || ratio == null ||
                    bounds.outWidth.toLong() * bounds.outHeight > com.mangalens.core.translation.ChapterTranslationStore.MAX_SURFACE_PIXELS) return@runCatching null
                val outputRatio = bounds.outWidth.toFloat() / bounds.outHeight
                if (kotlin.math.abs(outputRatio - ratio) / ratio > .01f) null else candidate.absolutePath to outputRatio
            }.getOrNull()
            ratio to usable
        }
        sourceRatio = checked.first
        usableCleaned = checked.second
        geometryChecked = true
    }
    val translatedSurface = usableCleaned.takeIf { showTranslation && !cleanedDecodeFailed.value }
    val failedSurface = if (translatedSurface != null) cleanedDecodeFailed else originalDecodeFailed
    return ReaderPageSurface(translatedSurface?.first ?: original, translatedSurface?.second ?: sourceRatio,
        translatedSurface != null, geometryChecked,
        sourceUnreadable = originalDecodeFailed.value || (geometryChecked && page.localPath != null && sourceRatio == null),
        onDecodeError = { failedSurface.value = true }, onOriginalError = { originalDecodeFailed.value = true },
        onTranslatedError = { cleanedDecodeFailed.value = true })
}

@Composable
private fun PageLoadError(page: ChapterPage, retry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Page ${page.index} could not load", style = MaterialTheme.typography.titleMedium)
        Text(page.error.orEmpty(), style = MaterialTheme.typography.bodySmall)
        TextButton(retry) { Text("Retry page loading") }
    }
}

/** Original list indices survive personal projection and renderer validity filtering. */
@Composable
private fun rememberBubbleDrawCapture(page: ChapterPage, nativeOverlays: List<TranslationOverlay>, enabled: Boolean,
    callback: ((Int, Int, com.mangalens.core.translation.SavedMangaLettering) -> Unit)?, displayKey: Any? = null): ReaderBubbleDrawCapture? {
    if (!enabled || callback == null) return null
    return remember(page.index, page.sourceUrl, page.contentRevision, nativeOverlays, callback, displayKey) {
        val targets = nativeOverlays.mapIndexedNotNull { index, overlay ->
            overlay.lettering?.let { ReaderBubbleHitTarget(ReaderBubbleTap(page.index, index, it), overlay.imageWidthPx, overlay.imageHeightPx) }
        }
        if (targets.isEmpty()) null else ReaderBubbleDrawCapture(targets)
    }
}
