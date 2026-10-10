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
    val imageCandidates: List<ChapterImageCandidate> = imageUrls.map { ChapterImageCandidate(it) }
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
        cookie: String?
    ): RenderedPageSet = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val finished = AtomicBoolean(false)
            val networkImages = ConcurrentHashMap.newKeySet<String>()
            val networkVideos = ConcurrentHashMap.newKeySet<String>()
            var discoveredImages = emptyList<ChapterImageCandidate>()
            var navigationGeneration = 0L
            val observationGuard = Any()
            var currentDocument: String? = null
            val retiredDocuments = LinkedHashSet<String>()
            val handler = Handler(Looper.getMainLooper())

            val webView = WebView(context.applicationContext).apply {
                com.mangalens.core.web.SafeWebView.configure(this)
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = true

            }

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, false)


            fun finishAcquisition(result: RenderedPageSet) {
                if (finished.compareAndSet(false, true)) {
                    handler.removeCallbacksAndMessages(null)
                    (webView.webViewClient as? AdBlockWebViewClient)?.clearScriptRegistration()
                    webView.stopLoading()
                    webView.destroy()
                    if (continuation.isActive) continuation.resume(result)
                }
            }

            handler.postDelayed({
                // Network scheduling does not prove node identity, chapter membership or reading order.
                val fallbackImages = discoveredImages.map { it.url }

                finishAcquisition(
                    RenderedPageSet(
                        finalUrl = webView.url ?: url,
                        imageUrls = fallbackImages,
                        videoStreamUrls = networkVideos.toList(),
                        resourceImageUrls = networkImages.toList(),
                        observations = 1,
                        mainFrameHttpStatus = 200,
                        navigationError = if (fallbackImages.isEmpty() && networkVideos.isEmpty()) {
                            "Acquisition timeout: No valid media streams found"
                        } else null,
                        imageCandidates = discoveredImages
                    )
                )
            }, timeoutMs.coerceIn(5_000L, 20_000L))

            continuation.invokeOnCancellation { handler.post {
                if (finished.compareAndSet(false, true)) {
                    handler.removeCallbacksAndMessages(null)
                    (webView.webViewClient as? AdBlockWebViewClient)?.clearScriptRegistration()
                    webView.stopLoading()
                    webView.destroy()
                }
            } }

            webView.webViewClient = object : AdBlockWebViewClient(adBlockEngine, adBlockEnabled) {

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)

                    val captured = synchronized(observationGuard) { navigationGeneration to currentDocument }
                    val blocked = super.shouldInterceptRequest(view, request)
                    if (blocked != null || finished.get()) return blocked
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
                    discoveredImages = emptyList()
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
                                imageUrls = emptyList(),
                                videoStreamUrls = emptyList(),
                                resourceImageUrls = emptyList(),
                                observations = 0,
                                mainFrameHttpStatus = 0,
                                navigationError = if (detail.didCrash()) {
                                    "WebView renderer crashed during acquisition."
                                } else {
                                    "WebView renderer was reclaimed because of memory pressure."
                                }
                            )
                        )
                    }
                    return true
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)
                    if (finished.get() || loadedUrl == null || loadedUrl != webView.url) return
                    val capturedGeneration = navigationGeneration
                    // Trigger lazy image loading before the bounded observation, not after it.
                    webView.evaluateJavascript("window.scrollTo(0,document.body?document.body.scrollHeight:0);", null)
                    handler.postDelayed({
                        if (finished.get() || navigationGeneration != capturedGeneration || webView.url != loadedUrl) return@postDelayed
                        webView.evaluateJavascript(ChapterImageExtractionScript.extract) { rawResult ->
                            if (finished.get() || navigationGeneration != capturedGeneration || webView.url != loadedUrl) return@evaluateJavascript
                            discoveredImages = ChapterImageCandidates.decode(rawResult.orEmpty(), loadedUrl)
                            synchronized(observationGuard) {
                                ChapterImageCandidates.decodeVideos(rawResult.orEmpty(), loadedUrl).filter(::isVideoCandidate)
                                    .take((MAX_VIDEO_URLS - networkVideos.size).coerceAtLeast(0)).forEach(networkVideos::add)
                            }
                            if (discoveredImages.isNotEmpty() || networkVideos.isNotEmpty()) {
                                finishAcquisition(RenderedPageSet(
                                    finalUrl = loadedUrl, imageUrls = discoveredImages.map { it.url },
                                    videoStreamUrls = networkVideos.toList(), resourceImageUrls = networkImages.toList(),
                                    observations = 1, mainFrameHttpStatus = 200, navigationError = null,
                                    imageCandidates = discoveredImages))
                            }
                        }
                    }, 2500L)
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
