package com.mangalens.core.web

import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

/** Applies the same untrusted-page policy to browsing, verification and acquisition. */
object SafeWebView {
    @Suppress("DEPRECATION")
    fun configure(view: WebView, cookies: CookieManager = CookieManager.getInstance()) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
        }
        cookies.setAcceptThirdPartyCookies(view, false)
    }
}
