package com.mangalens.core.adblock

import java.net.IDN
import java.net.URI
import java.util.Locale

enum class AdBlockMode { STRICT, STANDARD, ALLOW }

/** Query, path and credentials never form a saved rule. Host matching is exact, including www. */
data class AdBlockSite private constructor(val host: String, val origin: String) {
    companion object {
        fun from(url: String): AdBlockSite? = runCatching {
            require(url.length <= 16_384 && url.none(Char::isISOControl))
            val uri = URI(url); val scheme = uri.scheme?.lowercase(Locale.ROOT)
            require(scheme == "http" || scheme == "https")
            require(uri.rawUserInfo == null && uri.port in -1..65535)
            val wireHost = requireNotNull(uri.host).lowercase(Locale.ROOT)
            val raw = wireHost.removeSuffix(".")
            val host = if (raw.startsWith("[")) raw else IDN.toASCII(raw, IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
            require(host.length in 1..253 && host.none { it.isISOControl() || it in "\t\n /?#@" })
            val port = uri.port.takeUnless { it == -1 || (scheme == "https" && it == 443) || (scheme == "http" && it == 80) }
            AdBlockSite(host, "$scheme://$wireHost" + (port?.let { ":$it" } ?: ""))
        }.getOrNull()
    }
}

/** Standard is the existing policy. Stronger rules require explicit endpoints, never a shared CDN. */
internal object AdBlockProtectionPolicy {
    val strictAdSegments = setOf("ad-impression", "advert-impression", "adauction", "adrequest")
    val strictAdQueryKeys = setOf("ad_impression", "advert_impression", "ad_auction")
    private fun analytics(uri: URI): Boolean {
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        return (host == "google-analytics.com" || host.endsWith(".google-analytics.com")) &&
            uri.path.orEmpty().lowercase(Locale.ROOT) in setOf("/collect", "/g/collect", "/j/collect")
    }
    fun reason(url: String, type: String, mode: AdBlockMode): String? {
        if (mode == AdBlockMode.ALLOW) return null
        AdBlockRequestPolicy.blockingReason(url, type)?.let { return it }
        if (mode != AdBlockMode.STRICT || type.lowercase(Locale.ROOT) in setOf("navigation", "document")) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https") || uri.rawUserInfo != null) return null
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        if (host == "cloudflare.com" || host.endsWith(".cloudflare.com")) return null
        if (analytics(uri)) return "Strict explicit analytics endpoint"
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        if (path.split('/').any { it.substringBefore('.') in strictAdSegments }) return "Strict explicit ad endpoint"
        if (uri.rawQuery.orEmpty().split('&').any { parameter ->
                runCatching { java.net.URLDecoder.decode(parameter.substringBefore('='), "UTF-8").lowercase(Locale.ROOT) }.getOrNull() in strictAdQueryKeys
            }) return "Strict explicit ad query"
        return null
    }
    fun isTracker(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty(); val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        return analytics(uri) || host == "scorecardresearch.com" || host.endsWith(".scorecardresearch.com") ||
            path.substringAfterLast('/') in AdBlockRequestPolicy.trackerFilenames || path.split('/').any { it == "tracking" || it == "tracker" }
    }
}

internal data class AdBlockDocumentPolicyTicket(val generation: Long, val url: String, val site: AdBlockSite?, val mode: AdBlockMode, val enabled: Boolean)

/** Native navigation and IO interception share the same retirement/publication guard. */
internal class AdBlockDocumentPolicyGate {
    private var generation = 0L
    private var current: AdBlockDocumentPolicyTicket? = null
    private var closed = false
    private val retired = java.util.ArrayDeque<String>()
    @Synchronized fun started(url: String, mode: AdBlockMode, enabled: Boolean): AdBlockDocumentPolicyTicket {
        check(!closed)
        val boundedUrl = url.takeIf { it.length <= 16_384 } ?: ""
        val document = boundedUrl.substringBefore('#')
        current?.url?.substringBefore('#')?.takeIf { it != document }?.let { retired.addLast(it) }
        while (retired.size > 64) retired.removeFirst()
        retired.removeAll { it == document }
        return AdBlockDocumentPolicyTicket(++generation, boundedUrl, AdBlockSite.from(url), mode, enabled).also { current = it }
    }
    @Synchronized fun forRequest(referer: String?): AdBlockDocumentPolicyTicket? {
        if (closed || (referer != null && referer.length > 16_384) || (referer != null && referer.substringBefore('#') in retired)) return null
        return current
    }
    @Synchronized fun <T> withCurrent(ticket: AdBlockDocumentPolicyTicket, action: (AdBlockDocumentPolicyTicket) -> T): T? =
        if (closed || current != ticket) null else action(ticket)
    @Synchronized fun close() { closed = true; current = null; retired.clear() }
}
