package com.mangalens.engine

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import com.mangalens.core.adblock.AdBlockEngine
import com.mangalens.core.adblock.AdBlockWebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONTokener
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
                    if (continuation.isActive) {
                        continuation.resume(values.distinctBy { it.url }.take(MAX_CHAPTER_LINKS))
                    }
                }

                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                web.webViewClient = object : AdBlockWebViewClient(adBlock) {
                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        view?.evaluateJavascript(
                            """
                            (function(){
                              const out=[], seen=new Set();
                              const chapterRe=/(chapter|chap|episode|ep)[-_ /#:]?\d+/i;
                              const semanticRe=/(chapter|chap|episode|manga|manhwa|manhua|webtoon|comic)/i;
                              document.querySelectorAll('a[href]').forEach(a=>{
                                const href=(a.href||'').split('#')[0];
                                const text=(a.innerText||a.textContent||'').trim().replace(/\s+/g,' ');
                                if(!href.startsWith('http')) return;
                                const haystack=href+' '+text;
                                if(chapterRe.test(haystack) || (semanticRe.test(haystack) && /\d+/.test(haystack))){
                                  if(!seen.has(href)){
                                    seen.add(href);
                                    out.push({title:(text||href).slice(0,240),url:href});
                                  }
                                }
                              });
                              return JSON.stringify(out);
                            })();
                            """.trimIndent()
                        ) { raw ->
                            val parsed = parseLinks(raw)
                            finish(parsed)
                        }
                    }
                }

                handler.postDelayed({ finish(emptyList()) }, timeoutMs.coerceIn(4_000L, 20_000L))
                continuation.invokeOnCancellation {
                    if (!finished) {
                        finished = true
                        handler.removeCallbacksAndMessages(null)
                        web.stopLoading()
                        web.destroy()
                    }
                }
                web.loadUrl(url)
            }
        }

    private fun parseLinks(raw: String?): List<MangaChapterLink> {
        if (raw.isNullOrBlank() || raw == "null") return emptyList()
        return runCatching {
            val payload = JSONTokener(raw).nextValue() as? String ?: return@runCatching emptyList()
            val array = JSONArray(payload)
            buildList {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val link = item.optString("url").trim()
                    if (!link.startsWith("http")) continue
                    add(MangaChapterLink(item.optString("title").trim(), link))
                }
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val MAX_CHAPTER_LINKS = 300
    }
}
