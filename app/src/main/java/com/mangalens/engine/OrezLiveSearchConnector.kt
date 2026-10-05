package com.mangalens.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class OrezSearchResult(val title: String, val snippet: String, val url: String)
data class LiveSearchAnswer(val query: String, val results: List<OrezSearchResult>, val summary: String, val provider: String = "web")

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
        val html = get("https://html.duckduckgo.com/html/?q=" + encoded)
        val primary = OrezSearchParser.parse(html, limit)
        val results = primary.ifEmpty {
            OrezSearchParser.parseWikipedia(get("https://en.wikipedia.org/w/rest.php/v1/search/page?q=$encoded&limit=${limit.coerceIn(1, 10)}"), limit)
        }
        val provider = if (primary.isEmpty() && results.isNotEmpty()) "wikipedia" else "web"

        val enriched = if (provider == "wikipedia") results.take(4) else coroutineScope {
            results.take(4).map { result ->
                async(Dispatchers.IO) {
                    val host = runCatching { java.net.URI(result.url).host.orEmpty().lowercase() }.getOrDefault("")
                    val evidence = if (host.endsWith("wikipedia.org")) {
                        result.snippet
                    } else {
                        val page = fetch(result.url, 7000)
                        relevantSentences(page, query).take(3).joinToString(" ")
                    }
                    result.copy(snippet = cleanEvidence(evidence.ifBlank { result.snippet }).take(700))
                }
            }.awaitAll()
        }
        val summary = if (enriched.isEmpty()) {
            "Live web search did not return readable public results. Try a more specific query."
        } else {
            buildString {
                append("Research question: ").append(query).append("\n\n")
                if (provider == "wikipedia") append("Wikipedia encyclopedia fallback; this does not verify current news, prices or schedules. Wikipedia excerpts are CC BY-SA 4.0, with original page links below.\n\n")
                append("Findings from public web pages:\n")
                enriched.forEachIndexed { index, result ->
                    append(index + 1).append(". ").append(result.title).append("\n")
                    append(result.snippet.ifBlank { "The page was found, but its text could not be extracted." })
                    append("\nSource: ").append(result.url).append("\n\n")
                }
                append("Treat these as source excerpts, not as a complete answer; check dates and context where relevant.")
            }
        }
        LiveSearchAnswer(query, enriched, summary, provider)
    }

    suspend fun fetch(url: String, maxChars: Int = 12000): String = withContext(Dispatchers.IO) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return@withContext ""
        get(url)
                    .replace(Regex("(?is)<script[\\s\\S]*?</script>"), " ")
                    .replace(Regex("(?is)<style[\\s\\S]*?</style>"), " ")
                    .replace(Regex("(?is)<noscript[\\s\\S]*?</noscript>"), " ")
                    .replace(Regex("(?is)<svg[\\s\\S]*?</svg>"), " ")
                    .replace(Regex("(?is)<(?:nav|header|footer|aside|form)[^>]*>[\\s\\S]*?</(?:nav|header|footer|aside|form)>"), " ")
                    .replace(Regex("<[^>]+>"), " ")
                    .let(::cleanEvidence)
                    .take(maxChars.coerceIn(0, 12_000))
    }

    private suspend fun get(url: String): String = coroutineScope {
        val request = Request.Builder().url(url)
            .header("User-Agent", "MangaLens/1.4 (https://github.com/RezoxNemesis/MangaLens)")
            .build()
        val call = client.newCall(request)
        val jobContext = currentCoroutineContext()
        val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.execute().use { response ->
                jobContext.ensureActive()
                if (response.isSuccessful) readBounded(response.body?.byteStream(), MAX_HTML_BYTES) { jobContext.ensureActive() } else ""
            }
        } catch (failure: java.io.IOException) {
            jobContext.ensureActive()
            ""
        } finally { cancellation.cancel() }
    }

    private fun readBounded(input: java.io.InputStream?, maxBytes: Int, checkActive: () -> Unit): String {
        if (input == null) return ""
        return input.use { stream ->
            val output = java.io.ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (total < maxBytes) {
                checkActive()
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
            .map(::cleanEvidence)
            .filter { it.length in 35..650 }
            .filterNot { isBoilerplate(it) }
        val ranked = sentences.map { sentence ->
            sentence to terms.count { sentence.lowercase().contains(it) }
        }.sortedByDescending { it.second }
        val matched = ranked.filter { it.second > 0 }.take(3).map { it.first }
        return (matched.ifEmpty { sentences.take(2) }).map { it.take(650) }
    }

    private fun cleanEvidence(value: String): String =
        clean(value)
            .replace(Regex("""(?i)\b(jump to content|main menu|navigation|create account|log in|donate|personal tools|move to sidebar|toggle .*? subsection)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun isBoilerplate(value: String): Boolean {
        val lower = value.lowercase()
        val markers = listOf(
            "jump to content", "main menu", "navigation", "create account", "log in",
            "privacy policy", "terms of use", "cookie policy", "subscribe", "sign up",
            "upload file", "community portal", "recent changes"
        )
        return markers.count(lower::contains) >= 1 ||
            value.split(' ').distinct().size < value.split(' ').size * .45
    }

    companion object { private const val MAX_HTML_BYTES = 1_500_000 }

    private fun clean(value: String): String = value
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
        .replace(Regex("""\s+"""), " ").trim()
}
