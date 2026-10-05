package com.mangalens.core.adblock

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class EnterpriseAdBlockEngine(
    private val base: AdBlockEngine = AdBlockEngine()
) : WebViewClient() {

    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?
    ): WebResourceResponse? {
        val safeRequest = request ?: return null
        val destination = safeRequest.requestHeaders["Sec-Fetch-Dest"].orEmpty().lowercase()
        return base.shouldBlockRequest(safeRequest.url.toString())
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
        super.onPageStarted(view, url, favicon)
        view?.post { view.evaluateJavascript(mutationObserverScript(), null) }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        view?.post { view.evaluateJavascript(mutationObserverScript(), null) }
    }

    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse(
            "text/plain",
            StandardCharsets.UTF_8.name(),
            ByteArrayInputStream(ByteArray(0))
        )

    fun mutationObserverScript(): String = """
        (function() {
          if (window.__mangalensEnterpriseAdGuard) return;
          window.__mangalensEnterpriseAdGuard = true;
          const deny = /(casino|slot|gambl|popup|popunder|clickunder|interstitial|advert|sponsor|doubleclick|googlesyndication|exoclick|trafficjunky|popads|popcash|clickadu|hilltopads|admaven|trafficstars|juicyads)/i;
          const selectors = [
            '[id="ad" i]','[id^="ad-" i]','[class~="ad" i]','[class~="ads" i]','[class*="ad-banner" i]',
            '[class*="popup" i]','[class*="popunder" i]',
            '[class*="interstitial" i]','[class*="overlay-ad" i]',
            'iframe[src*="doubleclick.net" i]','iframe[src*="googlesyndication.com" i]',
            'iframe[src*="exoclick" i]','iframe[src*="trafficjunky" i]',
            'iframe[src*="popads" i]','iframe[src*="clickadu" i]',
            'iframe[src*="hilltopads" i]','iframe[src*="trafficstars" i]'
          ];
          function remove(root) {
            if (!root || root.nodeType !== 1) return;
            const matched = root.matches && root.matches(selectors.join(',')) ? [root] : [];
            const descendants = root.querySelectorAll ? Array.from(root.querySelectorAll(selectors.join(','))) : [];
            matched.concat(descendants).forEach(function(el) {
              if (el.tagName !== 'IMG' && el.tagName !== 'VIDEO') el.remove();
            });

          }
          const nativeOpen = window.open;
          window.open = function(url, name, features) {
            try {
              const absolute = new URL(String(url || ''), location.href).href;
              if (deny.test(absolute)) return null;
            } catch (_) {}
            return nativeOpen.call(window, url, name, features);
          };
          remove(document.documentElement);
          const observer = new MutationObserver(function(mutations) {
            mutations.forEach(function(m) {
              Array.from(m.addedNodes).forEach(remove);
            });
          });
          observer.observe(document.documentElement, {childList:true, subtree:true});
          document.addEventListener('click', function(e) {
            const target = e.target && e.target.closest ? e.target.closest('a') : null;
            if (!target) return;
            const href = target.href || '';
            if (/popunder|clickunder|redirect.*ad|casino|bet|gambl|exoclick|trafficjunky|popads|clickadu|hilltopads|trafficstars/i.test(href)) {
              e.preventDefault();
              e.stopImmediatePropagation();
            }
          }, true);
        })();
    """.trimIndent()
}
