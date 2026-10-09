package com.mangalens.download

import java.net.URI

/** Installed extraction and client fallbacks share a budget, with time left for HTML fallback. */
internal object SiteExtractionPolicy {
    fun clients(url: String): List<String?> {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return if (host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com"))
            listOf(null, "tv", "web_embedded", "web_safari", "ios", "android")
        else listOf(null)
    }

    fun extractorBudgetMs(remainingMs: Long): Long = (remainingMs - remainingMs / 4L).coerceAtLeast(1L)

    fun attemptBudgetMs(index: Int, remainingMs: Long): Long =
        minOf(if (index == 0) 16_000L else 8_000L, remainingMs.coerceAtLeast(1L))
}
