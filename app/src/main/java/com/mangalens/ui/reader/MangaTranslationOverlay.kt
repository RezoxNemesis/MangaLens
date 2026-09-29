package com.mangalens.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.matchParentSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    val imageHeightPx: Int = 0
)

@Composable
fun MangaTranslationOverlay(
    overlays: List<TranslationOverlay>,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier) {
        overlays.forEach { overlay ->
            val sourceWidth = overlay.imageWidthPx.takeIf { it > 0 }?.toFloat() ?: 1080f
            val scale = (maxWidth.value / sourceWidth).coerceIn(0.0001f, 4f)
            val left = overlay.region.left.coerceAtLeast(0).toFloat() * scale
            val top = overlay.region.top.coerceAtLeast(0).toFloat() * scale
            val regionWidth = (overlay.region.right - overlay.region.left).coerceAtLeast(1) * scale
            val textColor = Color(overlay.textColorArgb)
            val backgroundColor = Color(overlay.backgroundColorArgb).copy(alpha = 0.90f)
            Text(
                text = overlay.translatedText,
                color = textColor,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = (overlay.fontSizePx * scale).coerceIn(8f, 28f).sp
                ),
                modifier = Modifier
                    .offset(x = left.dp, y = top.dp)
                    .widthIn(max = regionWidth.coerceIn(28f, maxWidth.value).dp)
                    .background(backgroundColor)
                    .padding(horizontal = 3.dp, vertical = 1.dp)
            )
        }
    }
}
