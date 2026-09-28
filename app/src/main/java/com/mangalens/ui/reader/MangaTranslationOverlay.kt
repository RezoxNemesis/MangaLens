package com.mangalens.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mangalens.core.translation.OcrRegion

data class TranslationOverlay(
    val region: OcrRegion,
    val translatedText: String,
    val textColorArgb: Int = android.graphics.Color.WHITE,
    val backgroundColorArgb: Int = android.graphics.Color.BLACK,
    val fontSizePx: Float = 18f,
    val maxWidthPx: Float = 320f
)

@Composable
fun MangaTranslationOverlay(
    overlays: List<TranslationOverlay>,
    modifier: Modifier = Modifier
) {
    Box(modifier) {
        overlays.forEach { overlay ->
            val textColor = Color(overlay.textColorArgb)
            val backgroundColor = Color(overlay.backgroundColorArgb).copy(alpha = 0.82f)
            Text(
                text = overlay.translatedText,
                color = textColor,
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = (overlay.fontSizePx / 3f).coerceIn(9f, 32f).sp
                ),
                modifier = Modifier
                    .offset { IntOffset(overlay.region.left, overlay.region.top) }
                    .widthIn(max = (overlay.maxWidthPx / 3f).coerceIn(60f, 420f).dp)
                    .background(backgroundColor)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }
    }
}
