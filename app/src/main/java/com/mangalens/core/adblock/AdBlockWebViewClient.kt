package com.mangalens.core.adblock

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

open class AdBlockWebViewClient(
    private val adBlockEngine: AdBlockEngine = AdBlockEngine()
) : WebViewClient() {
    private val enterpriseGuard = EnterpriseAdBlockEngine(adBlockEngine)

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        val reqUrl = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
        enterpriseGuard.shouldInterceptRequest(view, request)?.let { return it }
        return adBlockEngine.shouldBlockRequest(reqUrl) ?: super.shouldInterceptRequest(view, request)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        view?.post {
            view.evaluateJavascript(adBlockEngine.getElementHidingScript(), null)
            view.evaluateJavascript(enterpriseGuard.mutationObserverScript(), null)
        }
    }
}
