package com.mangalens.acquisition

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.mangalens.core.adblock.AdBlockWebViewClient
import com.mangalens.core.acquisition.ChapterImageCandidate
import com.mangalens.core.acquisition.ChapterImageCandidates
import com.mangalens.core.acquisition.ChapterImageExtractionScript
import com.mangalens.core.adblock.AdBlockEngine
import com.mangalens.core.acquisition.LazyChapterObservation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

data class RenderedPageSet(
    val finalUrl: String,
    val imageUrls: List<String>,
    val videoStreamUrls: List<String>,
    val resourceImageUrls: List<String>,
    val observations: Int,
    val mainFrameHttpStatus: Int,
    val navigationError: String?,
    val imageCandidates: List<ChapterImageCandidate> = imageUrls.map { ChapterImageCandidate(it) },
    val discoveryLimited: Boolean = false
)

/**
 * Thread-safe WebView extraction engine for discovering image chapters and video streams.
 */
class RenderedBrowserAcquirer(
    private val context: Context,
    private val adBlockEngine: AdBlockEngine = AdBlockEngine(),
    private val adBlockEnabled: () -> Boolean = { true }
) {
    private companion object {
        const val MAX_IMAGE_URLS = 3000
        const val MAX_VIDEO_URLS = 500
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun discoverWithCookie(
        url: String,
        timeoutMs: Long,
        cookie: String?,
        isCurrent: () -> Boolean = { true }
    ): RenderedPageSet = withContext(Dispatchers.Main) {
        val acquisitionScope = CoroutineScope(currentCoroutineContext())
        suspendCancellableCoroutine { continuation ->
            val finished = AtomicBoolean(false)
            val networkImages = ConcurrentHashMap.newKeySet<String>()
            val networkVideos = ConcurrentHashMap.newKeySet<String>()
            var observation = LazyChapterObservation()
            var scheduledGeneration = -1L
            var navigationGeneration = 0L
            val observationGuard = Any()
            var currentDocument: String? = null
            val retiredDocuments = LinkedHashSet<String>()
            val handler = Handler(Looper.getMainLooper())

            val webView = WebView(context.applicationContext).apply {
                com.mangalens.core.web.SafeWebView.configure(this)
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = true
                // Headless extraction still needs a real positive layout viewport for lazy scrolling.
                val metrics = context.resources.displayMetrics
                val width = metrics.widthPixels.coerceIn(320, 1536)
                val height = metrics.heightPixels.coerceIn(480, 2048)
                measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY),
                    android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY))
                layout(0, 0, width, height)
            }

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, false)


            fun closeAcquisition() {
                handler.removeCallbacksAndMessages(null)
                (webView.webViewClient as? AdBlockWebViewClient)?.clearScriptRegistration()
                webView.stopLoading()
                webView.destroy()
            }
            // Caller selection predicates are consulted only on Main, never request IO.
            fun ownerActive(): Boolean {
                if (finished.get() || !continuation.isActive) return false
                if (!isCurrent()) {
                    continuation.cancel(CancellationException("The chapter acquisition selection changed."))
                    return false
                }
                return true
            }
            fun finishAcquisition(result: RenderedPageSet) {
                if (ownerActive() && finished.compareAndSet(false, true)) {
                    closeAcquisition()
                    if (continuation.isActive) continuation.resume(result)
                }
            }
            fun result(document: String, limited: Boolean, error: String? = null) = RenderedPageSet(
                finalUrl = document,
                imageUrls = observation.images.map { it.url },
                videoStreamUrls = networkVideos.toList(), resourceImageUrls = networkImages.toList(),
                observations = observation.observations, mainFrameHttpStatus = 200,
                navigationError = error, imageCandidates = observation.images, discoveryLimited = limited)

            fun observe(document: String, generation: Long) {
                if (!ownerActive() || navigationGeneration != generation || webView.url != document) return
                webView.evaluateJavascript(ChapterImageExtractionScript.extract) { rawResult ->
                    if (!ownerActive() || navigationGeneration != generation || webView.url != document) return@evaluateJavascript
                    // The bounded JSON catalogue is decoded off Main in a child of this acquisition.
                    acquisitionScope.launch {
                        val decoded = withContext(Dispatchers.Default) {
                            val raw = rawResult.orEmpty()
                            ChapterImageCandidates.decodeObservation(raw, document)
                        }
                        if (!ownerActive() || navigationGeneration != generation || webView.url != document) return@launch
                        synchronized(observationGuard) {
                            decoded.videos.filter(::isVideoCandidate)
                                .take((MAX_VIDEO_URLS - networkVideos.size).coerceAtLeast(0)).forEach(networkVideos::add)
                        }
                        val decision = observation.observe(decoded.images, decoded.viewport, networkVideos.size, decoded.limited)
                        if (decision.stop) {
                            val message = if (observation.images.isEmpty() && networkVideos.isEmpty())
                                "Acquisition observation limit: No valid media streams found"
                            else if (decision.limited) "Chapter discovery reached its bounded observation limit. The source may contain more pages."
                            else null
                            finishAcquisition(result(document, decision.limited, message))
                        } else {
                            // Walk the viewport rather than jump past lazy-loading intermediate nodes.
                            webView.evaluateJavascript("if(window.innerHeight>0)window.scrollBy(0,Math.max(1,Math.floor(window.innerHeight*0.85)));", null)
                            handler.postDelayed({ observe(document, generation) }, LazyChapterObservation.WAIT_MS)
                        }
                    }
                }
            }

            handler.postDelayed({
                if (!ownerActive()) return@postDelayed
                val noMedia = observation.images.isEmpty() && networkVideos.isEmpty()
                finishAcquisition(result(webView.url ?: url, true, if (noMedia)
                    "Acquisition timeout: No valid media streams found"
                else "Chapter discovery reached its time limit. Acquired candidates are retained; the source may contain more pages."))
            }, timeoutMs.coerceIn(5_000L, 20_000L))

            continuation.invokeOnCancellation {
                // Retire before queuing Main cleanup so request callbacks cannot add late evidence.
                if (finished.compareAndSet(false, true)) handler.post { closeAcquisition() }
            }

            webView.webViewClient = object : AdBlockWebViewClient(adBlockEngine, adBlockEnabled) {

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)

                    val captured = synchronized(observationGuard) { navigationGeneration to currentDocument }
                    val blocked = super.shouldInterceptRequest(view, request)
                    if (blocked != null || finished.get()) return blocked
                    if (reqUrl.length > ChapterImageCandidates.MAX_URL_CHARS) return null
                    val cleanUrl = reqUrl.substringBefore("#")
                    val referer = request.requestHeaders.entries.firstOrNull { it.key.equals("Referer", true) }?.value?.let {
                        ChapterImageCandidates.normalizedUrl(it, captured.second ?: url)
                    }
                    synchronized(observationGuard) {
                        if (finished.get() || captured.first != navigationGeneration || captured.second != currentDocument)
                            return null
                        if (referer != null && referer != currentDocument && referer in retiredDocuments) return null
                        // Unknown frame/origin-only Referer observations remain candidates, not source or playback proof.
                        if (isVideoCandidate(cleanUrl)) {
                            if (networkVideos.size < MAX_VIDEO_URLS) networkVideos.add(cleanUrl)
                        } else if (isImageCandidate(cleanUrl)) {
                            if (networkImages.size < MAX_IMAGE_URLS) networkImages.add(cleanUrl)
                        }
                    }
                    return null
                }

                override fun onPageStarted(view: WebView?, loadedUrl: String?, favicon: android.graphics.Bitmap?) {
                    if (finished.get()) return
                    synchronized(observationGuard) {
                        currentDocument?.let { retiredDocuments.add(it) }
                        currentDocument = loadedUrl?.let { ChapterImageCandidates.normalizedUrl(it, it) }
                        currentDocument?.let { retiredDocuments.remove(it) }
                        while (retiredDocuments.size > 64) retiredDocuments.remove(retiredDocuments.first())
                        navigationGeneration++
                        networkImages.clear()
                        networkVideos.clear()
                    }
                    observation = LazyChapterObservation()
                    scheduledGeneration = -1L
                    super.onPageStarted(view, loadedUrl, favicon)
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: android.webkit.RenderProcessGoneDetail
                ): Boolean {
                    if (finished.compareAndSet(false, true)) {
                        handler.removeCallbacksAndMessages(null)
                        clearScriptRegistration()
                        view?.stopLoading()
                        view?.destroy()
                        if (continuation.isActive) continuation.resume(
                            RenderedPageSet(
                                finalUrl = url,
                                imageUrls = observation.images.map { it.url },
                                videoStreamUrls = networkVideos.toList(),
                                resourceImageUrls = networkImages.toList(),
                                observations = observation.observations,
                                mainFrameHttpStatus = 0,
                                navigationError = if (detail.didCrash()) {
                                    "WebView renderer crashed during acquisition."
                                } else {
                                    "WebView renderer was reclaimed because of memory pressure."
                                },
                                imageCandidates = observation.images,
                                discoveryLimited = true
                            )
                        )
                    }
                    return true
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)
                    if (!ownerActive() || loadedUrl == null || loadedUrl != webView.url || scheduledGeneration == navigationGeneration) return
                    scheduledGeneration = navigationGeneration
                    val capturedGeneration = navigationGeneration
                    handler.postDelayed({ observe(loadedUrl, capturedGeneration) }, LazyChapterObservation.INITIAL_WAIT_MS)
                }
            }
            (webView.webViewClient as AdBlockWebViewClient).prepareForNavigation(webView)

            webView.loadUrl(url)
        }
    }

    private fun isImageCandidate(url: String): Boolean {
        val lower = url.lowercase()
        if (listOf(".js", ".css", ".html", ".json", ".xml", ".woff", ".svg").any { lower.endsWith(it) }) {
            return false
        }
        if (listOf(
                "google.com/recaptcha",
                "gstatic.com/recaptcha",
                "hcaptcha.com/",
                "challenges.cloudflare.com/",
                "/cdn-cgi/challenge-platform/",
                "cf-chl-",
                "/captcha/",
                "captcha.php"
            ).any { lower.contains(it) }
        ) return false
        return listOf(".jpg", ".jpeg", ".png", ".webp", ".avif", "chapter", "page", "upload", "manga", "cdn")
            .any { lower.contains(it) }
    }

    private fun isVideoCandidate(url: String): Boolean {
        val lower = url.lowercase()
        return listOf(
            ".m3u8", ".mpd", ".mp4", ".m4v", ".webm", ".mkv", ".mov", ".ts",
            "/hls/", "/dash/", "/manifest/"
        ).any { lower.contains(it) }
    }

}
