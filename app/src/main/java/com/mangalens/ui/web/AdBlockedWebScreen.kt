package com.mangalens.ui.web

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mangalens.core.adblock.AdBlockEngine
import com.mangalens.core.adblock.AdBlockWebViewClient
import com.mangalens.core.translation.TranslationService
import com.mangalens.core.translation.WebTranslationScript
import com.mangalens.download.MediaDownloadManager
import com.mangalens.ui.video.SniffedMedia
import com.mangalens.ui.video.VideoSourcePolicy
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
    onOpenManga: (String) -> Unit = {},
    onOpenVideo: (SniffedMedia, String) -> Unit = { _, _ -> },
    onClose: (() -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val speech = remember { com.mangalens.ui.video.VideoSpeechEngine(context.applicationContext, scope) }
    val speechState by speech.state.collectAsState()
    val captureActive by WebAudioCaptureService.active.collectAsState()
    val captureStatus by WebAudioCaptureService.status.collectAsState()
    var speechSettings by remember { mutableStateOf(false) }
    val projectionManager = remember { context.getSystemService(android.media.projection.MediaProjectionManager::class.java) }
    val projectionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            speech.setEnabled(true)
            WebAudioCaptureService.windowSeconds = { speech.chunkSeconds }
            WebAudioCaptureService.clock = { speech.positionMs = it }
            WebAudioCaptureService.sink = { samples, start ->
                speech.positionMs = start + samples.size * 1000L / 16000
                speech.submitPcm16k(samples, start)
            }
            androidx.core.content.ContextCompat.startForegroundService(context,
                android.content.Intent(context, WebAudioCaptureService::class.java).putExtra("token", result.data))
        }
    }
    val audioPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
    LaunchedEffect(speech) { speech.loadInstalled() }
    LaunchedEffect(captureActive) { if (!captureActive) speech.setEnabled(false) }
    LaunchedEffect(speechState.enabled) {
        if (!speechState.enabled && captureActive) context.stopService(android.content.Intent(context, WebAudioCaptureService::class.java))
    }
    DisposableEffect(speech) { onDispose {
        context.stopService(android.content.Intent(context, WebAudioCaptureService::class.java))
        WebAudioCaptureService.sink = null
        WebAudioCaptureService.windowSeconds = null
        WebAudioCaptureService.clock = null
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { speech.close() }
    } }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, speech) {
        val listener = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                context.stopService(android.content.Intent(context, WebAudioCaptureService::class.java))
                speech.setEnabled(false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(listener)
        onDispose { lifecycleOwner.lifecycle.removeObserver(listener) }
    }
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
    var detectedMedia by remember { mutableStateOf<SniffedMedia?>(null) }
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
                val host = runCatching { java.net.URI(currentUrl).host.orEmpty().lowercase() }.getOrDefault("")
                translationStatus = if (
                    host.endsWith("youtube.com") || host.endsWith("instagram.com") ||
                    host.endsWith("x.com") || host.endsWith("twitter.com")
                ) "Dynamic media page • use English CC, Video mode or Live OCR for media content."
                else "No readable page text found."
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

    if (speechSettings) androidx.compose.ui.window.Dialog(onDismissRequest = { speechSettings = false }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                com.mangalens.ui.video.LiveAudioSubtitleSettings(speech)
                if (captureStatus.isNotBlank()) Text(captureStatus)
                Text("Android asks for audio and capture permission. Site/device capture restrictions may require Open in Video. Only MangaLens playback is captured; audio stays on your device.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    if (captureActive) {
                        context.stopService(android.content.Intent(context, WebAudioCaptureService::class.java)); speech.setEnabled(false)
                    } else if (android.os.Build.VERSION.SDK_INT >= 29) {
                        audioPermission.launch(android.Manifest.permission.RECORD_AUDIO)
                    }
                    speechSettings = false
                }, enabled = speechState.ready && !speechState.busy && android.os.Build.VERSION.SDK_INT >= 29) {
                    Text(if (captureActive) "Stop web capture" else "Start web audio capture")
                }
                if (android.os.Build.VERSION.SDK_INT < 29) Text("Web audio capture requires Android 10+. Use Open in Video on this device.")
                TextButton(onClick = { speechSettings = false }) { Text("Close") }
            }
        }
    }

    val browserChromeVisible = hudVisible || loadProgress < 100
    val browserTopInset by animateDpAsState(
        targetValue = if (browserChromeVisible) 66.dp else 0.dp,
        animationSpec = tween(220),
        label = "webTopInset"
    )

    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(top = browserTopInset),
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
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): android.webkit.WebResourceResponse? {
                            val mediaUrl = request?.url?.toString()
                            if (mediaUrl != null && VideoSourcePolicy.isLikelyMediaRequest(mediaUrl)) {
                                val allowed = setOf("accept", "cookie", "origin", "referer", "user-agent")
                                val headers = request.requestHeaders
                                    .filter { (name, value) -> name.lowercase() in allowed && value.length <= 16_384 }
                                val candidate = SniffedMedia(mediaUrl, headers, "WEB")
                                view?.post {
                                    val current = detectedMedia
                                    if (
                                        current == null ||
                                        VideoSourcePolicy.mediaScore(candidate.url) >= VideoSourcePolicy.mediaScore(current.url)
                                    ) {
                                        detectedMedia = candidate
                                    }
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, pageUrl, favicon)
                            pageReady = false
                            detectedMedia = null
                            speech.invalidate(clear = true)
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

        AnimatedVisibility(
            visible = browserChromeVisible,
            enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { -it / 2 },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(190)) { -it / 2 },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
        Surface(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 5.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xF20A0D14),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .42f)),
            shadowElevation = 8.dp
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(pageTitle.ifBlank { "Web" }, maxLines = 1, style = MaterialTheme.typography.titleSmall)
                    Text(
                        runCatching { java.net.URI(currentUrl).host?.removePrefix("www.") }.getOrNull().orEmpty(),
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { webView?.goBack() }, enabled = canGoBack, contentPadding = PaddingValues(horizontal = 7.dp)) { Text("‹") }
                TextButton(onClick = { webView?.goForward() }, enabled = canGoForward, contentPadding = PaddingValues(horizontal = 7.dp)) { Text("›") }
                TextButton(
                    onClick = { if (loadProgress < 100) webView?.stopLoading() else webView?.reload() },
                    contentPadding = PaddingValues(horizontal = 7.dp)
                ) { Text(if (loadProgress < 100) "■" else "↻") }
                if (detectedMedia != null) {
                    TextButton(
                        onClick = {
                            val media = detectedMedia ?: return@TextButton
                            val cookie = android.webkit.CookieManager.getInstance().getCookie(media.url).orEmpty()
                            val enrichedHeaders = buildMap {
                                putAll(media.headers)
                                if (cookie.isNotBlank()) put("Cookie", cookie)
                                if (keys.none { it.equals("Referer", ignoreCase = true) }) put("Referer", currentUrl)
                            }
                            onOpenVideo(media.copy(headers = enrichedHeaders), currentUrl)
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) { Text("Video") }
                }
                TextButton(
                    onClick = {
                        siteAdBlockEnabled = !siteAdBlockEnabled
                        webView?.reload()
                    },
                    enabled = adBlockEnabled,
                    contentPadding = PaddingValues(horizontal = 7.dp)
                ) { Text(if (adBlockEnabled && siteAdBlockEnabled) "Ads ✓" else "Ads") }
                TextButton(onClick = { hudVisible = true }, contentPadding = PaddingValues(horizontal = 7.dp)) { Text("•••") }
            }
            if (loadProgress < 100) LinearProgressIndicator(progress = loadProgress / 100f, modifier = Modifier.fillMaxWidth())
        }
        }
        if (captureActive) com.mangalens.ui.video.LiveAudioSubtitleOverlay(speech,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (hudVisible) 125.dp else 24.dp, start = 16.dp, end = 16.dp))
        if (hudVisible || translating) {
            Surface(
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(12.dp),
                tonalElevation = 2.dp,
                shadowElevation = 14.dp,
                color = Color(0xF00C1018),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .58f)),
                shape = RoundedCornerShape(22.dp)
            ) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        com.mangalens.ui.video.VideoDockAction(
                            label = if (translating) "Translating…" else if (translated) "Translated" else "Translate",
                            status = if (translated) targetLanguage.uppercase() else "OCR / text",
                            selected = translated,
                            enabled = pageReady,
                            onClick = {
                                translated = !translated
                                hudVisible = true
                            }
                        )
                        com.mangalens.ui.video.VideoDockAction(
                            label = "Download",
                            status = "Resolve best media",
                            onClick = {
                                scope.launch {
                                    translationStatus = "Resolving the best accessible media source…"
                                    runCatching {
                                        MediaDownloadManager(context).enqueue(
                                            currentUrl,
                                            title = pageTitle.takeIf(String::isNotBlank),
                                            quality = com.mangalens.download.DownloadQuality.BEST,
                                            sourcePageUrl = currentUrl,
                                            headers = detectedMedia?.headers.orEmpty()
                                        )
                                    }.onSuccess {
                                        translationStatus = "Download queued from " +
                                            (runCatching { java.net.URI(currentUrl).host?.removePrefix("www.") }.getOrNull() ?: "source")
                                    }.onFailure {
                                        translationStatus = "Download failed: " + (it.message ?: "unable to resolve media")
                                    }
                                }
                            }
                        )
                        com.mangalens.ui.video.VideoDockAction(
                            label = "English CC",
                            status = if (captureActive) "Live audio" else "Speech",
                            selected = captureActive,
                            onClick = { speechSettings = true }
                        )
                        com.mangalens.ui.video.VideoDockAction(
                            label = "Manga",
                            status = "Reader",
                            enabled = pageReady,
                            onClick = { onOpenManga(currentUrl) }
                        )
                        com.mangalens.ui.video.VideoDockAction(
                            label = "Site data",
                            status = "Cookies / cache",
                            onClick = { clearSiteDialog = true }
                        )
                        com.mangalens.ui.video.VideoDockAction(
                            label = if (autoScroll) "Pause scroll" else "Auto scroll",
                            status = if (autoScroll) "${speed.toInt()}x" else null,
                            selected = autoScroll,
                            onClick = { autoScroll = !autoScroll }
                        )
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
