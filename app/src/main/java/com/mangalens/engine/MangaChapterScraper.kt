package com.mangalens.engine

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import com.mangalens.core.acquisition.ChapterImageCandidate
import com.mangalens.core.acquisition.ChapterImageCandidates
import com.mangalens.core.acquisition.ChapterImageExtractionScript
import com.mangalens.core.adblock.AdBlockEngine
import com.mangalens.core.adblock.AdBlockWebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class MangaChapterScraper(
    private val context: Context,
    private val adBlock: AdBlockEngine = AdBlockEngine(),
    private val adBlockEnabled: () -> Boolean = { true }
) {
    suspend fun extract(url: String, timeoutMs: Long = 18_000L): List<String> =
        extractCandidates(url, timeoutMs).map { it.url }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun extractCandidates(url: String, timeoutMs: Long = 18_000L): List<ChapterImageCandidate> =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val web = WebView(context.applicationContext)
                var finished = false
                var generation = 0L
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                fun close() {
                    handler.removeCallbacksAndMessages(null)
                    (web.webViewClient as? AdBlockWebViewClient)?.clearScriptRegistration()
                    web.stopLoading()
                    web.destroy()
                }
                fun finish(values: List<ChapterImageCandidate>) {
                    if (finished) return
                    finished = true
                    close()
                    if (continuation.isActive) continuation.resume(values.distinctBy { it.url })
                }
                com.mangalens.core.web.SafeWebView.configure(web)
                web.settings.domStorageEnabled = true
                web.webViewClient = object : AdBlockWebViewClient(adBlock, adBlockEnabled) {
                    override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                        if (finished) return
                        generation++
                        super.onPageStarted(view, pageUrl, favicon)
                    }
                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        super.onPageFinished(view, pageUrl)
                        if (finished || view == null || pageUrl == null || view.url != pageUrl) return
                        val captured = generation
                        view.evaluateJavascript(ChapterImageExtractionScript.extract) { raw ->
                            if (!finished && generation == captured && view.url == pageUrl)
                                finish(ChapterImageCandidates.decode(raw.orEmpty(), pageUrl))
                        }
                    }
                    override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                        finish(emptyList())
                        return true
                    }
                }
                handler.postDelayed({ finish(emptyList()) }, timeoutMs.coerceIn(5_000L, 20_000L))
                continuation.invokeOnCancellation { handler.post { if (!finished) { finished = true; close() } } }
                (web.webViewClient as AdBlockWebViewClient).prepareForNavigation(web)
                web.loadUrl(url)
            }
        }
}
