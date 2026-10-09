package com.mangalens.core.adblock

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Locale
import java.nio.charset.StandardCharsets

class AdBlockEngine(val statsStore: AdBlockStatsStore = AdBlockStatsStore()) {
    fun shouldBlockRequest(url: String, pageUrl: String? = null, type: String = "unknown"): WebResourceResponse? {
        val rule = AdBlockRequestPolicy.blockingReason(url, type) ?: return null
        val host = runCatching { URI(url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        val pageHost = runCatching { URI(pageUrl.orEmpty()).host.orEmpty() }.getOrDefault("")
        statsStore.recordBlocked(host, pageHost = pageHost, type = type, rule = rule)
        return WebResourceResponse("text/plain", StandardCharsets.UTF_8.name(), ByteArrayInputStream(ByteArray(0)))
    }

    fun getElementHidingScript(): String = AdBlockScript.build()
}
