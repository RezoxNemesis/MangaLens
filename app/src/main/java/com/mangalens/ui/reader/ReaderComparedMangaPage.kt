package com.mangalens.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.mangalens.core.reader.ChapterPage

/** Explicit comparison/crop renderer. Default old pages keep their existing rendering branch. */
@Composable
internal fun ReaderComparedMangaPage(page: ChapterPage, translatedModel: String?, ratio: Float?,
    options: ReaderDisplayOptions, comparison: ReaderComparison, overlays: List<TranslationOverlay>,
    textScale: Float, bubbleCapture: ReaderBubbleDrawCapture?, onFrame: (ReaderBubbleCanvasFrame?) -> Unit,
    onOriginalError: () -> Unit, onTranslatedError: () -> Unit, modifier: Modifier, fitted: Boolean,
    viewportTransform: Any?, diagnostics: com.mangalens.core.translation.SavedPageOcrDiagnostics? = null) {
    var parent by remember(page.index, page.contentRevision, comparison) { mutableStateOf<LayoutCoordinates?>(null) }
    val originalModel = page.localPath ?: page.sourceUrl
    val sideBySide = comparison == ReaderComparison.SIDE_BY_SIDE && translatedModel != null
    val displayRatio = (ratio ?: 1f) * if (sideBySide) 2f else 1f
    BoxWithConstraints(modifier.onGloballyPositioned { parent = it }, contentAlignment = Alignment.Center) {
        val width = if (fitted) minOf(maxWidth, maxHeight * displayRatio) else maxWidth
        val height = width / displayRatio
        Row(Modifier.size(width, height), horizontalArrangement = Arrangement.Start) {
            if (sideBySide) ImageSlot(originalModel, "Original page ${page.index}", page, Modifier.weight(1f).fillMaxHeight(),
                options, null, null, textScale, parent, onFrame = {}, onError = onOriginalError, viewportTransform = viewportTransform, diagnostics = diagnostics)
            val sourceOnly = comparison == ReaderComparison.ORIGINAL || translatedModel == null
            val slotModifier = Modifier.weight(1f).fillMaxHeight()
            if (comparison == ReaderComparison.SPLIT && translatedModel != null) {
                Box(slotModifier) {
                    ImageSlot(originalModel, "Original page ${page.index}", page, Modifier.fillMaxSize(), options,
                        null, null, textScale, parent, {}, onOriginalError, viewportTransform, diagnostics = diagnostics)
                    ImageSlot(translatedModel, "Translated page ${page.index}", page,
                        Modifier.fillMaxSize().drawWithContent {
                            clipRect(right = size.width * options.splitFraction) { this@drawWithContent.drawContent() }
                        },
                        options, overlays, bubbleCapture, textScale, parent, onFrame, onTranslatedError, viewportTransform,
                        visibleFraction = options.splitFraction, diagnostics = diagnostics)
                }
            } else ImageSlot(if (sourceOnly) originalModel else requireNotNull(translatedModel),
                "${if (sourceOnly) "Original" else "Translated"} page ${page.index}", page, slotModifier, options,
                if (sourceOnly) null else overlays, if (sourceOnly) null else bubbleCapture, textScale, parent,
                onFrame, if (sourceOnly) onOriginalError else onTranslatedError, viewportTransform, diagnostics = diagnostics)
        }
    }
}

@Composable
private fun ImageSlot(model: String, description: String, page: ChapterPage, modifier: Modifier,
    options: ReaderDisplayOptions, overlays: List<TranslationOverlay>?, capture: ReaderBubbleDrawCapture?,
    textScale: Float, parent: LayoutCoordinates?, onFrame: (ReaderBubbleCanvasFrame?) -> Unit,
    onError: () -> Unit, viewportTransform: Any?, visibleFraction: Float = 1f, diagnostics: com.mangalens.core.translation.SavedPageOcrDiagnostics? = null) {
    DisposableEffect(capture, model, options.marginCrop, visibleFraction) { onDispose { onFrame(null) } }
    Box(modifier.clipToBounds().onGloballyPositioned { slot ->
        val root = parent
        if (root == null || !root.isAttached || !slot.isAttached) { onFrame(null); return@onGloballyPositioned }
        val offset = root.localPositionOf(slot, Offset.Zero)
        onFrame(ReaderBubbleCanvasFrame(offset.x, offset.y, slot.size.width.toFloat(), slot.size.height.toFloat()))
    }) {
        val factor = ReaderDisplayGeometry.scale(options.marginCrop)
        Box(Modifier.fillMaxSize().graphicsLayer(scaleX = factor, scaleY = factor)) {
            // Independent x/y normalization matches actual sampled clean/source dimensions, including rounded downsampling.
            // Large original images remain tiled through ReaderMangaImage's explicit normalized-plane path.
            ReaderMangaImage(model, description, Modifier.fillMaxSize(), ContentScale.FillBounds,
                viewportTransform = listOf(viewportTransform, options.marginCrop), contentRevision = page.contentRevision,
                onLoadError = onError, normalizedPlane = true)
            ReaderOcrDiagnosticsOverlay(diagnostics, Modifier.matchParentSize())
            overlays?.let { values ->
                MangaTranslationOverlay(values, textScale, Modifier.matchParentSize().then(if (capture == null) Modifier else Modifier.drawWithContent {
                    drawContent()
                    capture.painted(size.width, size.height,
                        ReaderDisplayGeometry.visible(size.width, size.height, options.marginCrop, visibleFraction))
                }))
            }
        }
    }
}
