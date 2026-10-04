package com.mangalens.core.adblock

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

open class AdBlockWebViewClient(
    private val adBlockEngine: AdBlockEngine = AdBlockEngine(),
    private val enabled: () -> Boolean = { true }
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
        request == null || !com.mangalens.core.router.UrlEngineRouter.isSafeWebUrl(request.url.toString())

    @Volatile private var pageOrigin: String? = null
    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        pageOrigin = url
        super.onPageStarted(view, url, favicon)
    }

    private val enterpriseGuard = EnterpriseAdBlockEngine(adBlockEngine)

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        if (!enabled()) return super.shouldInterceptRequest(view, request)
        val reqUrl = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
        return adBlockEngine.shouldBlockRequest(reqUrl, pageOrigin, request?.requestHeaders?.get("Sec-Fetch-Dest") ?: "unknown") ?: super.shouldInterceptRequest(view, request)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        if (!enabled()) return
        view?.post {
            view.evaluateJavascript(adBlockEngine.getElementHidingScript(), null)
            view.evaluateJavascript(enterpriseGuard.mutationObserverScript(), null)
        }
    }
}
