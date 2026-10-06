package com.mangalens.acquisition

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import com.mangalens.core.adblock.AdBlockEngine
import com.mangalens.core.adblock.AdBlockWebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONTokener
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
    val cookieHeader: String? = null,
    val userAgent: String? = null
)

/**
 * Thread-safe WebView extraction engine for discovering chapter images and video streams.
 *
 * Chapter extraction intentionally favours images that belong to the reader itself. Logos,
 * avatars, banners, social promos and other page chrome are filtered before the URLs reach the
 * native reader. Several scroll/collect passes are used so lazy-loaded webtoon pages can appear.
 */
class RenderedBrowserAcquirer(
    private val context: Context,
    private val adBlockEngine: AdBlockEngine = AdBlockEngine()
) {
    private companion object {
        const val MAX_IMAGE_URLS = 3000
        const val MAX_VIDEO_URLS = 500
        const val COLLECTION_PASSES = 6
        const val COLLECTION_DELAY_MS = 450L
        const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36 MangaLens/13"
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun discoverWithCookie(
        url: String,
        timeoutMs: Long,
        cookie: String?,
        userAgent: String? = null
    ): RenderedPageSet = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val finished = AtomicBoolean(false)
            val networkImages = ConcurrentHashMap.newKeySet<String>()
            val networkVideos = ConcurrentHashMap.newKeySet<String>()
            val handler = Handler(Looper.getMainLooper())
            var observations = 0
            var mainFrameStatus = 200
            var navigationError: String? = null

            val webView = WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.loadsImagesAutomatically = true
                settings.userAgentString = userAgent?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_AGENT
            }

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)
            if (!cookie.isNullOrBlank()) {
                cookieManager.setCookie(url, cookie)
                cookieManager.flush()
            }

            fun currentCookies(target: String): String? =
                runCatching { cookieManager.getCookie(target) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }

            fun finishAcquisition(
                finalUrl: String = webView.url ?: url,
                images: List<String> = emptyList(),
                videos: List<String> = networkVideos.toList(),
                error: String? = navigationError
            ) {
                if (!finished.compareAndSet(false, true)) return
                val finalCookie = currentCookies(finalUrl) ?: currentCookies(url)
                val finalUserAgent = webView.settings.userAgentString
                handler.removeCallbacksAndMessages(null)
                webView.stopLoading()
                webView.destroy()
                continuation.resume(
                    RenderedPageSet(
                        finalUrl = finalUrl,
                        imageUrls = images.distinct().take(MAX_IMAGE_URLS),
                        videoStreamUrls = videos.distinct().take(MAX_VIDEO_URLS),
                        resourceImageUrls = networkImages.filter(::isImageCandidate).take(MAX_IMAGE_URLS),
                        observations = observations,
                        mainFrameHttpStatus = mainFrameStatus,
                        navigationError = error,
                        cookieHeader = finalCookie,
                        userAgent = finalUserAgent
                    )
                )
            }

            handler.postDelayed({
                if (finished.get()) return@postDelayed
                val fallback = networkImages.filter(::isImageCandidate).take(MAX_IMAGE_URLS)
                finishAcquisition(
                    images = fallback,
                    error = navigationError ?: if (fallback.isEmpty() && networkVideos.isEmpty()) {
                        "Acquisition timeout: no reader images or playable media were discovered."
                    } else {
                        null
                    }
                )
            }, timeoutMs.coerceIn(6_000L, 25_000L))

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
                    val reqUrl = request?.url?.toString()
                        ?: return super.shouldInterceptRequest(view, request)
                    val cleanUrl = reqUrl.substringBefore("#")
                    when {
                        isVideoCandidate(cleanUrl) && networkVideos.size < MAX_VIDEO_URLS ->
                            networkVideos.add(cleanUrl)
                        isImageCandidate(cleanUrl) && networkImages.size < MAX_IMAGE_URLS ->
                            networkImages.add(cleanUrl)
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onReceivedHttpError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    errorResponse: WebResourceResponse?
                ) {
                    if (request?.isForMainFrame == true) {
                        mainFrameStatus = errorResponse?.statusCode ?: 0
                        if (mainFrameStatus >= 400) {
                            navigationError = "Chapter page returned HTTP $mainFrameStatus."
                        }
                    }
                    super.onReceivedHttpError(view, request, errorResponse)
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        navigationError = error?.description?.toString()?.takeIf { it.isNotBlank() }
                            ?: "The chapter page could not be loaded."
                    }
                    super.onReceivedError(view, request, error)
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: android.webkit.RenderProcessGoneDetail
                ): Boolean {
                    navigationError = if (detail.didCrash()) {
                        "WebView renderer crashed during chapter acquisition."
                    } else {
                        "WebView renderer was reclaimed because of memory pressure."
                    }
                    finishAcquisition(error = navigationError)
                    return true
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    val targetView = view ?: return
                    if (finished.get()) return

                    val collectJs = """
                        (function() {
                          window.__mangalensChapterImages = window.__mangalensChapterImages || new Set();
                          const junk = /(logo|icon|avatar|favicon|sprite|advert|ads?[-_ ]|banner|promo|discord|social|announcement|header|footer|navbar|sidebar|cookie|captcha)/i;
                          const reader = /(reader|reading|chapter|manga|manhwa|manhua|comic|webtoon|page[-_ ]?(image|item|content)?)/i;

                          function contextFor(img) {
                            let node = img, out = (img.alt || '') + ' ' + (img.title || '');
                            for (let i = 0; i < 5 && node; i++, node = node.parentElement) {
                              out += ' ' + (node.id || '') + ' ' + (node.className || '');
                            }
                            return out;
                          }

                          function add(img, src) {
                            if (!src || src.startsWith('data:') || src.startsWith('blob:')) return;
                            let absolute;
                            try { absolute = new URL(src, location.href).href.split('#')[0]; } catch (e) { return; }
                            const ctx = contextFor(img);
                            const w = img.naturalWidth || img.width || img.getBoundingClientRect().width || 0;
                            const h = img.naturalHeight || img.height || img.getBoundingClientRect().height || 0;
                            const readerLike = reader.test(ctx);

                            if (junk.test(ctx) && !readerLike) return;
                            if (w > 0 && h > 0) {
                              if ((w < 220 || h < 220 || (w * h) < 90000) && !readerLike) return;
                              if ((w / h) > 3.0 && !readerLike) return;
                            }
                            window.__mangalensChapterImages.add(absolute);
                          }

                          document.querySelectorAll('img').forEach(img => {
                            add(img, img.currentSrc);
                            ['src','data-src','data-original','data-lazy-src','data-url','data-image'].forEach(k => add(img, img.getAttribute(k)));
                            [img.getAttribute('srcset'), img.getAttribute('data-srcset')].forEach(set => {
                              (set || '').split(',').forEach(part => add(img, part.trim().split(/\s+/)[0]));
                            });
                          });

                          const maxScroll = Math.max(0, document.documentElement.scrollHeight - innerHeight);
                          const next = Math.min(maxScroll, scrollY + Math.max(innerHeight * 0.82, 650));
                          window.scrollTo(0, next);
                          return window.__mangalensChapterImages.size;
                        })();
                    """.trimIndent()

                    val readJs = """
                        (function() {
                          return JSON.stringify(Array.from(window.__mangalensChapterImages || []));
                        })();
                    """.trimIndent()

                    fun collectPass(pass: Int) {
                        if (finished.get()) return
                        observations++
                        targetView.evaluateJavascript(collectJs) {
                            if (finished.get()) return@evaluateJavascript
                            if (pass + 1 < COLLECTION_PASSES) {
                                handler.postDelayed({ collectPass(pass + 1) }, COLLECTION_DELAY_MS)
                            } else {
                                targetView.evaluateJavascript(readJs) { raw ->
                                    if (finished.get()) return@evaluateJavascript
                                    val domImages = parseJavascriptJsonArray(raw)
                                        .filter(::isImageCandidate)
                                        .take(MAX_IMAGE_URLS)

                                    if (domImages.isNotEmpty() || networkVideos.isNotEmpty()) {
                                        finishAcquisition(
                                            finalUrl = loadedUrl ?: targetView.url ?: url,
                                            images = domImages,
                                            error = navigationError
                                        )
                                    }
                                }
                            }
                        }
                    }

                    handler.postDelayed({ collectPass(0) }, 250L)
                }
            }

            webView.loadUrl(url)
        }
    }

    private fun isImageCandidate(url: String): Boolean {
        val lower = url.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        val junk = listOf(
            "favicon", "sprite", "avatar", "emoji", "icon-", "/icon/", "logo",
            "banner", "advert", "/ads/", "doubleclick", "analytics", "tracking",
            "discord", "announcement", "promo", "social-share", "placeholder"
        )
        val readerSignal = listOf("chapter", "page", "manga", "manhwa", "manhua", "comic", "webtoon", "reader", "cdn")
            .any(lower::contains)
        if (junk.any(lower::contains) && !readerSignal) return false

        val path = lower.substringBefore("?")
        if (listOf(".js", ".css", ".html", ".json", ".xml", ".woff", ".woff2", ".svg").any(path::endsWith)) {
            return false
        }
        return listOf(".jpg", ".jpeg", ".png", ".webp", ".avif", ".gif").any(path::contains) || readerSignal
    }

    private fun isVideoCandidate(url: String): Boolean {
        val lower = url.lowercase()
        return listOf(".m3u8", ".mpd", ".mp4", ".webm", ".m4v", "/hls/", "/manifest/")
            .any(lower::contains)
    }

    private fun parseJavascriptJsonArray(raw: String?): List<String> {
        if (raw.isNullOrBlank() || raw == "null") return emptyList()
        return runCatching {
            val payload = JSONTokener(raw).nextValue() as? String ?: return@runCatching emptyList()
            val array = JSONArray(payload)
            buildList {
                for (index in 0 until array.length()) {
                    array.optString(index).takeIf { it.startsWith("http") }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }
}
