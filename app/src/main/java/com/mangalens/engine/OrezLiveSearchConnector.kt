package com.mangalens.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class OrezSearchResult(val title: String, val snippet: String, val url: String)
data class LiveSearchAnswer(val query: String, val results: List<OrezSearchResult>, val summary: String)

class OrezLiveSearchConnector(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(14, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    suspend fun search(query: String, limit: Int = 6): LiveSearchAnswer = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val request = Request.Builder()
            .url("https://html.duckduckgo.com/html/?q=" + encoded)
            .header("User-Agent", "Mozilla/5.0 (Android) MangaLens")
            .build()
        val html = runCatching {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) readBounded(response.body?.byteStream(), MAX_HTML_BYTES) else ""
            }
        }.getOrDefault("")

        val links = Regex(
            """<a[^>]*class=["'][^"']*result__a[^"']*["'][^>]*href=["']([^"']+)["'][^>]*>(.*?)</a>""",
            RegexOption.IGNORE_CASE
        ).findAll(html).take(limit.coerceIn(1, 10)).toList()
        val snippets = Regex(
            """<(?:a|div)[^>]*class=["'][^"']*result__snippet[^"']*["'][^>]*>(.*?)</(?:a|div)>""",
            RegexOption.IGNORE_CASE
        ).findAll(html).map { clean(it.groupValues[1]) }.toList()

        val results = links.mapIndexedNotNull { index, match ->
            val title = clean(match.groupValues[2])
            val url = unwrapUrl(match.groupValues[1])
            if (title.isBlank() || !url.startsWith("http")) null
            else OrezSearchResult(title, snippets.getOrNull(index).orEmpty(), url)
        }.distinctBy { it.url }

        val enriched = coroutineScope {
            results.take(4).map { result ->
                async(Dispatchers.IO) {
                    val page = fetch(result.url, 7000)
                    val evidence = relevantSentences(page, query).take(3).joinToString(" ")
                    result.copy(snippet = evidence.ifBlank { result.snippet }.take(1000))
                }
            }.awaitAll()
        }
        val summary = if (enriched.isEmpty()) {
            "Live web search did not return readable public results. Try a more specific query."
        } else {
            buildString {
                append("Research question: ").append(query).append("\n\n")
                append("Findings from public web pages:\n")
                enriched.forEachIndexed { index, result ->
                    append(index + 1).append(". ").append(result.title).append("\n")
                    append(result.snippet.ifBlank { "The page was found, but its text could not be extracted." })
                    append("\nSource: ").append(result.url).append("\n\n")
                }
                append("Treat these as source excerpts, not as a complete answer; check dates and context where relevant.")
            }
        }
        LiveSearchAnswer(query, enriched, summary)
    }

    suspend fun fetch(url: String, maxChars: Int = 12000): String = withContext(Dispatchers.IO) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@withContext ""
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Android) MangaLens")
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) "" else readBounded(response.body?.byteStream(), MAX_HTML_BYTES)
                    .replace(Regex("(?is)<script[\\s\\S]*?</script>"), " ")
                    .replace(Regex("(?is)<style[\\s\\S]*?</style>"), " ")
                    .replace(Regex("(?is)<noscript[\\s\\S]*?</noscript>"), " ")
                    .replace(Regex("(?is)<svg[\\s\\S]*?</svg>"), " ")
                    .replace(Regex("<[^>]+>"), " ")
                    .let(::clean)
                    .take(maxChars)
            }
        }.getOrDefault("")
    }

    private fun readBounded(input: java.io.InputStream?, maxBytes: Int): String {
        if (input == null) return ""
        return input.use { stream ->
            val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (total < maxBytes) {
                val count = stream.read(buffer, 0, minOf(buffer.size, maxBytes - total))
                if (count < 0) break
                output.write(buffer, 0, count)
                total += count
            }
            output.toString(Charsets.UTF_8.name())
        }
    }

    private fun relevantSentences(text: String, query: String): List<String> {
        if (text.isBlank()) return emptyList()
        val terms = Regex("""[\p{L}\p{N}]{4,}""").findAll(query.lowercase())
            .map { it.value }.distinct().take(10).toSet()
        val sentences = text.split(Regex("""(?<=[.!?])\s+|(?<=。)\s*"""))
            .map { it.trim() }.filter { it.length >= 35 }
        val ranked = sentences.map { sentence ->
            sentence to terms.count { sentence.lowercase().contains(it) }
        }.sortedByDescending { it.second }
        val matched = ranked.filter { it.second > 0 }.take(3).map { it.first }
        return (matched.ifEmpty { sentences.take(2) }).map { it.take(650) }
    }

    private fun unwrapUrl(raw: String): String {
        val decoded = cleanAttribute(raw).replace("&amp;", "&")
        val uddg = Regex("""(?:\?|&)uddg=([^&]+)""").find(decoded)?.groupValues?.get(1)
        return if (uddg != null) runCatching { URLDecoder.decode(uddg, "UTF-8") }.getOrDefault(uddg) else decoded
    }

    private fun cleanAttribute(value: String) = value.replace("&amp;", "&").replace("&#x2F;", "/")

    companion object { private const val MAX_HTML_BYTES = 1_500_000 }

    private fun clean(value: String): String = value
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("""\s+"""), " ").trim()
}
