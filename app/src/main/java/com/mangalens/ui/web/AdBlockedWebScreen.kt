package com.mangalens.ui.web

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
    adBlockEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    targetLanguage: String = "hi",
    onOpenManga: (String) -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { AdBlockEngine(com.mangalens.core.adblock.AdBlockStatsStore.shared) }
    val translator = remember { TranslationService() }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(url) }
    var pageTitle by remember { mutableStateOf("") }
    var loadProgress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var navigationEpoch by remember { mutableIntStateOf(0) }
    var clearSiteDialog by remember { mutableStateOf(false) }
    var pageReady by remember { mutableStateOf(false) }
    var autoScroll by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    var translated by remember { mutableStateOf(translationEnabled) }
    var translating by remember { mutableStateOf(false) }
    var translatedCount by remember { mutableIntStateOf(0) }
    var totalTranslatable by remember { mutableIntStateOf(0) }
    var translationStatus by remember { mutableStateOf<String?>(null) }
    var hudVisible by remember { mutableStateOf(true) }
    var siteAdBlockEnabled by remember { mutableStateOf(adBlockEnabled) }
    val latestAdBlockEnabled by rememberUpdatedState(adBlockEnabled)
    val latestSiteAdBlockEnabled by rememberUpdatedState(siteAdBlockEnabled)

    LaunchedEffect(translationEnabled) { translated = translationEnabled }
    LaunchedEffect(adBlockEnabled) { siteAdBlockEnabled = adBlockEnabled }
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
    DisposableEffect(translator) { onDispose {
        webView?.apply { stopLoading(); webChromeClient = null; webViewClient = android.webkit.WebViewClient(); destroy() }
        webView = null
        translator.close()
    } }
    BackHandler(canGoBack) { webView?.goBack() }
    if (clearSiteDialog) AlertDialog(
        onDismissRequest = { clearSiteDialog = false }, title = { Text("Clear all website data?") },
        text = { Text("This signs you out of websites and removes their stored data.") },
        confirmButton = { TextButton(onClick = {
            android.webkit.CookieManager.getInstance().removeAllCookies(null)
            android.webkit.CookieManager.getInstance().flush()
            android.webkit.WebStorage.getInstance().deleteAllData()
            com.mangalens.core.verification.VerificationSessionStore(context).clearAll()
            webView?.clearCache(true)
            clearSiteDialog = false
            webView?.reload()
        }) { Text("Clear") } }, dismissButton = { TextButton(onClick = { clearSiteDialog = false }) { Text("Cancel") } })

    LaunchedEffect(url, webView) {
        val view = webView ?: return@LaunchedEffect
        if (url.isBlank()) return@LaunchedEffect
        pageReady = false
        if (com.mangalens.core.router.UrlEngineRouter.isSafeWebUrl(url)) view.loadUrl(url)
        else translationStatus = "Enter a complete HTTP or HTTPS URL."
    }

    LaunchedEffect(pageReady, translated, webView, targetLanguage, navigationEpoch) {
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
            modifier = Modifier.fillMaxSize().padding(top = 110.dp),
            factory = { ctx ->
                WebView(ctx).apply {
                    com.mangalens.core.web.SafeWebView.configure(this)
                    settings.domStorageEnabled = true
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, progress: Int) { loadProgress = progress }
                        override fun onReceivedTitle(view: WebView?, title: String?) { pageTitle = title.orEmpty() }
                    }
                    setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_UP) hudVisible = true
                        false
                    }
                    webViewClient = object : AdBlockWebViewClient(engine, { latestAdBlockEnabled && latestSiteAdBlockEnabled }) {
                        override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                            pageReady = false
                            navigationEpoch++
                            currentUrl = pageUrl ?: currentUrl
                            translationStatus = null
                        }
                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            if (request?.isForMainFrame == true) translationStatus = "Page could not load. Check your connection and retry."
                        }
                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                            super.onPageFinished(view, pageUrl)
                            currentUrl = pageUrl ?: currentUrl
                            canGoBack = view?.canGoBack() == true
                            canGoForward = view?.canGoForward() == true
                            pageReady = true
                        }
                    }
                    webView = this
                }
            },
            update = { view -> if (webView !== view) webView = view }
        )

        Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding()) {
            Column(Modifier.padding(horizontal = 8.dp)) {
                Text(pageTitle.ifBlank { "Web" }, maxLines = 1, style = MaterialTheme.typography.titleSmall)
                Text(currentUrl, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { webView?.goBack() }, enabled = canGoBack) { Text("Back") }
                    TextButton(onClick = { webView?.goForward() }, enabled = canGoForward) { Text("Forward") }
                    TextButton(onClick = { if (loadProgress < 100) webView?.stopLoading() else webView?.reload() }) { Text(if (loadProgress < 100) "Stop" else "Reload") }
                    TextButton(onClick = { onOpenManga(currentUrl) }, enabled = pageReady) { Text("Open in Manga") }
                    TextButton(
                        onClick = {
                            siteAdBlockEnabled = !siteAdBlockEnabled
                            webView?.reload()
                        },
                        enabled = adBlockEnabled
                    ) { Text(if (adBlockEnabled && siteAdBlockEnabled) "Ad block: On" else "Ad block: Off") }
                    TextButton(onClick = { clearSiteDialog = true }) { Text("Clear site data") }
                }
                if (loadProgress < 100) LinearProgressIndicator(progress = loadProgress / 100f, modifier = Modifier.fillMaxWidth())
            }
        }
        if (hudVisible || translating) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
                tonalElevation = 6.dp,
                shape = MaterialTheme.shapes.large
            ) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = {
                            translated = !translated
                            hudVisible = true
                        }, enabled = pageReady) { Text(if (translating) "Translating…" else if (translated) "Translated ✓" else "Translate") }
                        Button(onClick = {
                            scope.launch {
                                runCatching { MediaDownloadManager(context).enqueue(currentUrl, "MangaLens web page") }
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
