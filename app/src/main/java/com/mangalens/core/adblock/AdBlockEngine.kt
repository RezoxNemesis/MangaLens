package com.mangalens.core.adblock

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.charset.StandardCharsets

class AdBlockEngine(val statsStore: AdBlockStatsStore = AdBlockStatsStore()) {
    private val blockedDomains = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "adnxs.com", "popads.net", "popcash.net",
        "exoclick.com", "juicyads.com", "propellerads.com", "adsterra.com",
        "clksite.com", "coinzone.biz", "trafficjunky.net", "a-ads.com",
        "taboola.com", "outbrain.com", "scorecardresearch.com",
        "onclickperformance.com", "onclickads.net", "trafficfactory.biz",
        "pushame.com", "pushads.net", "richpush.co", "syndication.exdynsrv.com"
    )

    private val blockedPathMarkers = setOf(
        "/ads/", "/adserver/", "/advert/", "/advertising/", "/banner/",
        "/popunder", "/popup", "/clickunder", "/redirect-ad", "/prebid/",
        "/tracking/", "/tracker/", "/pixel.gif", "/beacon", "/promotions/"
    )

    private val blockedQueryMarkers = setOf(
        "popunder", "interstitial", "adclick", "clickunder",
        "redirect=ad", "redirect_ad", "tracking_pixel"
    )

    private val verificationHosts = setOf(
        "challenges.cloudflare.com", "turnstile.cloudflare.com", "cloudflare.com"
    )

    private val mediaExtensions = setOf(
        ".m3u8", ".mpd", ".mp4", ".m4v", ".ts", ".webm", ".mkv",
        ".jpg", ".jpeg", ".png", ".webp", ".avif", ".gif", ".woff", ".woff2"
    )

    fun shouldBlockRequest(url: String, pageUrl: String? = null, type: String = "unknown"): WebResourceResponse? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase().orEmpty()
        val path = uri.rawPath?.lowercase().orEmpty()
        val query = uri.rawQuery?.lowercase().orEmpty()

        val pageHost = runCatching { URI(pageUrl.orEmpty()).host.orEmpty() }.getOrDefault("")
        if (host.isBlank() || isVerificationHost(host) || mediaExtensions.any(path::endsWith)) {
            return null
        }

        val blocked = isBlockedHost(host) ||
            blockedPathMarkers.any(path::contains) ||
            blockedQueryMarkers.any(query::contains)

        if (!blocked) return null

        statsStore.recordBlocked(host, pageHost = pageHost, type = type, rule = if (isBlockedHost(host)) "Local ad/tracker domain" else "Local ad/tracker path or query")
        return WebResourceResponse(
            "text/plain",
            StandardCharsets.UTF_8.name(),
            ByteArrayInputStream(ByteArray(0))
        )
    }

    private fun isVerificationHost(host: String): Boolean =
        verificationHosts.any { host == it || host.endsWith(".$it") }

    private fun isBlockedHost(host: String): Boolean {
        var candidate = host
        while (true) {
            if (candidate in blockedDomains) return true
            val dot = candidate.indexOf('.')
            if (dot < 0 || dot == candidate.lastIndex) return false
            candidate = candidate.substring(dot + 1)
        }
    }

    fun getElementHidingScript(): String = """
        (function(){
          const selectors=[
            '[id="ad" i]','[id^="ad-" i]','[class~="ad" i]','[class~="ads" i]','[class*="ad-banner" i]',
            '[class*="popup" i]','[class*="popunder" i]',
            '[class*="overlay-ad" i]','[class*="interstitial" i]',
            '[id*="popup" i]','[id*="popunder" i]'
          ];
          const hide=()=>{
            document.querySelectorAll(selectors.join(',')).forEach(e=>{
              if(e.tagName!=='IMG'&&e.tagName!=='VIDEO'){
                e.style.setProperty('display','none','important');
                e.style.setProperty('visibility','hidden','important');
              }
            });
          };
          hide();
          if(document.documentElement){
            new MutationObserver(hide).observe(document.documentElement,{subtree:true,childList:true});
          }
        })();
    """.trimIndent()
}
