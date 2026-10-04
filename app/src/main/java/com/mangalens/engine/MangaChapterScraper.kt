package com.mangalens.engine

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.mangalens.core.adblock.AdBlockEngine
import com.mangalens.core.adblock.AdBlockWebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class MangaChapterScraper(
    private val context: Context,
    private val adBlock: AdBlockEngine = AdBlockEngine()
) {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun extract(url: String, timeoutMs: Long = 18_000L): List<String> =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val web = WebView(context.applicationContext)
                var finished = false
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                fun finish(values: List<String>) {
                    if (finished) return
                    finished = true
                    handler.removeCallbacksAndMessages(null)
                    web.stopLoading()
                    web.destroy()
                    if (continuation.isActive) continuation.resume(values.distinct())
                }

                com.mangalens.core.web.SafeWebView.configure(web)
                web.settings.domStorageEnabled = true
                web.webViewClient = object : AdBlockWebViewClient(adBlock) {
                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        view?.evaluateJavascript(
                            com.mangalens.core.acquisition.ChapterDiscoveryScript.script()
                        ) { raw ->
                            val decoded = runCatching { java.net.URLDecoder.decode(org.json.JSONTokener(raw).nextValue() as String, "UTF-8") }.getOrDefault("")
                            val urls = Regex("https?://[^\\s\"]+")
                                .findAll(decoded)
                                .map { it.value }
                                .filter { looksLikeImage(it) && com.mangalens.core.acquisition.ChapterImagePolicy.accepts(it) }
                                .toList()
                            finish(urls)
                        }
                    }
                }
                handler.postDelayed({ finish(emptyList()) }, timeoutMs)
                continuation.invokeOnCancellation { handler.post {
                    if (!finished) { finished = true; handler.removeCallbacksAndMessages(null); web.stopLoading(); web.destroy() }
                } }
                web.loadUrl(url)
            }
        }

    private fun looksLikeImage(url: String): Boolean {
        val value = url.lowercase()
        return listOf(".jpg", ".jpeg", ".png", ".webp", ".avif", ".gif").any(value::contains) ||
            Regex("chapter|page|manga|comic|cdn").containsMatchIn(value)
    }
}
