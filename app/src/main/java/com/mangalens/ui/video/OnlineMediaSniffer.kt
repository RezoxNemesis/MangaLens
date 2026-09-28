package com.mangalens.ui.video

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import com.mangalens.core.adblock.AdBlockWebViewClient
import java.util.concurrent.CopyOnWriteArraySet

data class SniffedMedia(val url: String, val headers: Map<String, String>, val kind: String)

class OnlineMediaSniffer(private val context: Context) {
    private val urls = CopyOnWriteArraySet<String>()

    @SuppressLint("SetJavaScriptEnabled")
    fun inspect(pageUrl: String, onMedia: (List<SniffedMedia>) -> Unit): WebView {
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(object {
                @JavascriptInterface fun report(value: String) {
                    if (value.startsWith("http")) {
                        urls += value
                        onMedia(urls.map { SniffedMedia(it, emptyMap(), kind(it)) })
                    }
                }
            }, "MangaLensMedia")
            webViewClient = object : AdBlockWebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                    val u = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                    if (looksLikeMedia(u)) {
                        urls += u
                        onMedia(urls.map { SniffedMedia(it, request.requestHeaders, kind(it)) })
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript("""
                        (function(){
                          const emit=(u)=>{try{
                            const x=new URL(u,location.href).href;
                            if(x.startsWith('http')) MangaLensMedia.report(x);
                          }catch(e){}};
                          document.querySelectorAll('video,source').forEach(e=>emit(e.currentSrc||e.src));
                          new MutationObserver(()=>document.querySelectorAll('video,source').forEach(e=>emit(e.currentSrc||e.src)))
                            .observe(document.documentElement,{subtree:true,childList:true,attributes:true});
                        })();
                    """.trimIndent(), null)
                }
            }
            loadUrl(pageUrl)
        }
    }

    private fun looksLikeMedia(url: String): Boolean {
        val x = url.lowercase()
        return listOf(".m3u8", ".mpd", ".mp4", ".m4v", ".webm", ".ts").any(x::contains)
    }

    private fun kind(url: String): String = when {
        ".m3u8" in url.lowercase() -> "HLS"
        ".mpd" in url.lowercase() -> "DASH"
        ".ts" in url.lowercase() -> "MPEG-TS"
        else -> "PROGRESSIVE"
    }
}