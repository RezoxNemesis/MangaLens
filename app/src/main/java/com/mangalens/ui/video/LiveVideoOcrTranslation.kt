package com.mangalens.ui.video

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

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
        val translationCache = object : LinkedHashMap<String, String>(32, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 48
        }

        var candidate = ""
        var candidateCount = 0
        var lastTranslatedSignature = ""

        try {
            status = "Live subtitles starting…"
            while (true) {
                currentCoroutineContext().ensureActive()
                val frame = capturePlayerFrame(playerView)
                if (frame == null) {
                    status = "Waiting for a video frame…"
                    delay(900L)
                    continue
                }

                try {
                    val regions = withContext(Dispatchers.Default) { ocr.recognizeFast(frame) }
                    val sourceLines = regions
                        .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
                        .map { it.source.trim() }
                        .filter { line -> line.length >= 2 && line.any(Char::isLetterOrDigit) }
                        .distinct()
                        .take(6)

                    val source = sourceLines.joinToString(" ").take(700)
                    val signature = normalizeSignature(source)

                    if (signature.isBlank()) {
                        candidate = ""
                        candidateCount = 0
                        status = if (translated.isBlank()) "Looking for visible subtitles…" else null
                    } else {
                        val similar = similarity(signature, candidate) >= .76f
                        if (similar) {
                            candidateCount++
                        } else {
                            candidate = signature
                            candidateCount = 1
                        }

                        // Require the text to survive two frames. This removes most single-frame OCR
                        // noise without making normal subtitle changes feel sluggish.
                        if (candidateCount >= 2 && similarity(signature, lastTranslatedSignature) < .92f) {
                            original = source
                            status = "Translating subtitle…"

                            val translatedLines = sourceLines.map { line ->
                                val key = targetLanguage.lowercase() + "|" + normalizeSignature(line)
                                translationCache[key] ?: runCatching {
                                    translator.translate(line, targetLanguage)
                                }.getOrElse { failure ->
                                    if (failure is CancellationException) throw failure
                                    line
                                }.also { translationCache[key] = it }
                            }

                            translated = translatedLines.joinToString(" ").take(900)
                            lastTranslatedSignature = signature
                            status = if (translated.isBlank()) {
                                "Subtitle detected, but no translation was produced."
                            } else {
                                null
                            }
                        }
                    }
                } finally {
                    frame.recycle()
                }
                delay(900L)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            status = "Live subtitle translation failed: " + (failure.message ?: "unknown error")
        } finally {
            ocr.close()
            translator.close()
        }
    }

    if (enabled && (translated.isNotBlank() || status != null)) {
        Surface(
            modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            color = Color.Black.copy(alpha = 0.82f),
            contentColor = Color.White,
            shape = MaterialTheme.shapes.large,
            tonalElevation = 0.dp
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (translated.isNotBlank()) {
                    Text(
                        translated,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }
                if (original.isNotBlank() && original != translated) {
                    Text(
                        original,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.58f)
                    )
                }
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                }
            }
        }
    }
}

private suspend fun capturePlayerFrame(playerView: PlayerView): Bitmap? = withContext(Dispatchers.Main.immediate) {
    val surface = playerView.videoSurfaceView ?: return@withContext null
    val sourceWidth = surface.width
    val sourceHeight = surface.height
    if (sourceWidth <= 0 || sourceHeight <= 0) return@withContext null

    val targetWidth = sourceWidth.coerceAtMost(960)
    val targetHeight = ((targetWidth.toFloat() / sourceWidth) * sourceHeight)
        .toInt()
        .coerceAtLeast(1)

    when (surface) {
        is TextureView -> runCatching { surface.getBitmap(targetWidth, targetHeight) }.getOrNull()
        is SurfaceView -> copySurface(surface, targetWidth, targetHeight)
        else -> null
    }
}

private suspend fun copySurface(surface: SurfaceView, width: Int, height: Int): Bitmap? =
    suspendCancellableCoroutine { continuation ->
        val bitmap = runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }.getOrNull()
        if (bitmap == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }

        runCatching {
            PixelCopy.request(
                surface,
                bitmap,
                { result ->
                    if (!continuation.isActive) {
                        bitmap.recycle()
                    } else if (result == PixelCopy.SUCCESS) {
                        continuation.resume(bitmap)
                    } else {
                        bitmap.recycle()
                        continuation.resume(null)
                    }
                },
                Handler(Looper.getMainLooper())
            )
        }.onFailure {
            bitmap.recycle()
            if (continuation.isActive) continuation.resume(null)
        }

        continuation.invokeOnCancellation {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

private fun normalizeSignature(value: String): String =
    value.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun similarity(a: String, b: String): Float {
    if (a.isBlank() || b.isBlank()) return 0f
    if (a == b) return 1f
    val left = a.split(' ').filter(String::isNotBlank).toSet()
    val right = b.split(' ').filter(String::isNotBlank).toSet()
    if (left.isEmpty() || right.isEmpty()) return 0f
    val intersection = left.count { it in right }
    val union = (left + right).size
    return if (union == 0) 0f else intersection.toFloat() / union
}
