package com.mangalens.core.adblock

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Local rules shared by request interception and the document script. */
internal object AdBlockRequestPolicy {
    internal val blockedDomains = setOf(
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
        "/popunder", "/clickunder", "/redirect-ad", "/prebid/",
        "/tracking/", "/tracker/", "/pixel.gif", "/beacon", "/promotions/",
        "/vast", "/vmap", "/preroll", "/pre-roll", "/midroll", "/postroll",
        "/popads", "/clickads", "/adtag/", "/ads.xml", "/ad.xml",
        "/pagead/", "/adview", "/adclick", "/sponsor/", "/sponsored/",
        "/commercial/", "/advertiser/", "/trackingpixel", "/event.gif"
    )

    internal val adQueryKeys = setOf(
        "popunder", "clickunder", "adclick", "redirect_ad", "tracking_pixel",
        "vast", "vmap", "adtag", "ad_url", "adurl"
    )
    internal val explicitAdSegments = setOf(
        "ads", "adserver", "adtag", "pagead", "vast", "vmap", "preroll", "pre-roll",
        "midroll", "postroll", "adclick", "popads", "clickads", "clickunder", "popunder", "redirect-ad"
    )
    internal val adNavigationSegments = setOf("clickunder", "popunder", "redirect-ad")
    internal val trackerFilenames = setOf("pixel.gif", "event.gif", "trackingpixel.gif", "beacon.gif")

    private val verificationHosts = setOf(
        "challenges.cloudflare.com", "turnstile.cloudflare.com", "cloudflare.com"
    )

    private val mediaExtensions = setOf(
        ".m3u8", ".mpd", ".mp4", ".m4v", ".ts", ".webm", ".mkv", ".m4s",
        ".m4a", ".aac", ".mp3", ".opus", ".ogg",
        ".jpg", ".jpeg", ".png", ".webp", ".avif", ".gif", ".woff", ".woff2"
    )


    fun blockingReason(url: String, type: String = "unknown"): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val path = uri.path?.lowercase(Locale.ROOT).orEmpty()
        val segments = path.split('/').map { it.substringBefore('.') }
        if (host.isBlank() || isVerificationHost(host)) return null
        if (isBlockedHost(host)) return "Local ad/tracker domain"
        val navigation = type.lowercase(Locale.ROOT) in setOf("navigation", "document")
        if (navigation) return if (segments.any(adNavigationSegments::contains))
            "Local ad redirect path" else null
        val queryAd = uri.rawQuery.orEmpty().split('&').any { parameter ->
            val key = parameter.substringBefore('=')
            runCatching { URLDecoder.decode(key, "UTF-8").lowercase(Locale.ROOT) }.getOrDefault(key) in adQueryKeys
        }
        val explicitAd = segments.any(explicitAdSegments::contains) || path.substringAfterLast('/') in trackerFilenames || queryAd
        if (!explicitAd && mediaExtensions.any(path::endsWith)) return null
        if (!explicitAd && blockedPathMarkers.none(path::contains)) return null
        return "Local ad/tracker path or query"
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

}

/** Keep an intercepted ad from becoming a native playback candidate. */
internal inline fun <R> interceptBeforeMediaObservation(intercept: () -> R?, observe: () -> Unit): R? {
    val response = intercept()
    if (response != null) return response
    observe()
    return null
}
