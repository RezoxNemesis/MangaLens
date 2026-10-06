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

class MangaChapterScraper(
    private val context: Context,
    private val adBlock: AdBlockEngine = AdBlockEngine()
) {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun extract(url: String, timeoutMs: Long = 18_000L): List<String> =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val web = WebView(context.applicationContext)
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                var finished = false

                fun finish(values: List<String>) {
                    if (finished) return
                    finished = true
                    handler.removeCallbacksAndMessages(null)
                    web.stopLoading()
                    web.destroy()
                    if (continuation.isActive) continuation.resume(values.distinct().take(MAX_IMAGES))
                }

                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                web.settings.loadsImagesAutomatically = true
                web.webViewClient = object : AdBlockWebViewClient(adBlock) {
                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        view?.evaluateJavascript(
                            """
                            (function(){
                              const out=[], seen=new Set();
                              const junk=/(logo|icon|avatar|favicon|sprite|advert|ads?[-_ ]|banner|promo|discord|social|announcement|header|footer|navbar|sidebar|cookie|captcha)/i;
                              const reader=/(reader|reading|chapter|manga|manhwa|manhua|comic|webtoon|page)/i;
                              function ctx(img){
                                let n=img,s=(img.alt||'')+' '+(img.title||'');
                                for(let i=0;i<5&&n;i++,n=n.parentElement)s+=' '+(n.id||'')+' '+(n.className||'');
                                return s;
                              }
                              function add(img,value){
                                if(!value||value.startsWith('data:')||value.startsWith('blob:'))return;
                                let u;try{u=new URL(value,location.href).href.split('#')[0];}catch(e){return;}
                                const c=ctx(img), preferred=reader.test(c);
                                const w=img.naturalWidth||img.width||0,h=img.naturalHeight||img.height||0;
                                if(junk.test(c)&&!preferred)return;
                                if(w>0&&h>0&&(w<220||h<220||w*h<90000)&&!preferred)return;
                                if(w>0&&h>0&&w/h>3.0&&!preferred)return;
                                if(!seen.has(u)){seen.add(u);out.push(u);}
                              }
                              document.querySelectorAll('img').forEach(img=>{
                                add(img,img.currentSrc);
                                ['src','data-src','data-original','data-lazy-src','data-url','data-image'].forEach(k=>add(img,img.getAttribute(k)));
                                [img.getAttribute('srcset'),img.getAttribute('data-srcset')].forEach(set=>{
                                  (set||'').split(',').forEach(part=>add(img,part.trim().split(/\s+/)[0]));
                                });
                              });
                              return JSON.stringify(out);
                            })();
                            """.trimIndent()
                        ) { raw ->
                            finish(parseUrls(raw).filter(::looksLikeImage))
                        }
                    }
                }

                handler.postDelayed({ finish(emptyList()) }, timeoutMs.coerceIn(5_000L, 25_000L))
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

    private fun parseUrls(raw: String?): List<String> {
        if (raw.isNullOrBlank() || raw == "null") return emptyList()
        return runCatching {
            val payload = JSONTokener(raw).nextValue() as? String ?: return@runCatching emptyList()
            val array = JSONArray(payload)
            buildList {
                for (i in 0 until array.length()) {
                    array.optString(i).takeIf { it.startsWith("http") }?.let(::add)
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun looksLikeImage(url: String): Boolean {
        val value = url.lowercase()
        val readerSignal = listOf("chapter", "page", "manga", "manhwa", "manhua", "comic", "webtoon", "reader", "cdn")
            .any(value::contains)
        val junkSignal = listOf(
            "favicon", "sprite", "avatar", "emoji", "/icon/", "logo", "banner",
            "advert", "/ads/", "discord", "announcement", "promo", "social-share", "placeholder"
        ).any(value::contains)
        if (junkSignal && !readerSignal) return false
        val path = value.substringBefore("?")
        return listOf(".jpg", ".jpeg", ".png", ".webp", ".avif", ".gif").any(path::contains) || readerSignal
    }

    companion object {
        private const val MAX_IMAGES = 3000
    }
}
