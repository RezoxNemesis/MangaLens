package com.mangalens.engine

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import com.mangalens.core.adblock.AdBlockEngine
import com.mangalens.core.adblock.AdBlockWebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

data class MangaChapterLink(val title: String, val url: String)

class MangaChapterCatalogScraper(
    private val context: Context,
    private val adBlock: AdBlockEngine = AdBlockEngine()
) {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun extract(url: String, timeoutMs: Long = 12_000L): List<MangaChapterLink> =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val web = WebView(context.applicationContext)
                var finished = false
                val handler = android.os.Handler(android.os.Looper.getMainLooper())

                fun finish(values: List<MangaChapterLink>) {
                    if (finished) return
                    finished = true
                    handler.removeCallbacksAndMessages(null)
                    web.stopLoading()
                    web.destroy()
                    if (continuation.isActive) continuation.resume(values.distinctBy { it.url }.take(2000))
                }

                com.mangalens.core.web.SafeWebView.configure(web)
                web.settings.domStorageEnabled = true
                web.webViewClient = object : AdBlockWebViewClient(adBlock) {
                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        view?.evaluateJavascript(
                            """
                            (function(){
                              const out=[], seen=new Set();
                              const re=/(chapter|chap|episode|ep)[-_ ]?\d+/i;
                              document.querySelectorAll('a[href]').forEach(a=>{
                                const href=a.href||'';
                                const text=(a.innerText||a.textContent||'').trim().replace(/\s+/g,' ');
                                if(!href.startsWith('http') || href.startsWith('javascript:')) return;
                                if(re.test(href) || re.test(text)){
                                  if(!seen.has(href)){seen.add(href);out.push({title:text||href,url:href});}
                                }
                              });
                              return JSON.stringify(out);
                            })();
                            """.trimIndent()
                        ) { raw ->
                            val parsed = runCatching {
                                val array = org.json.JSONArray(org.json.JSONTokener(raw).nextValue().toString())
                                buildList {
                                    for (i in 0 until array.length()) {
                                        val item = array.getJSONObject(i)
                                        add(MangaChapterLink(item.optString("title"), item.optString("url")))
                                    }
                                }
                            }.getOrDefault(emptyList())
                            finish(parsed)
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
}
