package com.mangalens.core.adblock

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

open class AdBlockWebViewClient(
    private val adBlockEngine: AdBlockEngine = AdBlockEngine(),
    private val enabled: () -> Boolean = { true }
) : WebViewClient() {
    @Volatile private var pageOrigin: String? = null
    @Volatile private var closed = false
    private val injection = AdBlockInjectionGate()
    private val documentStart = AdBlockDocumentStartRegistration()
    internal var allowDocumentStart = true // Controlled Android fixture also exercises the older-provider fallback.
    internal val hasDocumentStartScript: Boolean get() = documentStart.installed

    /** Call before navigation; a supported provider runs the guard before page scripts. */
    @androidx.annotation.UiThread
    fun prepareForNavigation(view: WebView) {
        if (closed) return
        val active = enabled()
        documentStart.update(view, active) {
            if (!allowDocumentStart || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) null
            else {
                val handler = WebViewCompat.addDocumentStartJavaScript(view, adBlockEngine.getElementHidingScript(), setOf("*"))
                val remove: () -> Unit = { handler.remove() }
                remove
            }
        }
        if (!active) view.evaluateJavascript(AdBlockScript.disable(), null)
    }

    /** Terminal cleanup removes this registration and invalidates every held callback. */
    @androidx.annotation.UiThread
    fun clearScriptRegistration() {
        closed = true
        injection.close()
        documentStart.close()
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val safeRequest = request ?: return true
        val url = safeRequest.url.toString()
        if (!com.mangalens.core.router.UrlEngineRouter.isSafeWebUrl(url)) return true
        if (enabled() && adBlockEngine.shouldBlockRequest(url, pageOrigin, "navigation") != null) return true
        return false
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        pageOrigin = url
        super.onPageStarted(view, url, favicon)
        if (view == null || url == null) return
        val ticket = injection.started(url) ?: return
        prepareForNavigation(view)
        injectIfCurrent(view, ticket)
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        if (closed || !enabled()) return super.shouldInterceptRequest(view, request)
        val req = request ?: return super.shouldInterceptRequest(view, null as WebResourceRequest?)
        val type = if (req.isForMainFrame) "document" else req.requestHeaders["Sec-Fetch-Dest"] ?: "unknown"
        return adBlockEngine.shouldBlockRequest(req.url.toString(), pageOrigin, type) ?: super.shouldInterceptRequest(view, request)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        val ticket = injection.currentFor(url) ?: return
        if (view != null && enabled()) injectIfCurrent(view, ticket)
    }

    private fun injectIfCurrent(view: WebView, ticket: AdBlockInjectionTicket) {
        view.post {
            if (closed || !enabled()) return@post
            if (injection.permits(ticket, view.url, true)) {
                view.evaluateJavascript(adBlockEngine.getElementHidingScript(), null)
            }
        }
    }
}
