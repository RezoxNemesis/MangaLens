package com.mangalens.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangalens.core.translation.OcrRegion
import kotlin.math.min

data class TranslationOverlay(
    val region: OcrRegion,
    val translatedText: String,
    val textColorArgb: Int = android.graphics.Color.WHITE,
    val backgroundColorArgb: Int = android.graphics.Color.BLACK,
    val fontSizePx: Float = 18f,
    val maxWidthPx: Float = 320f,
    val imageWidthPx: Int = 0,
    val imageHeightPx: Int = 0
)

@Composable
fun MangaTranslationOverlay(
    overlays: List<TranslationOverlay>,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    BoxWithConstraints(modifier) {
        val sourceWidth = overlays.firstOrNull()?.imageWidthPx?.takeIf { it > 0 }?.toFloat() ?: 1080f
        val sourceHeight = overlays.firstOrNull()?.imageHeightPx?.takeIf { it > 0 }?.toFloat()
        val widthScale = (maxWidth.value / sourceWidth).coerceIn(0.0001f, 8f)
        val uniformScale = if (
            sourceHeight != null &&
            maxHeight.value.isFinite() &&
            maxHeight.value > 0f
        ) {
            min(widthScale, (maxHeight.value / sourceHeight).coerceIn(0.0001f, 8f))
        } else {
            widthScale
        }
        val renderedWidth = sourceWidth * uniformScale
        val renderedHeight = sourceHeight?.times(uniformScale) ?: maxHeight.value
        val baseX = ((maxWidth.value - renderedWidth) / 2f).coerceAtLeast(0f)
        val baseY = if (maxHeight.value.isFinite()) {
            ((maxHeight.value - renderedHeight) / 2f).coerceAtLeast(0f)
        } else {
            0f
        }

        overlays.forEach { overlay ->
            val left = baseX + overlay.region.left.coerceAtLeast(0) * uniformScale
            val top = baseY + overlay.region.top.coerceAtLeast(0) * uniformScale
            val regionWidth = ((overlay.region.right - overlay.region.left).coerceAtLeast(1) * uniformScale)
                .coerceIn(24f, maxWidth.value.coerceAtLeast(24f))
            val regionHeight = ((overlay.region.bottom - overlay.region.top).coerceAtLeast(1) * uniformScale)
                .coerceAtLeast(18f)

            val scaledFontPx = overlay.fontSizePx * uniformScale
            val fontSp = (scaledFontPx / density.density).coerceIn(8f, 30f)
            val radius = (regionHeight * .12f).coerceIn(3f, 14f)
            val backgroundColor = Color(overlay.backgroundColorArgb).copy(alpha = 0.96f)
            val textColor = Color(overlay.textColorArgb)

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .offset(x = left.dp, y = top.dp)
                    .size(width = regionWidth.dp, height = regionHeight.dp)
                    .clip(RoundedCornerShape(radius.dp))
                    .background(backgroundColor)
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            ) {
                Text(
                    text = overlay.translatedText,
                    modifier = Modifier.fillMaxSize(),
                    color = textColor,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = fontSp.sp,
                        lineHeight = (fontSp * 1.08f).sp
                    ),
                    softWrap = true,
                    overflow = TextOverflow.Clip,
                    maxLines = 10
                )
            }
        }
    }
}
