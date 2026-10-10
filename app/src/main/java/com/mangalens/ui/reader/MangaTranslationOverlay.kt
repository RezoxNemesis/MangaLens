package com.mangalens.ui.reader

import android.graphics.Rect
import android.text.Layout
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import com.mangalens.core.translation.MangaLettering
import com.mangalens.core.translation.OcrRegion
import com.mangalens.core.translation.SavedMangaLettering

data class TranslationOverlay(
    val region: OcrRegion,
    val translatedText: String,
    val textColorArgb: Int = android.graphics.Color.WHITE,
    val backgroundColorArgb: Int = android.graphics.Color.BLACK,
    val fontSizePx: Float = 18f,
    val maxWidthPx: Float = 320f,
    val imageWidthPx: Int = 0,
    val imageHeightPx: Int = 0,
    val patch: MangaLettering.Patch? = null,
    val lettering: SavedMangaLettering? = null,
    val personalRegion: com.mangalens.core.translation.PersonalReaderRegion? = null
)

/** Cached typesetting metadata contains no page or patch Bitmap. */
private data class LetteringDescription(val bounds: Rect, val style: MangaLettering.Style, val text: String, val saved: Boolean)

@Composable
fun MangaTranslationOverlay(
    overlays: List<TranslationOverlay>,
    textScale: Float = 1f,
    modifier: Modifier = Modifier
) {
    val descriptions = remember(overlays) {
        overlays.map { overlay ->
            val saved = overlay.lettering
            if (saved != null) {
                val bounds = Rect(saved.left, saved.top, saved.right, saved.bottom)
                if (bounds.isEmpty || bounds.left < 0 || bounds.top < 0 || bounds.right > overlay.imageWidthPx ||
                    bounds.bottom > overlay.imageHeightPx || !saved.size.isFinite() || saved.size <= 0f) null
                else LetteringDescription(bounds, MangaLettering.Style(saved.family, saved.face, saved.color, saved.size,
                    runCatching { Layout.Alignment.valueOf(saved.alignment) }.getOrDefault(Layout.Alignment.ALIGN_CENTER)), saved.translated, true)
            } else overlay.patch?.let { patch ->
                LetteringDescription(Rect(patch.bounds), patch.style, overlay.translatedText, false)
            }
        }
    }
    val layouts = remember(descriptions, textScale) {
        descriptions.map { description -> description?.let {
            MangaLettering.layout(it.text, it.style, it.bounds.width(), it.bounds.height(), textScale)
        } }
    }
    ReaderSfxPresentationLayer(overlays, modifier) { canvasModifier, originalPatches ->
    Canvas(canvasModifier) {
        overlays.forEachIndexed { index, overlay ->
            val description = descriptions[index] ?: return@forEachIndexed
            val scale = size.width / overlay.imageWidthPx.coerceAtLeast(1)
            drawContext.canvas.nativeCanvas.apply {
                val checkpoint = save()
                try {
                    scale(scale, scale)
                    val original = overlay.personalRegion?.let { originalPatches[it] }
                    if (original != null && drawOriginalSfxPatch(this, original)) {
                        if (overlay.personalRegion?.personal?.regionPresentation?.sfx == com.mangalens.core.translation.memory.MemorySfxPresentation.ANNOTATE) {
                            val marker = android.graphics.Paint().apply { color = android.graphics.Color.YELLOW; style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.5f / scale }
                            drawRect(original.nativeBounds, marker)
                        }
                        return@apply
                    }
                    if (description.saved) {
                        MangaLettering.drawText(this, description.bounds, description.style, description.text, textScale, layouts[index])
                    } else overlay.patch?.let { patch ->
                        MangaLettering.draw(this, patch, description.text, textScale, layouts[index])
                    }
                } finally { restoreToCount(checkpoint) }
            }
        }
    }
    }
}
