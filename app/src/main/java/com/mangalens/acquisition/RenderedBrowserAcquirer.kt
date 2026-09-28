package com.mangalens.acquisition

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mangalens.core.adblock.AdBlockWebViewClient
import com.mangalens.core.adblock.AdBlockEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
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
    val navigationError: String?
)

/**
 * Thread-safe WebView extraction engine for discovering image chapters and video streams.
 */
class RenderedBrowserAcquirer(
    private val context: Context,
    private val adBlockEngine: AdBlockEngine = AdBlockEngine()
) {

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
            val discoveredDomUrls = LinkedHashSet<String>()
            val handler = Handler(Looper.getMainLooper())

            val webView = WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            }

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)

            if (!cookie.isNullOrBlank()) {
                cookieManager.setCookie(url, cookie)
                cookieManager.flush()
            }

            fun finishAcquisition(result: RenderedPageSet) {
                if (finished.compareAndSet(false, true)) {
                    handler.removeCallbacksAndMessages(null)
                    webView.stopLoading()
                    webView.destroy()
                    continuation.resume(result)
                }
            }

            handler.postDelayed({
                val fallbackImages = if (discoveredDomUrls.isNotEmpty()) {
                    discoveredDomUrls.toList()
                } else {
                    networkImages.filter { isImageCandidate(it) }
                }

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
                        } else null
                    )
                )
            }, timeoutMs.coerceIn(5_000L, 20_000L))

            continuation.invokeOnCancellation {
                if (finished.compareAndSet(false, true)) {
                    handler.removeCallbacksAndMessages(null)
                    webView.stopLoading()
                    webView.destroy()
                }
            }

            webView.webViewClient = object : AdBlockWebViewClient(adBlockEngine) {

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?
                ): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)

                    val cleanUrl = reqUrl.substringBefore("#")
                    if (isVideoCandidate(cleanUrl)) {
                        if (networkVideos.size < MAX_VIDEO_URLS) networkVideos.add(cleanUrl)
                    } else if (isImageCandidate(cleanUrl)) {
                        if (networkImages.size < MAX_IMAGE_URLS) networkImages.add(cleanUrl)
                    }

                    return super.shouldInterceptRequest(view, request)
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: android.webkit.RenderProcessGoneDetail
                ): Boolean {
                    if (finished.compareAndSet(false, true)) {
                        handler.removeCallbacksAndMessages(null)
                        view?.stopLoading()
                        view?.destroy()
                        continuation.resume(
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
                    val extractionJs = """
                        (function() {
                            window.scrollTo(0, document.body.scrollHeight);

                            let urls = [];
                            let seen = new Set();

                            function addUrl(src) {
                                if (!src || src.startsWith('data:')) return;
                                try {
                                    let resolved = new URL(src, location.href).href.split('#')[0];
                                    if (!seen.has(resolved)) {
                                        seen.add(resolved);
                                        urls.push(resolved);
                                    }
                                } catch(e) {}
                            }

                            document.querySelectorAll('img, source, video, [data-src], [data-original], [data-lazy-src]').forEach(el => {
                                addUrl(el.src);
                                addUrl(el.currentSrc);
                                addUrl(el.getAttribute('data-src'));
                                addUrl(el.getAttribute('data-original'));
                                addUrl(el.getAttribute('data-lazy-src'));
                            });

                            return encodeURIComponent(JSON.stringify(urls));
                        })();
                    """.trimIndent()

                    handler.postDelayed({
                        webView.evaluateJavascript(extractionJs) { rawResult ->
                            if (finished.get()) return@evaluateJavascript

                            val decoded = try {
                                URLDecoder.decode(rawResult ?: "", StandardCharsets.UTF_8.name())
                                    .trim('"')
                                    .replace("\\\"", "\"")
                            } catch (e: Exception) {
                                ""
                            }

                            val parsedUrls = parseJsonArray(decoded)
                            for (candidate in parsedUrls) {
                                if (discoveredDomUrls.size >= MAX_IMAGE_URLS) break
                                if (isImageCandidate(candidate)) discoveredDomUrls.add(candidate)
                            }

                            val finalImages = if (discoveredDomUrls.isNotEmpty()) {
                                discoveredDomUrls.toList()
                            } else {
                                networkImages.filter { isImageCandidate(it) }
                            }

                            if (finalImages.isNotEmpty() || networkVideos.isNotEmpty()) {
                                finishAcquisition(
                                    RenderedPageSet(
                                        finalUrl = loadedUrl ?: url,
                                        imageUrls = finalImages,
                                        videoStreamUrls = networkVideos.toList(),
                                        resourceImageUrls = networkImages.toList(),
                                        observations = 1,
                                        mainFrameHttpStatus = 200,
                                        navigationError = null
                                    )
                                )
                            }
                        }
                    }, 2500L)
                }
            }

            webView.loadUrl(url)
        }
    }

    private fun isImageCandidate(url: String): Boolean {
        val lower = url.lowercase()
        if (listOf(".js", ".css", ".html", ".json", ".xml", ".woff", ".svg").any { lower.endsWith(it) }) {
            return false
        }
        return listOf(".jpg", ".jpeg", ".png", ".webp", ".avif", "chapter", "page", "upload", "manga", "cdn")
            .any { lower.contains(it) }
    }

    private fun isVideoCandidate(url: String): Boolean {
        val lower = url.lowercase()
        return listOf(".m3u8", ".mp4", ".ts", "/hls/", "/manifest/").any { lower.contains(it) }
    }

    private fun parseJsonArray(json: String): List<String> {
        return try {
            json.replace("[", "")
                .replace("]", "")
                .replace("\"", "")
                .split(",")
                .map { it.trim().replace("\\/", "/") }
                .filter { it.startsWith("http") }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
