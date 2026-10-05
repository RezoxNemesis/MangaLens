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
        "bidvertiser.com", "revcontent.com", "mgid.com", "zedo.com",
        "adsrvr.org", "rubiconproject.com", "openx.net", "pubmatic.com",
        "criteo.com", "criteo.net", "smartadserver.com", "casalemedia.com",
        "serving-sys.com", "lijit.com", "contextweb.com", "yieldmo.com",
        "sharethrough.com", "33across.com", "adform.net", "adform.com"
    )

    private val blockedPathMarkers = setOf(
        "/ads/", "/adserver/", "/advert/", "/advertising/", "/banner/",
        "/popunder", "/popup", "/clickunder", "/redirect-ad", "/prebid/",
        "/tracking/", "/tracker/", "/pixel.gif", "/beacon", "/promotions/",
        "/vast", "/vmap", "/preroll", "/pre-roll", "/midroll", "/postroll",
        "/popads", "/clickads", "/adtag/", "/ads.xml", "/ad.xml",
        "/pagead/", "/adview", "/adclick", "/sponsor/", "/sponsored/",
        "/commercial/", "/advertiser/", "/trackingpixel", "/event.gif"
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
        val blockedPathOrQuery = blockedPathMarkers.any(path::contains) ||
            blockedQueryMarkers.any(query::contains)
        // Ordinary first-party media remains exempt, but a first-party /preroll/, /vast or other
        // explicit ad path must not become invisible to the blocker merely because it ends in MP4.
        if (!blockedHost && !blockedPathOrQuery && mediaExtensions.any(path::endsWith)) return null

        if (!blockedHost && !blockedPathOrQuery) return null

        statsStore.recordBlocked(
            host,
            pageHost = pageHost,
            type = type,
            rule = if (blockedHost) "Local ad/tracker domain" else "Local ad/tracker path or query"
        )
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
          const suspicious=/doubleclick|googlesyndication|googleadservices|exoclick|trafficjunky|popads|popcash|clickadu|hilltopads|admaven|ad-maven|trafficstars|juicyads|rubiconproject|pubmatic|criteo|smartadserver|casalemedia|adsrvr|clickunder|popunder|interstitial|\/vast(?:[/?#]|$)|\/vmap(?:[/?#]|$)|\/pagead\/|\/preroll(?:[/?#]|$)|\/midroll(?:[/?#]|$)/i;
          const suspiciousUrl=(value)=>{
            try{
              const u=new URL(String(value||''),location.href);
              const thirdParty=u.hostname!==location.hostname && !u.hostname.endsWith('.'+location.hostname) && !location.hostname.endsWith('.'+u.hostname);
              return suspicious.test(u.href) && (thirdParty || /\/vast|\/vmap|\/pagead|\/preroll|\/midroll/i.test(u.pathname));
            }catch(_){ return false; }
          };
          const cleanAnchors=()=>{
            document.querySelectorAll('a[target="_blank"],a[target="_new"]').forEach(a=>{
              try {
                const u=new URL(a.href,location.href);
                if(suspiciousUrl(u.href)){
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
              if(suspiciousUrl(absolute)) return null;
            }catch(_){}
            return nativeOpen.call(window,target,name,features);
          };
          const nativeBeacon=navigator.sendBeacon?.bind(navigator);
          if(nativeBeacon){
            navigator.sendBeacon=function(target,data){
              if(suspiciousUrl(target)) return true;
              return nativeBeacon(target,data);
            };
          }
          const nativeFetch=window.fetch?.bind(window);
          if(nativeFetch){
            window.fetch=function(input,init){
              const target=typeof input==='string'?input:(input&&input.url)||'';
              if(suspiciousUrl(target)) return Promise.resolve(new Response('',{status:204}));
              return nativeFetch(input,init);
            };
          }
          const nativeXhrOpen=XMLHttpRequest.prototype.open;
          XMLHttpRequest.prototype.open=function(method,target){
            if(suspiciousUrl(target)){
              this.__mangalensBlocked=true;
              return nativeXhrOpen.call(this,method,'data:text/plain,',true);
            }
            return nativeXhrOpen.apply(this,arguments);
          };
          hide(); cleanAnchors();
          if(document.documentElement){
            new MutationObserver(()=>{hide();cleanAnchors();}).observe(document.documentElement,{subtree:true,childList:true,attributes:true,attributeFilter:['class','id','style']});
          }
        })();
    """.trimIndent()
}
