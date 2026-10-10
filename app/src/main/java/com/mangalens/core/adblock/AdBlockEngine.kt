package com.mangalens.core.adblock

import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.net.URI
import java.util.Locale
import java.nio.charset.StandardCharsets

class AdBlockEngine(val statsStore: AdBlockStatsStore = AdBlockStatsStore()) {
    fun shouldBlockRequest(url: String, pageUrl: String? = null, type: String = "unknown", mode: AdBlockMode = AdBlockMode.STANDARD): WebResourceResponse? {
        val rule = AdBlockProtectionPolicy.reason(url, type, mode) ?: return null
        val host = runCatching { URI(url).host.orEmpty().lowercase(Locale.ROOT) }.getOrDefault("")
        val pageHost = AdBlockSite.from(pageUrl.orEmpty())?.host.orEmpty()
        statsStore.recordBlocked(host, pageHost = pageHost, type = type, rule = rule, tracker = AdBlockProtectionPolicy.isTracker(url))
        return WebResourceResponse("text/plain", StandardCharsets.UTF_8.name(), 200, "OK", emptyMap(), ByteArrayInputStream(ByteArray(0)))
    }

    fun getElementHidingScript(mode: AdBlockMode = AdBlockMode.STANDARD, origin: String? = null): String = AdBlockScript.build(mode, origin)
}
