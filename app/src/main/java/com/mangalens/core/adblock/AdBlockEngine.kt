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
        "pushame.com", "pushads.net", "richpush.co", "syndication.exdynsrv.com",
        "exdynsrv.com", "realsrv.com", "clickadu.com", "clickaine.com",
        "onclicka.com", "onclickperformance.com", "trafficstars.com", "trafficstars.net",
        "hilltopads.net", "hilltopads.com", "admaven.com", "ad-maven.com",
        "adspyglass.com", "ero-advertising.com", "plugrush.com", "popunder.net",
        "tsyndicate.com", "twinrdsyn.com", "go2cloud.org", "mediafuse.com",
        "bidvertiser.com", "revcontent.com", "mgid.com", "zedo.com"
    )

    private val blockedPathMarkers = setOf(
        "/ads/", "/adserver/", "/advert/", "/advertising/", "/banner/",
        "/popunder", "/popup", "/clickunder", "/redirect-ad", "/prebid/",
        "/tracking/", "/tracker/", "/pixel.gif", "/beacon", "/promotions/",
        "/vast", "/vmap", "/preroll", "/pre-roll", "/midroll", "/postroll",
        "/popads", "/clickads", "/adtag/", "/ads.xml", "/ad.xml"
    )

    private val blockedQueryMarkers = setOf(
        "popunder", "interstitial", "adclick", "clickunder",
        "redirect=ad", "redirect_ad", "tracking_pixel", "vast=", "vmap=",
        "adtag=", "ad_url=", "adurl=", "campaign=", "pop="
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
        if (host.isBlank() || isVerificationHost(host)) return null

        // Known ad/tracker hosts are denied even when they serve MP4/HLS assets. The old ordering
        // exempted any media-looking URL first, which let video pre-rolls from ad networks through.
        val blockedHost = isBlockedHost(host)
        if (!blockedHost && mediaExtensions.any(path::endsWith)) return null

        val blocked = blockedHost ||
            blockedPathMarkers.any(path::contains) ||
            blockedQueryMarkers.any(query::contains)

        if (!blocked) return null

        statsStore.recordBlocked(host, pageHost = pageHost, type = type, rule = if (blockedHost) "Local ad/tracker domain" else "Local ad/tracker path or query")
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
            '[id*="popup" i]','[id*="popunder" i]',
            '[class*="ad-container" i]','[class*="ad-wrapper" i]','[class*="ad-slot" i]',
            '[id*="ad-container" i]','[id*="ad-wrapper" i]','[id*="ad-slot" i]',
            'iframe[src*="doubleclick" i]','iframe[src*="exoclick" i]',
            'iframe[src*="trafficjunky" i]','iframe[src*="popads" i]',
            'iframe[src*="clickadu" i]'
          ];
          const hide=()=>{
            document.querySelectorAll(selectors.join(',')).forEach(e=>{
              if(e.tagName!=='IMG'&&e.tagName!=='VIDEO'){
                e.style.setProperty('display','none','important');
                e.style.setProperty('visibility','hidden','important');
              }
            });
          };
          const suspicious=/doubleclick|googlesyndication|exoclick|trafficjunky|popads|popcash|clickadu|hilltopads|admaven|ad-maven|trafficstars|juicyads|clickunder|popunder|interstitial|\/vast(?:[/?#]|$)|\/vmap(?:[/?#]|$)/i;
          const cleanAnchors=()=>{
            document.querySelectorAll('a[target="_blank"],a[target="_new"]').forEach(a=>{
              try {
                const u=new URL(a.href,location.href);
                if(suspicious.test(u.href)){
                  a.removeAttribute('target');
                  a.addEventListener('click',e=>{e.preventDefault();e.stopImmediatePropagation();},{capture:true});
                }
              } catch(_){}
            });
          };
          const nativeOpen=window.open;
          window.open=function(target,name,features){
            try{
              const absolute=new URL(String(target||''),location.href).href;
              if(suspicious.test(absolute)) return null;
            }catch(_){}
            return nativeOpen.call(window,target,name,features);
          };
          hide(); cleanAnchors();
          if(document.documentElement){
            new MutationObserver(()=>{hide();cleanAnchors();}).observe(document.documentElement,{subtree:true,childList:true});
          }
        })();
    """.trimIndent()
}
