package com.mangalens.ui.video

import android.graphics.Bitmap
import android.view.TextureView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.ui.PlayerView
import com.mangalens.core.translation.TranslationService
import com.mangalens.engine.AdvancedTranslationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
fun LiveVideoOcrTranslationOverlay(
    enabled: Boolean,
    targetLanguage: String,
    playerView: PlayerView?,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var original by remember { mutableStateOf("") }
    var translated by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(enabled, targetLanguage, playerView) {
        if (!enabled || playerView == null) {
            original = ""
            translated = ""
            status = null
            return@LaunchedEffect
        }

        val ocr = AdvancedTranslationEngine(context)
        val translator = TranslationService()
        var lastText = ""
        try {
            status = "Live OCR starting…"
            while (true) {
                currentCoroutineContext().ensureActive()
                val frame: Bitmap? = withContext(Dispatchers.Main.immediate) {
                    val texture = playerView.videoSurfaceView as? TextureView
                    if (texture != null && texture.isAvailable && texture.width > 0 && texture.height > 0) {
                        runCatching { texture.getBitmap(640, 360) }.getOrNull()
                    } else null
                }
                if (frame != null) {
                    try {
                        val regions = withContext(Dispatchers.Default) { ocr.recognizeFast(frame) }
                        val sourceLines = regions.map { it.source.trim() }
                            .filter { it.length >= 2 && it.any(Char::isLetter) }
                            .distinct()
                            .take(8)
                        val source = sourceLines.joinToString(" ").take(500)
                        if (source.isBlank()) {
                            original = ""
                            translated = ""
                            lastText = ""
                            status = "Looking for on-screen text…"
                        } else if (source != lastText) {
                            lastText = source
                            original = source
                            status = "Translating detected text…"
                            val outputs = mutableListOf<String>()
                            for (line in sourceLines) {
                                currentCoroutineContext().ensureActive()
                                val result = runCatching { translator.translate(line, targetLanguage) }
                                    .getOrElse { failure ->
                                        if (failure is CancellationException) throw failure
                                        line
                                    }
                                outputs += result
                            }
                            translated = outputs.joinToString(" ").take(700)
                            status = if (translated.isBlank() || translated == source) {
                                "Text detected; translation model may still be unavailable."
                            } else null
                        }
                    } finally {
                        frame.recycle()
                    }
                } else {
                    status = "Live frame unavailable. Try another video or player surface."
                }
                delay(1800L)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            status = "Live translation failed: " + (failure.message ?: "unknown error")
        } finally {
            ocr.close()
            translator.close()
        }
    }

    if (enabled && (translated.isNotBlank() || status != null)) {
        Surface(
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            color = Color.Black.copy(alpha = 0.84f),
            contentColor = Color.White,
            shape = MaterialTheme.shapes.large,
            tonalElevation = 0.dp
        ) {
            Column(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (original.isNotBlank()) {
                    Text(original, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.7f))
                }
                if (translated.isNotBlank()) {
                    Text(translated, style = MaterialTheme.typography.titleMedium)
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.72f)) }
            }
        }
    }
}
