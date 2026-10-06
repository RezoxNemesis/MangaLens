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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
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

/**
 * Live subtitle translator with two cooperating paths:
 *
 * 1. Embedded/text subtitle tracks from Media3 are translated directly. This is the preferred,
 *    lossless path when a video already contains subtitles.
 * 2. If no text cue is available, the displayed video frame is sampled and OCR is used to read
 *    burned-in/hardcoded subtitles. OCR waits for a cue to survive multiple frames before showing
 *    it, which greatly reduces flicker and one-frame hallucinations.
 */
@Composable
fun LiveVideoOcrTranslationOverlay(
    enabled: Boolean,
    targetLanguage: String,
    playerView: PlayerView?,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var embeddedSource by remember(playerView) { mutableStateOf("") }
    var embeddedTranslated by remember(playerView) { mutableStateOf("") }

    var ocrOriginal by remember(playerView) { mutableStateOf("") }
    var ocrTranslated by remember(playerView) { mutableStateOf("") }
    var status by remember(playerView) { mutableStateOf<String?>(null) }

    val embeddedCache = remember {
        object : LinkedHashMap<String, String>(32, .75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, String>?
            ): Boolean = size > 64
        }
    }

    DisposableEffect(enabled, playerView) {
        val player = playerView?.player
        if (!enabled || player == null) {
            embeddedSource = ""
            embeddedTranslated = ""
            onDispose { }
        } else {
            val listener = object : Player.Listener {
                override fun onCues(cueGroup: CueGroup) {
                    embeddedSource = cueGroup.cues
                        .mapNotNull { cue -> cue.text?.toString()?.trim() }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .joinToString("\n")
                        .take(1200)
                }
            }
            player.addListener(listener)
            onDispose {
                player.removeListener(listener)
                embeddedSource = ""
                embeddedTranslated = ""
            }
        }
    }

    LaunchedEffect(enabled, targetLanguage, embeddedSource) {
        if (!enabled || embeddedSource.isBlank()) {
            embeddedTranslated = ""
            return@LaunchedEffect
        }

        val signature = normalizeSignature(embeddedSource)
        val key = targetLanguage.lowercase() + "|" + signature
        embeddedCache[key]?.let {
            embeddedTranslated = it
            status = null
            return@LaunchedEffect
        }

        val translator = TranslationService()
        try {
            status = "Translating embedded subtitles…"
            val result = translator.translate(embeddedSource, targetLanguage)
                .trim()
                .ifBlank { embeddedSource }
            embeddedCache[key] = result
            embeddedTranslated = result
            status = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            embeddedTranslated = embeddedSource
            status = "Embedded subtitle translation unavailable: " +
                (failure.message ?: "unknown error")
        } finally {
            translator.close()
        }
    }

    LaunchedEffect(enabled, targetLanguage, playerView) {
        if (!enabled || playerView == null) {
            ocrOriginal = ""
            ocrTranslated = ""
            status = null
            return@LaunchedEffect
        }

        val ocr = AdvancedTranslationEngine(context)
        val translator = TranslationService()
        val translationCache = object : LinkedHashMap<String, String>(48, .75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, String>?
            ): Boolean = size > 72
        }

        var candidate = ""
        var candidateCount = 0
        var lastTranslatedSignature = ""

        try {
            status = "Live subtitles starting…"
            while (true) {
                currentCoroutineContext().ensureActive()

                // Direct subtitle cues are cleaner than OCR. While one is active, avoid wasting CPU
                // sampling the video surface and let Media3's text track drive the overlay.
                if (embeddedSource.isNotBlank()) {
                    candidate = ""
                    candidateCount = 0
                    delay(350L)
                    continue
                }

                val frame = capturePlayerFrame(playerView)
                if (frame == null) {
                    status = "Waiting for a video frame…"
                    delay(700L)
                    continue
                }

                try {
                    val regions = withContext(Dispatchers.Default) { ocr.recognizeFast(frame) }
                    val sourceLines = regions
                        .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
                        .map { it.source.trim() }
                        .filter { line ->
                            line.length >= 2 &&
                                line.any(Char::isLetterOrDigit) &&
                                line.length <= 220
                        }
                        .distinct()
                        .take(6)

                    val source = sourceLines.joinToString(" ").take(700)
                    val signature = normalizeSignature(source)

                    if (signature.isBlank()) {
                        candidate = ""
                        candidateCount = 0
                        status = if (ocrTranslated.isBlank()) {
                            "Looking for visible subtitles…"
                        } else {
                            null
                        }
                    } else {
                        val similar = similarity(signature, candidate) >= .76f
                        if (similar) {
                            candidateCount++
                        } else {
                            candidate = signature
                            candidateCount = 1
                        }

                        if (
                            candidateCount >= REQUIRED_STABLE_FRAMES &&
                            similarity(signature, lastTranslatedSignature) < .92f
                        ) {
                            ocrOriginal = source
                            status = "Translating visible subtitle…"

                            val translatedLines = sourceLines.map { line ->
                                val key = targetLanguage.lowercase() + "|" + normalizeSignature(line)
                                translationCache[key] ?: runCatching {
                                    translator.translate(line, targetLanguage)
                                }.getOrElse { failure ->
                                    if (failure is CancellationException) throw failure
                                    line
                                }.also { translationCache[key] = it }
                            }

                            ocrTranslated = translatedLines.joinToString(" ").take(900)
                            lastTranslatedSignature = signature
                            status = if (ocrTranslated.isBlank()) {
                                "Subtitle detected, but no translation was produced."
                            } else {
                                null
                            }
                        }
                    }
                } finally {
                    frame.recycle()
                }
                delay(OCR_INTERVAL_MS)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            status = "Live subtitle translation failed: " +
                (failure.message ?: "unknown error")
        } finally {
            ocr.close()
            translator.close()
        }
    }

    val displayedTranslation = embeddedTranslated.ifBlank { ocrTranslated }
    val displayedOriginal = embeddedSource.ifBlank { ocrOriginal }
    val sourceLabel = if (embeddedSource.isNotBlank()) "Embedded subtitle" else "On-screen OCR"

    if (enabled && (displayedTranslation.isNotBlank() || status != null)) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            color = Color.Black.copy(alpha = 0.82f),
            contentColor = Color.White,
            shape = MaterialTheme.shapes.large,
            tonalElevation = 0.dp
        ) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                if (displayedTranslation.isNotBlank()) {
                    Text(
                        displayedTranslation,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
                }
                if (
                    displayedOriginal.isNotBlank() &&
                    normalizeSignature(displayedOriginal) != normalizeSignature(displayedTranslation)
                ) {
                    Text(
                        displayedOriginal,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.58f)
                    )
                }
                if (displayedTranslation.isNotBlank()) {
                    Text(
                        sourceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.46f)
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

private suspend fun capturePlayerFrame(playerView: PlayerView): Bitmap? =
    withContext(Dispatchers.Main.immediate) {
        val surface = playerView.videoSurfaceView ?: return@withContext null
        val sourceWidth = surface.width
        val sourceHeight = surface.height
        if (sourceWidth <= 0 || sourceHeight <= 0) return@withContext null

        val targetWidth = sourceWidth.coerceAtMost(960)
        val targetHeight = ((targetWidth.toFloat() / sourceWidth) * sourceHeight)
            .toInt()
            .coerceAtLeast(1)

        when (surface) {
            is TextureView ->
                runCatching { surface.getBitmap(targetWidth, targetHeight) }.getOrNull()
            is SurfaceView -> copySurface(surface, targetWidth, targetHeight)
            else -> null
        }
    }

private suspend fun copySurface(
    surface: SurfaceView,
    width: Int,
    height: Int
): Bitmap? = suspendCancellableCoroutine { continuation ->
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

private const val REQUIRED_STABLE_FRAMES = 2
private const val OCR_INTERVAL_MS = 700L
