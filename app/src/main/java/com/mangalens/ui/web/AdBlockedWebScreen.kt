package com.mangalens.ui.web

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.webkit.WebView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mangalens.core.adblock.AdBlockEngine
import com.mangalens.core.adblock.AdBlockWebViewClient
import com.mangalens.core.translation.TranslationService
import com.mangalens.core.translation.WebTranslationScript
import com.mangalens.download.MediaDownloadManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONTokener
import kotlin.coroutines.resume

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AdBlockedWebScreen(
    url: String,
    translationEnabled: Boolean,
    modifier: Modifier = Modifier,
    targetLanguage: String = "hi"
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { AdBlockEngine() }
    val translator = remember { TranslationService() }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageReady by remember { mutableStateOf(false) }
    var autoScroll by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var translated by remember { mutableStateOf(translationEnabled) }
    var translating by remember { mutableStateOf(false) }
    var translatedCount by remember { mutableIntStateOf(0) }
    var totalTranslatable by remember { mutableIntStateOf(0) }
    var translationStatus by remember { mutableStateOf<String?>(null) }
    var hudVisible by remember { mutableStateOf(true) }

    LaunchedEffect(translationEnabled) { translated = translationEnabled }
    LaunchedEffect(autoScroll, speed, webView) {
        while (autoScroll && webView != null) {
            webView?.evaluateJavascript("window.scrollBy(0, " + (2.5f * speed) + ");", null)
            delay(16L)
        }
    }
    LaunchedEffect(hudVisible) {
        if (hudVisible) {
            delay(2500L)
            hudVisible = false
        }
    }
    DisposableEffect(translator) { onDispose { translator.close() } }

    LaunchedEffect(url, webView) {
        val view = webView ?: return@LaunchedEffect
        if (url.isBlank()) return@LaunchedEffect
        pageReady = false
        view.loadUrl(url)
    }

    LaunchedEffect(pageReady, translated, webView, targetLanguage, url) {
        val view = webView ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        if (!translated) {
            evaluateJavascriptAwait(view, "window.__mangalensTranslationOff && window.__mangalensTranslationOff();")
            translating = false
            translationStatus = "Original page restored."
            return@LaunchedEffect
        }

        translating = true
        translatedCount = 0
        translationStatus = "Reading page text…"
        try {
            val raw = evaluateJavascriptAwait(view, WebTranslationScript.build(targetLanguage))
            val texts = parseJavascriptStringArray(raw).take(MAX_WEB_TEXT_NODES)
            totalTranslatable = texts.count { it.trim().length >= 2 && it.any(Char::isLetter) }
            if (texts.isEmpty()) {
                translationStatus = "No readable page text found."
                return@LaunchedEffect
            }
            for ((index, original) in texts.withIndex()) {
                currentCoroutineContext().ensureActive()
                val source = original.trim()
                if (source.length < 2 || source.length > 1200 || source.none(Char::isLetter)) continue
                val result = try {
                    translator.translate(source, targetLanguage)
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    translationStatus = "Translation paused: " + (failure.message ?: "language model unavailable")
                    continue
                }
                if (result.isNotBlank() && result != source) {
                    evaluateJavascriptAwait(view, WebTranslationScript.apply(index, result))
                }
                translatedCount++
                translationStatus = "Translating page text… $translatedCount / $totalTranslatable"
            }
            translationStatus = if (translatedCount > 0) "Page translation finished."
                else "No text was translated. Check the language model and retry."
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            translationStatus = "Web translation failed: " + (failure.message ?: "unknown error")
        } finally {
            translating = false
        }
    }

    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_UP) hudVisible = true
                        false
                    }
                    webViewClient = object : AdBlockWebViewClient(engine) {
                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                            super.onPageFinished(view, pageUrl)
                            pageReady = true
                        }
                    }
                    webView = this
                }
            },
            update = { view -> if (webView !== view) webView = view }
        )

        if (hudVisible || translating) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                tonalElevation = 6.dp,
                shape = MaterialTheme.shapes.large
            ) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            translated = !translated
                            hudVisible = true
                        }, enabled = !translating) { Text(if (translating) "Translating…" else if (translated) "Translated ✓" else "Translate") }
                        Button(onClick = {
                            scope.launch {
                                runCatching { MediaDownloadManager(context).enqueue(url, "MangaLens web page") }
                                    .onFailure { translationStatus = "Download failed: " + (it.message ?: "unknown error") }
                            }
                        }) { Text("Download") }
                        Button(onClick = { autoScroll = !autoScroll }) { Text(if (autoScroll) "Pause" else "Scroll") }
                        if (autoScroll) Slider(value = speed, onValueChange = { speed = it }, valueRange = 1f..5f, steps = 3, modifier = Modifier.width(90.dp))
                    }
                    translationStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (translating) LinearProgressIndicator(
                        progress = if (totalTranslatable > 0) translatedCount.toFloat() / totalTranslatable else 0f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

private suspend fun evaluateJavascriptAwait(view: WebView, script: String): String? =
    suspendCancellableCoroutine { continuation ->
        view.post {
            if (!continuation.isActive) return@post
            view.evaluateJavascript(script) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }
    }

private fun parseJavascriptStringArray(raw: String?): List<String> {
    if (raw.isNullOrBlank() || raw == "null") return emptyList()
    return runCatching {
        val payload = JSONTokener(raw).nextValue() as? String ?: return@runCatching emptyList()
        val array = JSONArray(payload)
        (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
    }.getOrDefault(emptyList())
}

private const val MAX_WEB_TEXT_NODES = 160
