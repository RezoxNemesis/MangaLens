package com.mangalens.ui.web

import com.mangalens.core.router.UrlEngineRouter
import java.net.URLEncoder

object BrowserAddress {
    fun resolve(input: String): String? {
        val text = input.trim()
        if (text.isBlank() || text.length > 8192 || text.any { it.isISOControl() }) return null
        if (UrlEngineRouter.isSafeWebUrl(text)) return text
        if (Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
        if (!text.contains(' ') && text.substringBefore('/').contains('.')) {
            return ("https://$text").takeIf(UrlEngineRouter::isSafeWebUrl)
        }
        return "https://duckduckgo.com/?q=" + URLEncoder.encode(text, "UTF-8")
    }
}
