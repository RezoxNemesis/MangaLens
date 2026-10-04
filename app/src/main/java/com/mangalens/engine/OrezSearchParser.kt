package com.mangalens.engine

import com.mangalens.core.router.UrlEngineRouter
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder

/** Keeps result snippets paired with their source despite attribute order or multiline HTML. */
object OrezSearchParser {
    fun parse(html: String, limit: Int = 6): List<OrezSearchResult> {
        if (html.length > 1_500_000) return emptyList()
        return Jsoup.parse(html, "https://html.duckduckgo.com/").select(".result").mapNotNull { result ->
            val anchor = result.selectFirst("a.result__a") ?: return@mapNotNull null
            val title = anchor.text().trim().take(500)
            val raw = anchor.absUrl("href")
            val wrapped = runCatching {
                URI(raw).rawQuery?.split('&')?.firstOrNull { it.startsWith("uddg=") }
                    ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }
            }.getOrNull()
            val url = wrapped ?: raw
            if (title.isBlank() || !UrlEngineRouter.isSafeWebUrl(url)) null else
                OrezSearchResult(title, result.selectFirst(".result__snippet")?.text().orEmpty().take(1000), url)
        }.distinctBy { it.url }.take(limit.coerceIn(1, 10))
    }
}
