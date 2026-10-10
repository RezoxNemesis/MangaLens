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
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import com.mangalens.core.adblock.AdBlockEngine
import com.mangalens.core.adblock.AdBlockWebViewClient
import com.mangalens.core.translation.TranslationService
import com.mangalens.core.translation.WebTranslationScript
import com.mangalens.download.DownloadQuality
import com.mangalens.download.MediaDownloadManager
import com.mangalens.download.MediaLinkResolver
import com.mangalens.download.YtDlpSiteMediaExtractor
import com.mangalens.ui.video.SniffedMedia
import com.mangalens.ui.video.VideoSourcePolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONTokener
import kotlin.coroutines.resume

@SuppressLint("SetJavaScriptEnabled")
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun AdBlockedWebScreen(
    url: String,
    translationEnabled: Boolean,
    adBlockEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    targetLanguage: String = "hi",
    onOpenManga: (String) -> Unit = {},
    onOpenVideo: (SniffedMedia, String) -> Unit = { _, _ -> },
    onClose: (() -> Unit)? = null,
    onPageChanged: (String) -> Unit = {},
    onResearchQuestion: ((String) -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val session = remember(context.applicationContext) { BrowserWorkspaceRepository.session(context) }
    BrowserWorkspaceScreen(session, url, translationEnabled, adBlockEnabled, modifier, targetLanguage,
        onOpenManga, onOpenVideo, onClose, onPageChanged, onResearchQuestion)
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun GuardedBrowserTabScreen(
    url: String, tab: BrowserTab, workspace: BrowserWorkspaceSnapshot, session: BrowserWorkspaceSession, workspaceError: String?,
    translationEnabled: Boolean, adBlockEnabled: Boolean, modifier: Modifier, targetLanguage: String,
    onOpenManga: (String) -> Unit, onOpenVideo: (SniffedMedia, String) -> Unit,
    onClose: (() -> Unit)?, onPageChanged: (String) -> Unit, onResearchQuestion: ((String) -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var showAddress by remember { mutableStateOf(url.isBlank()) }
    var address by remember { mutableStateOf(url) }
    var addressError by remember { mutableStateOf<String?>(null) }
    val captureGate = remember { BrowserCaptureGate() }
    val captureAuthority = remember { java.util.concurrent.atomic.AtomicReference<BrowserUploadScope?>(null) }
    var capturePending by remember { mutableStateOf(false) }
    var captureConsentStatus by remember { mutableStateOf<String?>(null) }
    fun captureOwner(): BrowserUploadScope? = captureAuthority.get()?.takeIf { session.state.value?.activeTabId == it.tabId }
    val scope = rememberCoroutineScope()
    val speech = remember { com.mangalens.ui.video.VideoSpeechEngine(context.applicationContext, scope) }
    val speechState by speech.state.collectAsState()
    val captureActive by WebAudioCaptureService.active.collectAsState()
    val captureStatus by WebAudioCaptureService.status.collectAsState()
    var speechSettings by remember { mutableStateOf(false) }
    var showDomTools by remember { mutableStateOf(false) }
    var showCleanReading by remember { mutableStateOf(false) }
    var showResearchQuestion by remember { mutableStateOf(false) }
    var linkExport by remember { mutableStateOf<Triple<BrowserDomOwner, BrowserPublicLink, Boolean>?>(null) }
    val projectionManager = remember { context.getSystemService(android.media.projection.MediaProjectionManager::class.java) }
    val projectionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val owned = captureGate.finish(captureOwner())
        capturePending = false
        if (owned && result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            WebAudioCaptureService.windowSeconds = { speech.chunkSeconds }
            WebAudioCaptureService.clock = { speech.positionMs = it }
            WebAudioCaptureService.sink = { samples, start ->
                speech.positionMs = start + samples.size * 1000L / 16000
                speech.submitPcm16k(samples, start)
            }
            androidx.core.content.ContextCompat.startForegroundService(context,
                android.content.Intent(context, WebAudioCaptureService::class.java).putExtra("token", result.data))
        } else if (owned) captureConsentStatus = "Web audio capture was not approved. You can retry from English CC."
    }
    val audioPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && captureGate.mayProject(captureOwner())) {
            try { projectionLauncher.launch(projectionManager.createScreenCaptureIntent()) }
            catch (_: Exception) {
                if (captureGate.finish(captureOwner())) captureConsentStatus = "Android audio capture is unavailable. Try Open in Video."
                capturePending = false
            }
        } else {
            if (captureGate.finish(captureOwner())) captureConsentStatus = "Audio permission was not granted. You can retry from English CC."
            capturePending = false
        }
    }
    LaunchedEffect(speech) { speech.loadInstalled() }
    LaunchedEffect(captureActive) {
        // Bind the subtitle engine to real Android playback capture, not merely to a UI toggle.
        // This removes the old false "Active" state when permission is denied or capture fails.
        speech.setEnabled(captureActive)
    }
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
    val mediaResolver = remember {
        MediaLinkResolver(
            siteExtractor = YtDlpSiteMediaExtractor(context, allowSeparateStreams = true)
        )
    }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var captionWebViewToken by remember { mutableStateOf("") }
    var currentUrl by remember { mutableStateOf(url) }
    var pageTitle by remember { mutableStateOf("") }
    var pageLoad by remember { mutableStateOf(WebPageLoadState()) }
    val loadProgress = pageLoad.progress
    val pageReady = pageLoad.pageReady
    val navigationEpoch = pageLoad.navigation?.epoch ?: 0L
    val canGoBack = workspace.activeTab.canGoBack
    val canGoForward = workspace.activeTab.canGoForward
    var clearSiteDialog by remember { mutableStateOf(false) }
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
    val revoked = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var commandSerial by remember { mutableLongStateOf(0) }
    var awaitingDispatch by remember { mutableStateOf(false) }
    var mobileUserAgent by remember { mutableStateOf("") }
    var panel by remember { mutableStateOf<BrowserWorkspacePanel?>(null) }
    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var findSearching by remember { mutableStateOf(false) }
    var findCount by remember { mutableIntStateOf(-1) }
    var findOrdinal by remember { mutableIntStateOf(1) }
    var findError by remember { mutableStateOf<String?>(null) }
    var findSerial by remember { mutableLongStateOf(0) }
    val uploadBridge = rememberBrowserFileUploadBridge(currentScope = {
        val view = webView
        val ticket = pageLoad.navigation
        if (!revoked.get() && session.state.value?.activeTabId == tab.id && view != null && ticket != null && pageLoad.readyFor(ticket) &&
            view.url?.let { WebPageLoadState.sameDocument(it, ticket.url) } == true)
            BrowserUploadScope(tab.id, ticket.epoch, ticket.url) else null
    }, onStatus = { translationStatus = it })

    val sourceCaptions = remember(session, tab.id, context.applicationContext) {
        BrowserSourceCaptionController(context, session.state, currentHost = {
            val view = webView
            val ticket = pageLoad.navigation
            val current = session.state.value
            if (!revoked.get() && current?.activeTabId == tab.id && view != null && ticket != null &&
                pageLoad.readyFor(ticket) && current.activeTab.url == ticket.url && view.url == ticket.url &&
                captionWebViewToken.matches(Regex("[a-f0-9]{32}")))
                BrowserCaptionLiveHost(BrowserCaptionPageOwner(tab.id, ticket.epoch, ticket.url, captionWebViewToken), view)
            else null
        })
    }
    val sourceCaptionState by sourceCaptions.state.collectAsState()

    fun owns(view: WebView?): Boolean = !revoked.get() && view != null && view === webView && session.state.value?.activeTabId == tab.id
    fun clearFind() {
        findSerial++
        findOpen = false; findSearching = false; findCount = -1; findError = null
        webView?.setFindListener(null); webView?.clearMatches()
    }

    fun observeBrowser(
        action: String, view: WebView? = webView, callbackUrl: String? = null,
        targetUrl: String? = null, detail: String = ""
    ) {
        if (!WebNavigationDiagnostics.enabled) return
        WebNavigationDiagnostics.observe(action, pageLoad, currentUrl, view?.url,
            callbackUrl, targetUrl, detail, view?.let(System::identityHashCode))
    }

    fun beginNavigation(target: String, record: Boolean = true, replace: Boolean = false) {
        session.domTools?.retire()
        sourceCaptions.retire()
        captureGate.revoke(); captureAuthority.set(null); captureConsentStatus = null
        context.stopService(android.content.Intent(context, WebAudioCaptureService::class.java))
        uploadBridge.cancel()
        clearFind()
        webView?.let { (it.webViewClient as? AdBlockWebViewClient)?.prepareForNavigation(it) }
        if (record) session.recordNavigation(tab.id, target, replace)
        pageLoad = pageLoad.start(target)
        currentUrl = target
        pageTitle = ""
        detectedMedia = null
        speech.invalidate(clear = true)
        translating = false
        translatedCount = 0
        totalTranslatable = 0
        translationStatus = null
        hudVisible = true
        observeBrowser("begin_navigation", targetUrl = target)
    }

    fun loadPage(target: String, canDispatch: () -> Boolean = { true }, onDispatched: (Boolean) -> Unit = {}) {
        val view = webView
        if (view == null || !owns(view) || !canDispatch() || runCatching { requireBrowserUrl(target) }.isFailure) {
            onDispatched(false); return
        }
        val serial = ++commandSerial
        uploadBridge.cancel(); clearFind()
        beginNavigation(target, record = false)
        awaitingDispatch = true
        val ticket = pageLoad.navigation ?: run { onDispatched(false); return }
        scope.launch {
            var dispatched = false
            try {
                val saved = session.recordNavigation(tab.id, target).await()
                if (!canDispatch()) {
                    if (owns(view) && commandSerial == serial && pageLoad.navigation == ticket) {
                        awaitingDispatch = false
                        pageLoad = pageLoad.stopped(ticket)
                    }
                    return@launch
                }
                if (!owns(view) || commandSerial != serial || !pageLoad.loading || pageLoad.navigation != ticket ||
                    saved.activeTabId != tab.id || saved.activeTab.url != target) return@launch
                observeBrowser("load_url_command", view, targetUrl = target)
                awaitingDispatch = false
                view.loadUrl(target)
                dispatched = true
                observeBrowser("load_url_dispatched", view, targetUrl = target)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (owns(view) && commandSerial == serial && pageLoad.navigation == ticket) {
                    awaitingDispatch = false
                    pageLoad = pageLoad.failed(ticket, target, true, "Page address could not be saved. Retry when storage is available.")
                }
            } finally { onDispatched(dispatched) }
        }
    }

    val domTools = remember(session, tab.id) {
        com.mangalens.orez.agent.OrezBrowserTools(BrowserDomExecutor(currentHost = {
            val view = webView
            val ticket = pageLoad.navigation
            val current = session.state.value
            if (view == null || ticket == null || !owns(view) || !view.isAttachedToWindow || !pageLoad.readyFor(ticket) ||
                current == null || current.activeTabId != tab.id || current.activeTab.url != ticket.url ||
                !WebPageLoadState.sameDocument(view.url.orEmpty(), ticket.url) || !BrowserDomPolicy.permittedAddress(ticket.url)) null
            else {
                val owner = BrowserDomOwner(tab.id, ticket.epoch, ticket.url, captionWebViewToken)
                BrowserDomWebViewHost(owner, view, stillOwned = {
                    owns(view) && pageLoad.readyFor(ticket) && captionWebViewToken == owner.viewToken &&
                        session.state.value?.activeTab?.url == owner.url &&
                        WebPageLoadState.sameDocument(view.url.orEmpty(), owner.url)
                }, navigation = { target, stillExecuting ->
                    suspendCancellableCoroutine { continuation ->
                        loadPage(target, canDispatch = { continuation.isActive && stillExecuting() }, onDispatched = { accepted ->
                            if (continuation.isActive) continuation.resume(accepted)
                        })
                    }
                })
            }
        }, clock = android.os.SystemClock::elapsedRealtime))
    }
    DisposableEffect(session, domTools) {
        session.bindDomTools(domTools)
        onDispose { session.unbindDomTools(domTools) }
    }
    if (showDomTools) BrowserDomAgentDialog(domTools) { showDomTools = false }
    if (showCleanReading) BrowserCleanReadingDialog(domTools, onOpenManga) { showCleanReading = false }
    if (showResearchQuestion && onResearchQuestion != null) BrowserResearchDialog(onReview = { question ->
        showResearchQuestion = false
        onResearchQuestion(question)
    }, onDismiss = { showResearchQuestion = false })
    fun preparePublicLink(share: Boolean) {
        val owner = domTools.currentOwner()
        val publicLink = owner?.let { BrowserPublicLinkPolicy.prepare(it.url) }
        if (owner == null || publicLink == null) translationStatus = "A public page link is unavailable for this address."
        else linkExport = Triple(owner, publicLink, share)
    }
    linkExport?.let { export ->
        BrowserPublicLinkDialog(export.second, export.third, onDismiss = { linkExport = null }, onConfirm = {
            linkExport = null
            if (domTools.currentOwner() != export.first) translationStatus = "The page changed. Select its public link again."
            else try {
                if (export.third) {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, export.second.address)
                    context.startActivity(android.content.Intent.createChooser(intent, "Share page link"))
                } else {
                    requireNotNull(context.getSystemService(android.content.ClipboardManager::class.java))
                        .setPrimaryClip(android.content.ClipData.newPlainText("Public page link", export.second.address))
                    translationStatus = "Public page link copied."
                }
            } catch (_: Exception) { translationStatus = if (export.third) "No sharing app is available." else "The page link could not be copied." }
        })
    }


    fun reloadPage() {
        val view = webView ?: return
        if (!owns(view)) return
        val plan = planWebReload(pageLoad, view.url, currentUrl,
            com.mangalens.core.router.UrlEngineRouter::isSafeWebUrl) ?: return
        observeBrowser("reload_requested", view, targetUrl = plan.url,
            detail = if (plan.reloadCurrent) "reload" else "load_url")
        val serial = ++commandSerial
        beginNavigation(plan.url, record = false)
        awaitingDispatch = true
        val ticket = pageLoad.navigation ?: return
        scope.launch {
            try {
                val saved = session.recordNavigation(tab.id, plan.url).await()
                if (!owns(view) || serial != commandSerial || !pageLoad.loading || pageLoad.navigation != ticket ||
                    saved.activeTabId != tab.id || saved.activeTab.url != plan.url) return@launch
                observeBrowser("reload_command", view, targetUrl = plan.url,
                    detail = if (plan.reloadCurrent) "reload" else "load_url")
                awaitingDispatch = false
                if (plan.reloadCurrent && view.url?.let { WebPageLoadState.sameDocument(it, plan.url) } == true) view.reload()
                    else view.loadUrl(plan.url)
                observeBrowser("reload_dispatched", view, targetUrl = plan.url)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (owns(view) && commandSerial == serial && pageLoad.navigation == ticket) {
                    awaitingDispatch = false
                    pageLoad = pageLoad.failed(ticket, plan.url, true, "Page address could not be saved. Retry when storage is available.")
                }
            }
        }
    }

    fun navigateHistory(delta: Int) {
        val view = webView ?: return
        if (!owns(view)) return
        val history = view.copyBackForwardList()
        val captured = session.state.value?.activeTab?.takeIf { it.id == tab.id } ?: return
        val plan = planBrowserHistory(captured, delta, (0 until history.size).map { history.getItemAtIndex(it).url }, history.currentIndex) ?: return
        val serial = ++commandSerial
        uploadBridge.cancel(); clearFind()
        beginNavigation(plan.url, record = false)
        awaitingDispatch = true
        val ticket = pageLoad.navigation ?: return
        scope.launch {
            try {
                val saved = session.moveHistory(captured, delta).await()
                if (!owns(view) || serial != commandSerial || !pageLoad.loading || pageLoad.navigation != ticket ||
                    saved.activeTabId != tab.id || saved.activeTab.url != plan.url) return@launch
                observeBrowser("history_command", view, targetUrl = plan.url, detail = delta.toString())
                val currentNative = view.copyBackForwardList()
                val nativeIndex = currentNative.currentIndex + delta
                awaitingDispatch = false
                if (plan.nativeDelta != null && nativeIndex in 0 until currentNative.size && currentNative.getItemAtIndex(nativeIndex)?.url == plan.url)
                    view.goBackOrForward(delta) else view.loadUrl(plan.url)
                observeBrowser("history_dispatched", view, targetUrl = plan.url, detail = delta.toString())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (owns(view) && commandSerial == serial && pageLoad.navigation == ticket) {
                    awaitingDispatch = false
                    pageLoad = pageLoad.failed(ticket, plan.url, true, "Browser history could not be saved. Retry when storage is available.")
                }
            }
        }
    }

    fun callbackTicket(view: WebView?, callbackUrl: String?): WebNavigationTicket? {
        if (!owns(view) || awaitingDispatch) return null
        val ticket = pageLoad.navigation ?: return null
        if (!pageLoad.accepts(ticket, callbackUrl)) return null
        val visibleUrl = view?.url
        if (visibleUrl != null && callbackUrl != null && !WebPageLoadState.sameDocument(visibleUrl, callbackUrl)) return null
        // WebView supplies no original request ID. URL checks reject another document's
        // callbacks; captured tickets also fence our own asynchronous continuations.
        return ticket
    }

    fun findOnPage() {
        val view = webView ?: return
        val ticket = pageLoad.navigation ?: return
        if (!owns(view) || !pageLoad.readyFor(ticket) || findSearching || findQuery.isBlank()) return
        val query = findQuery
        val serial = ++findSerial
        findSearching = true; findCount = -1; findError = null
        view.setFindListener { index, count, done ->
            if (!owns(view) || !findOpen || findSerial != serial || !pageLoad.readyFor(ticket) || findQuery != query) return@setFindListener
            if (done) { findCount = count.coerceAtLeast(0); findOrdinal = index + 1; findSearching = false }
        }
        view.findAllAsync(query)
        scope.launch {
            delay(10_000)
            if (owns(view) && findOpen && findSerial == serial && findSearching) {
                findSerial++; findSearching = false; findError = "Page search did not finish. Close and retry."
                view.setFindListener(null); view.clearMatches()
            }
        }
    }

    fun enrichSniffedMedia(media: SniffedMedia): SniffedMedia {
        val cookie = runCatching {
            android.webkit.CookieManager.getInstance().getCookie(media.url)
        }.getOrNull().orEmpty()
        val headers = buildMap {
            putAll(media.headers)
            if (cookie.isNotBlank() && keys.none { it.equals("Cookie", true) }) put("Cookie", cookie)
            if (keys.none { it.equals("Referer", true) }) put("Referer", currentUrl)
            if (keys.none { it.equals("User-Agent", true) }) {
                put("User-Agent", webView?.settings?.userAgentString ?: com.mangalens.ui.video.MediaRequestContext.USER_AGENT)
            }
            if (keys.none { it.equals("Accept", true) }) put("Accept", "*/*")
        }
        return media.copy(headers = headers)
    }

    fun openCurrentVideo() {
        val view = webView ?: return
        val ticket = callbackTicket(view, currentUrl) ?: return
        if (!pageLoad.readyFor(ticket)) return
        val sourcePage = currentUrl
        val capturedTitle = pageTitle.takeIf(String::isNotBlank)
        val capturedUserAgent = view.settings.userAgentString
        val capturedFallback = detectedMedia?.let(::enrichSniffedMedia)
        scope.launch {
            translationStatus = "Resolving the best playable media source…"
            val resolved = try { withContext(Dispatchers.IO) { mediaResolver.resolveCancellable(sourcePage, DownloadQuality.BEST) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (callbackTicket(view, sourcePage) != ticket || !pageLoad.readyFor(ticket)) return@launch
            if (resolved != null) {
                val videoCookie = runCatching {
                    android.webkit.CookieManager.getInstance().getCookie(resolved.url)
                }.getOrNull().orEmpty()
                val videoHeaders = buildMap {
                    putAll(resolved.headers)
                    if (videoCookie.isNotBlank() && keys.none { it.equals("Cookie", true) }) put("Cookie", videoCookie)
                    if (keys.none { it.equals("Referer", true) }) put("Referer", sourcePage)
                    if (keys.none { it.equals("User-Agent", true) }) {
                        put("User-Agent", capturedUserAgent ?: com.mangalens.ui.video.MediaRequestContext.USER_AGENT)
                    }
                    if (keys.none { it.equals("Accept", true) }) put("Accept", "*/*")
                }
                val audioHeaders = resolved.audioUrl?.let { audio ->
                    val audioCookie = runCatching {
                        android.webkit.CookieManager.getInstance().getCookie(audio)
                    }.getOrNull().orEmpty()
                    buildMap {
                        putAll(resolved.audioHeaders)
                        if (audioCookie.isNotBlank() && keys.none { it.equals("Cookie", true) }) put("Cookie", audioCookie)
                        if (keys.none { it.equals("Referer", true) }) put("Referer", sourcePage)
                        if (keys.none { it.equals("User-Agent", true) }) {
                            put("User-Agent", capturedUserAgent ?: com.mangalens.ui.video.MediaRequestContext.USER_AGENT)
                        }
                        if (keys.none { it.equals("Accept", true) }) put("Accept", "*/*")
                    }
                }.orEmpty()
                onOpenVideo(
                    captureResolvedBrowserMedia(resolved, videoHeaders, audioHeaders, capturedTitle),
                    sourcePage
                )
                translationStatus = null
                return@launch
            }

            val fallback = capturedFallback
            if (fallback != null && !isYoutubePage(sourcePage)) {
                onOpenVideo(fallback, sourcePage)
                translationStatus = null
            } else {
                translationStatus = if (isYoutubePage(sourcePage))
                    "YouTube did not expose a fresh playable source. Reload the page and retry."
                else "No concrete video request has been detected yet. Start the video, then retry."
            }
        }
    }

    LaunchedEffect(translationEnabled) { translated = translationEnabled }
    LaunchedEffect(adBlockEnabled) {
        siteAdBlockEnabled = adBlockEnabled
        webView?.let { (it.webViewClient as? AdBlockWebViewClient)?.prepareForNavigation(it) }
    }
    LaunchedEffect(autoScroll, speed, webView) {
        while (autoScroll && webView != null) {
            if (!owns(webView)) break
            if (!pageReady) { delay(16L); continue }
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
        BrowserLifecycleDiagnostics.mark(BrowserLifecyclePhase.DISPOSE_BEGIN)
        sourceCaptions.close()
        revoked.set(true)
        captureGate.revoke(); captureAuthority.set(null)
        uploadBridge.cancel()
        (webView?.webViewClient as? AdBlockWebViewClient)?.clearScriptRegistration()
        observeBrowser("dispose")
        webView?.apply {
            stopLoading(); setFindListener(null); webChromeClient = null; webViewClient = android.webkit.WebViewClient()
            browserLifecycleViewEvent(BrowserLifecyclePhase.WEBVIEW_DESTROY_BEGIN, this)
            destroy()
            browserLifecycleViewEvent(BrowserLifecyclePhase.WEBVIEW_DESTROY_END, this)
        }
        webView = null
        translator.close()
        BrowserLifecycleDiagnostics.mark(BrowserLifecyclePhase.DISPOSE_END)
    } }
    BackHandler(canGoBack) { navigateHistory(-1) }
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
            reloadPage()
        }) { Text("Clear") } }, dismissButton = { TextButton(onClick = { clearSiteDialog = false }) { Text("Cancel") } })

    LaunchedEffect(webView) {
        val view = webView ?: return@LaunchedEffect
        if (url.isBlank()) return@LaunchedEffect
        if (view.url == url) return@LaunchedEffect
        if (com.mangalens.core.router.UrlEngineRouter.isSafeWebUrl(url)) loadPage(url)
        else translationStatus = "Enter a complete HTTP or HTTPS URL."
    }

    LaunchedEffect(tab.desktop, webView) {
        val view = webView ?: return@LaunchedEffect
        val desired = if (tab.desktop) browserDesktopUserAgent(mobileUserAgent) else mobileUserAgent
        if (desired.isNotEmpty() && view.settings.userAgentString != desired) {
            view.settings.userAgentString = desired
            view.settings.useWideViewPort = tab.desktop
            view.settings.loadWithOverviewMode = tab.desktop
            if (currentUrl.isNotBlank() && !awaitingDispatch) reloadPage()
        }
    }

    if (showAddress) AlertDialog(
        onDismissRequest = { observeBrowser("address_dismissed"); showAddress = false },
        title = { Text("Search or open a website") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(address, {
                val hadError = addressError != null
                address = it; addressError = null
                if (WebNavigationDiagnostics.enabled) observeBrowser("address_changed",
                    targetUrl = BrowserAddress.resolve(it), detail = "had_error=$hadError;nonblank=${it.isNotBlank()}")
            }, singleLine = true,
                label = { Text("URL or search") }, modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "Website URL or search"
                })
            addressError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } },
        confirmButton = { TextButton(onClick = {
            val value = address.trim()
            val target = BrowserAddress.resolve(value)
            observeBrowser("address_open_requested", targetUrl = target,
                detail = "resolved=${target != null};view_ready=${webView != null}")
            if (target == null) {
                addressError = "Use an HTTP(S) URL or a search phrase."
                observeBrowser("address_open_rejected")
            } else {
                loadPage(target); showAddress = false; hudVisible = true
                observeBrowser("address_open_accepted", targetUrl = target)
            }
        }, enabled = address.isNotBlank()) { Text("Open") } },
        dismissButton = { TextButton(onClick = { observeBrowser("address_cancelled"); showAddress = false }) { Text("Cancel") } }
    )

    LaunchedEffect(pageReady, translated, webView, targetLanguage, navigationEpoch) {
        val view = webView ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        val ticket = pageLoad.navigation ?: return@LaunchedEffect
        val guard = BrowserPageMutationGuard { callbackTicket(view, ticket.url) == ticket && pageLoad.readyFor(ticket) }
        if (!guard.current()) return@LaunchedEffect
        if (!translated) {
            guard.await { evaluateJavascriptAwait(view, "window.__mangalensTranslationOff && window.__mangalensTranslationOff();", guard) }
                ?: return@LaunchedEffect
            guard.publish { translating = false; translationStatus = "Page translation turned off." }
            return@LaunchedEffect
        }

        if (!guard.publish {
            translating = true; translatedCount = 0; translationStatus = "Reading page text…"
        }) return@LaunchedEffect
        try {
            val raw = guard.await { evaluateJavascriptAwait(view, WebTranslationScript.build(targetLanguage), guard) }
                ?: return@LaunchedEffect
            val texts = parseJavascriptStringArray(raw.value).take(MAX_WEB_TEXT_NODES)
            if (!guard.publish { totalTranslatable = texts.count { it.trim().length in 2..1200 && it.any(Char::isLetter) } }) return@LaunchedEffect
            if (texts.isEmpty()) {
                val host = runCatching { java.net.URI(currentUrl).host.orEmpty().lowercase() }.getOrDefault("")
                guard.publish { translationStatus = if (
                    host.endsWith("youtube.com") || host.endsWith("instagram.com") ||
                    host.endsWith("x.com") || host.endsWith("twitter.com")
                ) "Dynamic media page • use English CC, Video mode or Live OCR for media content."
                else "No readable page text found." }
                return@LaunchedEffect
            }
            var unchangedCount = 0
            var unavailableCount = 0
            var processedCount = 0
            for ((index, original) in texts.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (!guard.current()) return@LaunchedEffect
                val source = original.trim()
                if (source.length < 2 || source.length > 1200 || source.none(Char::isLetter)) continue
                val result = try {
                    guard.await { translator.translate(source, targetLanguage) } ?: return@LaunchedEffect
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    unavailableCount++; processedCount++
                    if (!guard.publish { translationStatus = "Translation paused: " + (failure.message ?: "language model unavailable") }) return@LaunchedEffect
                    continue
                }
                if (result.value.isBlank()) unavailableCount++
                else if (result.value.trim() == source) unchangedCount++
                else {
                    val application = guard.await {
                        evaluateJavascriptAwait(view, WebTranslationScript.apply(index, result.value, original), guard)
                    } ?: return@LaunchedEffect
                    when (WebTranslationScript.applicationStatus(application.value)) {
                        "applied" -> if (!guard.publish { translatedCount++ }) return@LaunchedEffect
                        "unchanged" -> unchangedCount++
                        else -> unavailableCount++
                    }
                }
                processedCount++
                if (!guard.publish {
                    translationStatus = "Checking page text… $processedCount / $totalTranslatable · $translatedCount changed"
                }) return@LaunchedEffect
            }
            guard.publish {
                translationStatus = when {
                    unavailableCount > 0 -> "$translatedCount text blocks translated; $unchangedCount unchanged; $unavailableCount unavailable or changed before application."
                    translatedCount > 0 -> "Page translation applied to $translatedCount text blocks${if (unchangedCount > 0) "; $unchangedCount unchanged" else ""}."
                    unchangedCount > 0 -> "Page text checked; $unchangedCount blocks were already unchanged."
                    else -> "No translatable page text found."
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            guard.publish { translationStatus = "Web translation failed: " + (failure.message ?: "unknown error") }
        } finally {
            guard.publish { translating = false }
        }
    }

    if (speechSettings) androidx.compose.ui.window.Dialog(onDismissRequest = { speechSettings = false }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BrowserSourceCaptionControls(sourceCaptions, sourceCaptionState,
                    enabled = pageReady && !capturePending && !captureActive,
                    initialTargetLanguage = targetLanguage, onStart = { speechSettings = false })
                HorizontalDivider()
                Button(onClick = {
                    speechSettings = false
                    openCurrentVideo()
                }, enabled = pageReady && !capturePending && !captureActive) {
                    Text("Use source captions in Video")
                }
                Text("Open the current source in Video, then use Generate Full Subtitles to check its original captions. Captions are fetched and verified before they can be shown. This route does not require web audio capture or a speech model.",
                    style = MaterialTheme.typography.bodySmall)
                com.mangalens.ui.video.LiveAudioSubtitleSettings(speech, showEnableControl = false)
                if (captureStatus.isNotBlank()) Text(captureStatus)
                captureConsentStatus?.let { Text(it) }
                if (captureActive) {
                    Text(
                        "Captured audio: " + "%.1f".format(speechState.capturedAudioMs / 1000f) +
                            " s • processed windows: " + speechState.processedWindows,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (speechState.capturedAudioMs > 0) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                    )
                }
                Text("Android asks for audio and capture permission. Site/device capture restrictions may require Open in Video. Only MangaLens playback is captured; audio stays on your device.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = {
                    if (captureActive) {
                        context.stopService(android.content.Intent(context, WebAudioCaptureService::class.java)); speech.setEnabled(false)
                    } else if (android.os.Build.VERSION.SDK_INT >= 29) {
                        val owner = captureOwner()
                        if (owner != null && captureGate.begin(owner)) {
                            sourceCaptions.retire("Source captions stopped for explicit web audio capture.")
                            capturePending = true; captureConsentStatus = null
                            try { audioPermission.launch(android.Manifest.permission.RECORD_AUDIO) }
                            catch (_: Exception) {
                                captureGate.finish(null); capturePending = false
                                captureConsentStatus = "Android audio permission is unavailable. Try Open in Video."
                            }
                        }
                    }
                    speechSettings = false
                }, enabled = captureActive || (pageReady && !capturePending && speechState.ready && !speechState.busy && android.os.Build.VERSION.SDK_INT >= 29)) {
                    Text(if (captureActive) "Stop web capture" else "Start web audio capture")
                }
                if (android.os.Build.VERSION.SDK_INT < 29) Text("Web audio capture requires Android 10+. Use Open in Video on this device.")
                TextButton(onClick = { speechSettings = false }) { Text("Close") }
            }
        }
    }

    panel?.let { selected ->
        BrowserWorkspaceDialog(selected, workspace, currentUrl, pageTitle, pageReady,
            onDismiss = { panel = null }, onPanel = { panel = it },
            onNewTab = { session.submit { it.newTab() }; panel = null },
            onSelectTab = { id -> session.submit { it.selectTab(id) }; panel = null },
            onCloseTab = { id -> session.submit { it.closeTab(id) } },
            onOpenUrl = { target -> panel = null; loadPage(target) },
            onToggleBookmark = {
                val bookmarkUrl = currentUrl; val bookmarkTitle = pageTitle
                session.submit { it.toggleBookmark(bookmarkUrl, bookmarkTitle) }
            },
            onRemoveBookmark = { id -> session.submit { it.removeBookmark(id) } },
            onClearHistory = { session.submit { it.clearHistory() } },
            onFind = { panel = null; findOpen = true; hudVisible = true },
            onDomTools = { panel = null; showDomTools = true; hudVisible = true },
            onDesktop = { session.submit { it.setDesktop(tab.id, !tab.desktop) }; panel = null },
            onExternal = {
                panel = null
                if (com.mangalens.core.router.UrlEngineRouter.isSafeWebUrl(currentUrl)) {
                    try { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(currentUrl))) }
                    catch (_: Exception) { translationStatus = "No external browser is available." }
                }
            },
            onCleanReading = { panel = null; showCleanReading = true; hudVisible = true },
            onCopyLink = { panel = null; preparePublicLink(share = false); hudVisible = true },
            onShareLink = { panel = null; preparePublicLink(share = true); hudVisible = true },
            onResearch = onResearchQuestion?.let { { panel = null; showResearchQuestion = true; hudVisible = true } })
    }
    if (findOpen) BrowserFindDialog(findQuery, findSearching, findOrdinal, findCount, findError,
        onQuery = { query ->
            findQuery = query.take(256).filter { it.code >= 32 && it.code != 127 }
            findSerial++; findCount = -1; findError = null
            webView?.setFindListener(null); webView?.clearMatches()
        }, onFind = ::findOnPage,
        onNext = { forward -> if (owns(webView) && pageReady && findCount > 0) webView?.findNext(forward) },
        onDismiss = ::clearFind)

    val browserChromeVisible = hudVisible || pageLoad.loading || pageLoad.error != null || workspaceError != null || captureConsentStatus != null
    val browserTopInset by animateDpAsState(
        targetValue = if (browserChromeVisible) 108.dp else 0.dp,
        animationSpec = tween(220),
        label = "webTopInset"
    )

    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize().padding(top = browserTopInset),
            factory = { ctx ->
                createBrowserLifecycleWebView(ctx).apply {
                    captionWebViewToken = java.util.UUID.randomUUID().toString().replace("-", "")
                    com.mangalens.core.web.SafeWebView.configure(this)
                    mobileUserAgent = settings.userAgentString.orEmpty()
                    if (tab.desktop) {
                        settings.userAgentString = browserDesktopUserAgent(mobileUserAgent)
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                    }
                    settings.domStorageEnabled = true
                    observeBrowser("webview_created", this)
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, progress: Int) {
                            val ticket = callbackTicket(view, view?.url) ?: return
                            pageLoad = pageLoad.progressed(ticket, view?.url, progress)
                        }
                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            observeBrowser("received_title", view)
                            if (callbackTicket(view, view?.url) != null) pageTitle = title.orEmpty()
                        }

                        override fun onShowFileChooser(view: WebView?, callback: android.webkit.ValueCallback<Array<android.net.Uri>>?, params: FileChooserParams?): Boolean {
                            if (!owns(view)) { callback?.onReceiveValue(null); return true }
                            return uploadBridge.show(callback, params)
                        }

                        override fun onCreateWindow(
                            view: WebView?,
                            isDialog: Boolean,
                            isUserGesture: Boolean,
                            resultMsg: android.os.Message?
                        ): Boolean {
                            if (!owns(view)) return false
                            if (latestAdBlockEnabled && latestSiteAdBlockEnabled) {
                                translationStatus = "Blocked a popup window."
                                return false
                            }
                            return super.onCreateWindow(view, isDialog, isUserGesture, resultMsg)
                        }
                    }
                    setOnTouchListener { _, event ->
                        if (event.actionMasked == MotionEvent.ACTION_UP) hudVisible = true
                        false
                    }
                    webViewClient = object : AdBlockWebViewClient(engine, { latestAdBlockEnabled && siteAdBlockEnabled }) {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val blocked = super.shouldOverrideUrlLoading(view, request)
                            if (!owns(view) || awaitingDispatch) return true
                            observeBrowser("override_navigation", view, callbackUrl = request?.url?.toString(), detail = "blocked=$blocked")
                            if (!blocked && request?.isForMainFrame == true) {
                                val target = request.url.toString()
                                if (!pageLoad.pageReady || !WebPageLoadState.sameDocument(currentUrl, target))
                                    beginNavigation(target, replace = pageLoad.loading && request.isRedirect)
                            }
                            return blocked
                        }
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): android.webkit.WebResourceResponse? {
                            return com.mangalens.core.adblock.interceptBeforeMediaObservation({
                                super.shouldInterceptRequest(view, request)
                            }) {
                            if (!owns(view)) return@interceptBeforeMediaObservation
                            val mediaUrl = request?.url?.toString()
                            if (mediaUrl != null && VideoSourcePolicy.isLikelyMediaRequest(mediaUrl, request?.requestHeaders.orEmpty())) {
                                val allowed = setOf("accept", "accept-language", "cookie", "origin", "referer", "user-agent")
                                val headers = request?.requestHeaders.orEmpty()
                                    .filter { (name, value) -> name.lowercase() in allowed && value.length <= 16_384 }
                                val candidate = SniffedMedia(
                                    mediaUrl,
                                    headers,
                                    when {
                                        ".m3u8" in mediaUrl.lowercase() -> "HLS"
                                        ".mpd" in mediaUrl.lowercase() -> "DASH"
                                        else -> "WEB"
                                    },
                                    title = pageTitle.takeIf(String::isNotBlank),
                                    provider = runCatching { java.net.URI(mediaUrl).host?.removePrefix("www.") }.getOrNull()
                                )
                                val observedNavigation = pageLoad.navigation
                                view?.post {
                                    if (!owns(view) || pageLoad.navigation != observedNavigation) return@post
                                    if (observedNavigation == null || view.url?.let { WebPageLoadState.sameDocument(it, observedNavigation.url) } != true) return@post
                                    val current = detectedMedia
                                    if (
                                        current == null ||
                                        VideoSourcePolicy.mediaScore(candidate.url, candidate.headers) >=
                                            VideoSourcePolicy.mediaScore(current.url, current.headers)
                                    ) {
                                        detectedMedia = candidate
                                    }
                                }
                            }
                            }
                        }

                        override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: Bitmap?) {
                            if (!owns(view) || awaitingDispatch || pageLoad.phase == WebPageLoadPhase.STOPPED) return
                            observeBrowser("page_started", view, callbackUrl = pageUrl)
                            val target = pageUrl?.takeIf(com.mangalens.core.router.UrlEngineRouter::isSafeWebUrl) ?: return
                            val visibleUrl = view?.url
                            if (visibleUrl != null && !WebPageLoadState.sameDocument(visibleUrl, target)) return
                            val ticket = pageLoad.navigation
                            if (ticket == null || !WebPageLoadState.sameDocument(ticket.url, target) || pageLoad.pageReady)
                                beginNavigation(target, replace = pageLoad.loading)
                            if (!pageLoad.loading) return
                            super.onPageStarted(view, pageUrl, favicon)
                            currentUrl = target
                        }
                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            observeBrowser("received_error", view, callbackUrl = request?.url?.toString(),
                                detail = "main_frame=${request?.isForMainFrame};code=${error?.errorCode}")
                            if (request?.isForMainFrame != true) return
                            val ticket = callbackTicket(view, request.url.toString()) ?: return
                            pageLoad = pageLoad.failed(ticket, request.url.toString(), true, "Page could not load. Check your connection and retry.")
                            captureGate.revoke(); captureAuthority.set(null)
                            translating = false
                            autoScroll = false
                            hudVisible = true
                            observeBrowser("error_applied", view, callbackUrl = request.url.toString())
                        }
                        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: android.webkit.WebResourceResponse?) {
                            observeBrowser("received_http_error", view, callbackUrl = request?.url?.toString(),
                                detail = "main_frame=${request?.isForMainFrame};status=${response?.statusCode}")
                            if (request?.isForMainFrame != true) return
                            val ticket = callbackTicket(view, request.url.toString()) ?: return
                            pageLoad = pageLoad.failed(ticket, request.url.toString(), true, "Website returned HTTP ${response?.statusCode ?: "error"}. Retry or open the source.")
                            captureGate.revoke(); captureAuthority.set(null)
                            translating = false
                            autoScroll = false
                            hudVisible = true
                        }
                        override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                            observeBrowser("received_ssl_error", view, callbackUrl = error?.url)
                            handler?.cancel()
                            val failedUrl = error?.url ?: view?.url
                            val ticket = callbackTicket(view, failedUrl) ?: return
                            pageLoad = pageLoad.failed(ticket, failedUrl, true, "Secure connection failed. Check the source and retry.")
                            captureGate.revoke(); captureAuthority.set(null)
                            translating = false
                            autoScroll = false
                            hudVisible = true
                        }
                        override fun onPageFinished(view: WebView?, pageUrl: String?) {
                            observeBrowser("page_finished", view, callbackUrl = pageUrl)
                            val ticket = callbackTicket(view, pageUrl) ?: return
                            val wasLoading = pageLoad.loading
                            pageLoad = pageLoad.finished(ticket, pageUrl)
                            currentUrl = pageUrl ?: currentUrl
                            observeBrowser("finish_applied", view, callbackUrl = pageUrl)
                            if (pageLoad.readyFor(ticket)) {
                                captureAuthority.set(BrowserUploadScope(tab.id, ticket.epoch, ticket.url))
                                super.onPageFinished(view, pageUrl)
                                if (wasLoading) pageUrl?.takeIf(com.mangalens.core.router.UrlEngineRouter::isSafeWebUrl)?.let { committedUrl ->
                                    val committedTitle = view?.title.orEmpty()
                                    scope.launch {
                                        try {
                                            val saved = session.submit { it.commit(tab.id, ticket.url, committedUrl, committedTitle) }.await()
                                            if (callbackTicket(view, committedUrl) == ticket && pageLoad.readyFor(ticket) &&
                                                saved.activeTabId == tab.id && saved.activeTab.url == committedUrl)
                                                onPageChanged(committedUrl)
                                        } catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) {
                                            if (callbackTicket(view, committedUrl) == ticket && pageLoad.readyFor(ticket))
                                                translationStatus = "Visited page could not be saved. Retry when storage is available."
                                        }
                                    }
                                }
                            }
                        }
                        override fun onPageCommitVisible(view: WebView?, url: String?) {
                            if (!owns(view)) return
                            super.onPageCommitVisible(view, url)
                            observeBrowser("page_commit_visible", view, callbackUrl = url)
                        }
                    }
                    webView = this
                    (webViewClient as? AdBlockWebViewClient)?.prepareForNavigation(this)
                }
            },
            onRelease = { view -> browserLifecycleViewEvent(BrowserLifecyclePhase.VIEW_RELEASE, view) },
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
            Column {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Column(Modifier.weight(1f).clickable { address = currentUrl; showAddress = true }) {
                    Text(pageTitle.ifBlank { "Web" }, maxLines = 1, style = MaterialTheme.typography.titleSmall)
                    Text(
                        runCatching { java.net.URI(currentUrl).host?.removePrefix("www.") }.getOrNull().orEmpty(),
                        maxLines = 1,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(onClick = { if (canGoBack) navigateHistory(-1) else onClose?.invoke() }, enabled = canGoBack || onClose != null, contentPadding = PaddingValues(horizontal = 7.dp)) { Text("‹") }
                TextButton(onClick = { navigateHistory(1) }, enabled = canGoForward, contentPadding = PaddingValues(horizontal = 7.dp)) { Text("›") }
                TextButton(
                    onClick = {
                        if (pageLoad.loading) {
                            commandSerial++
                            awaitingDispatch = false
                            captureGate.revoke(); captureAuthority.set(null)
                            uploadBridge.cancel(); clearFind()
                            pageLoad.navigation?.let { pageLoad = pageLoad.stopped(it) }
                            webView?.stopLoading()
                        } else reloadPage()
                    },
                    contentPadding = PaddingValues(horizontal = 7.dp)
                ) { Text(if (pageLoad.loading) "■" else "↻") }
                if (detectedMedia != null || VideoSourcePolicy.isSourcePage(currentUrl)) {
                    TextButton(
                        onClick = { openCurrentVideo() },
                        enabled = pageReady,
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) { Text("Video") }
                }
                TextButton(
                    onClick = {
                        siteAdBlockEnabled = !siteAdBlockEnabled
                        reloadPage()
                    },
                    enabled = adBlockEnabled,
                    contentPadding = PaddingValues(horizontal = 7.dp)
                ) { Text(if (adBlockEnabled && siteAdBlockEnabled) "Ads ✓" else "Ads") }
                TextButton(onClick = { address = currentUrl; showAddress = true }, contentPadding = PaddingValues(horizontal = 7.dp)) { Text("URL") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { panel = BrowserWorkspacePanel.TABS; hudVisible = true }, modifier = Modifier.semantics {
                    contentDescription = "Browser tabs"
                }) { Text("Tabs (${workspace.tabs.size})") }
                TextButton(onClick = { panel = BrowserWorkspacePanel.TOOLS; hudVisible = true }, modifier = Modifier.semantics {
                    contentDescription = "Browser tools"
                }) { Text("Tools") }
                Spacer(Modifier.weight(1f))
                Text(if (tab.desktop) "Desktop" else "Mobile", style = MaterialTheme.typography.labelSmall)
            }
            if (pageLoad.loading) LinearProgressIndicator(progress = loadProgress / 100f, modifier = Modifier.fillMaxWidth())
            }
        }
        }
        BrowserSourceCaptionOverlay(sourceCaptionState.cueText,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, bottom = if (hudVisible) 180.dp else 24.dp))
        if (sourceCaptionState.requested && sourceCaptionState.cueText.isNullOrBlank()) {
            Surface(Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, bottom = if (hudVisible) 180.dp else 24.dp)
                .semantics { contentDescription = "Browser source-caption status" },
                shape = RoundedCornerShape(10.dp), color = Color.Black.copy(alpha = .8f)) {
                Text(sourceCaptionState.message, modifier = Modifier.padding(10.dp),
                    color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (captureActive) com.mangalens.ui.video.LiveAudioSubtitleOverlay(speech,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (hudVisible) 125.dp else 24.dp, start = 16.dp, end = 16.dp))
        if (hudVisible || translating || pageLoad.error != null || workspaceError != null || captureConsentStatus != null) {
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
                            label = if (translating) "Translating…" else if (translated && translatedCount > 0) "Translated" else if (translated) "Translate on" else "Translate",
                            status = if (translated) targetLanguage.uppercase() else "OCR / text",
                            selected = translated,
                            enabled = pageReady,
                            onClick = {
                                translated = !translated
                                hudVisible = true
                            }
                        )
                        com.mangalens.ui.video.VideoDockAction(
                            label = "Open in Video",
                            status = when {
                                detectedMedia != null -> "Media detected"
                                VideoSourcePolicy.isSourcePage(currentUrl) -> "Resolve best source"
                                else -> "Native player"
                            },
                            enabled = pageReady,
                            onClick = { openCurrentVideo() }
                        )
                        com.mangalens.ui.video.VideoDockAction(
                            label = "Download",
                            status = "Resolve best media",
                            enabled = pageReady,
                            onClick = {
                                val ownerView = webView
                                val ownerTicket = callbackTicket(ownerView, currentUrl)
                                val sourcePage = currentUrl
                                val capturedTitle = pageTitle.takeIf(String::isNotBlank)
                                val capturedMedia = detectedMedia?.let(::enrichSniffedMedia)
                                fun downloadStatus(message: String) {
                                    if (ownerTicket != null && callbackTicket(ownerView, sourcePage) == ownerTicket && pageLoad.readyFor(ownerTicket))
                                        translationStatus = message
                                }
                                if (ownerTicket != null && pageLoad.readyFor(ownerTicket)) {
                                scope.launch {
                                    downloadStatus("Resolving the best accessible media source…")
                                    val manager = MediaDownloadManager(context)
                                    try {
                                        manager.enqueue(
                                            sourcePage,
                                            title = capturedTitle,
                                            quality = com.mangalens.download.DownloadQuality.BEST,
                                            sourcePageUrl = sourcePage
                                        )
                                        downloadStatus("Download queued from " +
                                            (runCatching { java.net.URI(sourcePage).host?.removePrefix("www.") }.getOrNull() ?: "source"))
                                    } catch (pageFailure: Throwable) {
                                        if (pageFailure is kotlinx.coroutines.CancellationException) throw pageFailure
                                        val media = capturedMedia
                                        if (media == null) {
                                            downloadStatus("Download failed: " +
                                                (pageFailure.message ?: "unable to resolve media"))
                                        } else {
                                            downloadStatus("Page extractor unavailable • using detected video stream…")
                                            if (isYoutubePage(sourcePage)) {
                                                downloadStatus("YouTube source refresh failed. Reload the video page, play it briefly, then retry.")
                                            } else {
                                                val enriched = media
                                                try {
                                                    manager.enqueueResolvedMedia(
                                                        url = enriched.url,
                                                        title = capturedTitle,
                                                        mimeType = sniffedMime(enriched),
                                                        quality = DownloadQuality.BEST,
                                                        sourcePageUrl = sourcePage,
                                                        headers = enriched.headers,
                                                        provider = enriched.provider ?: "web-sniff"
                                                    )
                                                    downloadStatus("Detected media queued with the current website session.")
                                                } catch (cancelled: CancellationException) { throw cancelled }
                                                catch (failure: Exception) {
                                                    downloadStatus("Download failed after page + stream fallback: " +
                                                        (failure.message ?: pageFailure.message ?: "unavailable media"))
                                                }
                                            }
                                        }
                                    }
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
                    val loadError = pageLoad.error
                    if (workspaceError != null) Text(workspaceError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    captureConsentStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (loadError != null) {
                        Text(loadError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        translationStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { reloadPage() }) { Text("Retry") }
                            TextButton(onClick = {
                                if (com.mangalens.core.router.UrlEngineRouter.isSafeWebUrl(currentUrl)) runCatching {
                                    context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(currentUrl))
                                        .addCategory(android.content.Intent.CATEGORY_BROWSABLE))
                                }.onFailure { translationStatus = "No browser is available to open this source." }
                            }) { Text("Open source") }
                            TextButton(onClick = { if (canGoBack) navigateHistory(-1) else onClose?.invoke() }, enabled = canGoBack || onClose != null) { Text("Back") }
                        }
                    } else translationStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (translating) LinearProgressIndicator(
                        progress = if (totalTranslatable > 0) translatedCount.toFloat() / totalTranslatable else 0f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

private suspend fun evaluateJavascriptAwait(view: WebView, script: String, guard: BrowserPageMutationGuard): String? =
    suspendCancellableCoroutine { continuation ->
        view.post {
            if (!continuation.isActive) return@post
            val dispatched = guard.publish {
                view.evaluateJavascript(script) { result ->
                    if (continuation.isActive) continuation.resume(result)
                }
            }
            if (!dispatched && continuation.isActive) continuation.resume(null)
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


private fun isYoutubePage(value: String): Boolean = runCatching {
    val host = java.net.URI(value).host.orEmpty().lowercase()
    host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
}.getOrDefault(false)

private fun sniffedMime(media: SniffedMedia): String = when (media.kind.uppercase()) {
    "HLS" -> "application/x-mpegURL"
    "DASH" -> "application/dash+xml"
    "MPEG-TS" -> "video/mp2t"
    else -> "video/mp4"
}

private const val MAX_WEB_TEXT_NODES = 160
