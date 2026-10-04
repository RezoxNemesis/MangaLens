package com.mangalens.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangalens.core.translation.OcrRegion

data class TranslationOverlay(
    val region: OcrRegion,
    val translatedText: String,
    val textColorArgb: Int = android.graphics.Color.WHITE,
    val backgroundColorArgb: Int = android.graphics.Color.BLACK,
    val fontSizePx: Float = 18f,
    val maxWidthPx: Float = 320f,
    val imageWidthPx: Int = 0,
    val imageHeightPx: Int = 0,
    val patch: com.mangalens.core.translation.MangaLettering.Patch? = null
)

@Composable
fun MangaTranslationOverlay(
    overlays: List<TranslationOverlay>,
    textScale: Float = 1f,
    modifier: Modifier = Modifier
) {
    androidx.compose.foundation.Canvas(modifier) {
        overlays.forEach { overlay ->
            val patch = overlay.patch ?: return@forEach
            val scale = size.width / overlay.imageWidthPx.coerceAtLeast(1)
            drawContext.canvas.nativeCanvas.apply {
                save()
                scale(scale, scale)
                com.mangalens.core.translation.MangaLettering.draw(this, patch, overlay.translatedText, textScale)
                restore()
            }
        }
    }
}
