package com.mangalens.core.adblock

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

open class AdBlockWebViewClient(
    private val adBlockEngine: AdBlockEngine = AdBlockEngine(),
    private val enabled: () -> Boolean = { true },
    private val protection: ((String) -> AdBlockMode)? = null,
    private val scopeCurrent: () -> Boolean = { true }
) : WebViewClient() {
    @Volatile private var closed = false
    private val injection = AdBlockInjectionGate()
    private val documentStart = AdBlockDocumentStartRegistration()
    private val policy = AdBlockDocumentPolicyGate().apply { started("", AdBlockMode.STANDARD, true) }
    internal var allowDocumentStart = true // Controlled Android fixture also exercises the older-provider fallback.
    internal val hasDocumentStartScript: Boolean get() = documentStart.installed

    /** Native caller supplies its exact target before dispatch. Registration is scoped to that origin/mode. */
    @androidx.annotation.UiThread
    fun prepareForNavigation(view: WebView, pageUrl: String? = view.url) {
        if (closed || !scopeCurrent()) return
        val address = pageUrl.orEmpty()
        val mode = protection?.invoke(address) ?: AdBlockMode.STANDARD
        val ticket = policy.started(address, mode, enabled())
        val active = ticket.enabled && mode != AdBlockMode.ALLOW && (protection == null || ticket.site != null)
        val origin = ticket.site?.origin
        documentStart.updateScoped(view, active, "$origin:$mode") {
            if (!allowDocumentStart || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) null
            else {
                val handler = WebViewCompat.addDocumentStartJavaScript(view,
                    adBlockEngine.getElementHidingScript(mode, origin), setOf(origin ?: "*"))
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
        policy.close()
        documentStart.close()
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val safeRequest = request ?: return true
        val url = safeRequest.url.toString()
        if (!com.mangalens.core.router.UrlEngineRouter.isSafeWebUrl(url)) return true
        val ticket = policy.forRequest(null)
        if (!closed && scopeCurrent() && enabled() && ticket != null && policy.withCurrent(ticket) {
                if (!scopeCurrent() || !it.enabled) null else adBlockEngine.shouldBlockRequest(url, it.url, "navigation", it.mode)
            } != null) return true
        return false
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        super.onPageStarted(view, url, favicon)
        if (closed || !scopeCurrent() || view == null || url == null) return
        prepareForNavigation(view, url)
        val ticket = injection.started(url) ?: return
        injectIfCurrent(view, ticket)
    }

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
        if (closed || !scopeCurrent() || !enabled()) return super.shouldInterceptRequest(view, request)
        val req = request ?: return super.shouldInterceptRequest(view, null as WebResourceRequest?)
        val referer = req.requestHeaders.entries.firstOrNull { it.key.equals("referer", true) }?.value
        val ticket = policy.forRequest(referer) ?: return super.shouldInterceptRequest(view, request)
        val destination = req.requestHeaders.entries.firstOrNull { it.key.equals("Sec-Fetch-Dest", true) }?.value
        val type = if (req.isForMainFrame) "document" else destination?.takeIf {
            it in setOf("script", "style", "image", "font", "video", "audio", "fetch", "empty")
        } ?: "unknown"
        // Unknown-frame attribution stays candidate-only; spanning-generation or known retired requests cannot publish stats.
        return policy.withCurrent(ticket) {
            if (closed || !scopeCurrent() || !enabled() || !it.enabled) null
            else adBlockEngine.shouldBlockRequest(req.url.toString(), it.url, type, it.mode)
        } ?: super.shouldInterceptRequest(view, request)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        val ticket = injection.currentFor(url) ?: return
        if (view != null && enabled()) injectIfCurrent(view, ticket)
    }

    private fun injectIfCurrent(view: WebView, ticket: AdBlockInjectionTicket) {
        val captured = policy.forRequest(null) ?: return
        view.post {
            if (closed || !scopeCurrent() || !enabled()) return@post
            policy.withCurrent(captured) {
                if (it.enabled && it.mode != AdBlockMode.ALLOW && (protection == null || it.site != null) && injection.permits(ticket, view.url, true))
                    view.evaluateJavascript(adBlockEngine.getElementHidingScript(it.mode, it.site?.origin), null)
            }
        }
    }
}
