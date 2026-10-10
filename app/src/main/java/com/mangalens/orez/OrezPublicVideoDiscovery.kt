package com.mangalens.orez

import com.mangalens.orez.research.OrezResearchTransport
import com.mangalens.orez.research.ResearchHttpTransport
import com.mangalens.orez.research.ResearchOperationBudget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

/** Discovery evidence is a public video page link, never an extracted/playable stream or verified date. */
internal object OrezVideoDiscoveryLinks {
    fun normalizeIndividualVideo(value: String): String? = runCatching {
        require(value.length <= 2048 && com.mangalens.core.router.UrlEngineRouter.isSafeWebUrl(value))
        val uri = URI(value); require(uri.scheme == "https" && uri.port == -1 && uri.rawFragment == null)
        val host = uri.host.orEmpty().lowercase(Locale.ROOT); val path = uri.path.orEmpty()
        when (host) {
            "youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be" -> requireNotNull(youtubePage(value))
            "vimeo.com", "www.vimeo.com", "player.vimeo.com" -> { require(path.matches(Regex("/(?:video/)?[0-9]{1,20}/?"))); value }
            "dailymotion.com", "www.dailymotion.com" -> { require(path.matches(Regex("/video/[A-Za-z0-9]{1,32}/?"))); value }
            "instagram.com", "www.instagram.com" -> { require(path.matches(Regex("/(?:reel|reels|p)/[A-Za-z0-9_-]{1,64}/?"))); value }
            else -> { require(path.matches(Regex(".*\\.(?:mp4|m4v|mkv|webm|mov|m3u8|mpd)$", RegexOption.IGNORE_CASE))); value }
        }
    }.getOrNull()
    fun isIndividualVideo(value: String): Boolean = normalizeIndividualVideo(value) != null

    fun youtubePage(raw: String): String? = runCatching {
        val uri = URI(raw)
        require(uri.scheme == "https" && uri.rawUserInfo == null && uri.port == -1 && uri.rawFragment == null)
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        require(host in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be"))
        val path = uri.path.orEmpty()
        val ids = uri.rawQuery.orEmpty().split('&').filter { it.substringBefore('=') == "v" }
            .map { URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
        val id = when {
            host == "youtu.be" -> path.removePrefix("/").takeIf { '/' !in it }
            path in setOf("/watch", "/watch/") -> ids.singleOrNull()
            path.matches(Regex("/(shorts|embed)/[A-Za-z0-9_-]{11}/?")) -> path.split('/')[2]
            path.matches(Regex("/watch/[A-Za-z0-9_-]{11}/?")) -> path.split('/')[2]
            else -> null
        }
        require(id != null && id.matches(Regex("[A-Za-z0-9_-]{11}")))
        "https://www.youtube.com/watch?v=$id"
    }.getOrNull()
}

internal object OrezVideoDiscoveryFlow {
    suspend fun discover(query: String, native: suspend (String) -> List<OrezVideoResult>,
        public: suspend (String) -> List<OrezVideoResult>): List<OrezVideoResult> {
        val first = try { native(query).mapNotNull { row -> OrezVideoDiscoveryLinks.youtubePage(row.url)?.let { row.copy(url = it) } }.take(5) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyList() }
        currentCoroutineContext().ensureActive()
        if (first.isNotEmpty()) return first
        return try { public(query).filter { OrezVideoDiscoveryLinks.isIndividualVideo(it.url) }.take(5) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { emptyList() }
    }
}

/** Two bounded anonymous index requests, no page enrichment, cookies or credential headers. */
internal class OrezPublicVideoDiscovery(private val transport: ResearchHttpTransport) {
    suspend fun search(query: String): List<OrezVideoResult> {
        if (!validQuery(query)) return emptyList()
        return withTimeoutOrNull(9_000L) {
            val budget = ResearchOperationBudget()
            val context = currentCoroutineContext()
            val escaped = URLEncoder.encode("site:youtube.com " + query, "UTF-8")
            for (base in listOf("https://lite.duckduckgo.com/lite/?q=", "https://html.duckduckgo.com/html/?q=")) {
                context.ensureActive()
                val document = try { transport.get(base + escaped, budget) { context.isActive } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { continue }
                if (document.capture.status in setOf(401, 403, 429) ||
                    Jsoup.parse(document.text).selectFirst("#challenge-form, #anomaly-modal, .anomaly-modal") != null) break
                if (document.capture.status != 200 || document.capture.bodyTruncated) continue
                val matches = parse(document.text, document.capture.finalUrl, query)
                if (matches.isNotEmpty()) return@withTimeoutOrNull matches
            }
            emptyList()
        }.orEmpty()
    }
    companion object {
        fun production() = OrezPublicVideoDiscovery(OrezResearchTransport.production())
        fun validQuery(value: String) = value.isNotBlank() && value.length <= 256 && value.none { it.isISOControl() }
        fun browserSearch(query: String): String = "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query.take(256), "UTF-8")
        private val stopWords = setOf("find", "search", "show", "watch", "recommend", "videos", "video", "youtube", "latest", "recent", "newest", "today", "please", "me", "on", "the", "of", "for")
        private fun terms(value: String): Set<String> = Regex("[\\p{L}\\p{N}]{2,}")
            .findAll(Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT))
            .map { it.value }.filter { it !in stopWords }.take(16).toSet()

        fun parse(html: String, sourceUrl: String, query: String): List<OrezVideoResult> {
            if (html.length > 1_500_000 || !validQuery(query)) return emptyList()
            val source = runCatching { URI(sourceUrl) }.getOrNull() ?: return emptyList()
            if (source.scheme != "https" || source.host !in setOf("html.duckduckgo.com", "lite.duckduckgo.com", "duckduckgo.com") || source.rawUserInfo != null) return emptyList()
            val doc = Jsoup.parse(html, sourceUrl)
            // Challenge pages are not empty result evidence and must not be acted upon automatically.
            if (doc.selectFirst("#challenge-form, #anomaly-modal, .anomaly-modal") != null) return emptyList()
            val queryTerms = terms(query)
            if (queryTerms.isEmpty()) return emptyList()
            return doc.select("a.result__a, a.result-link").take(40).mapNotNull { anchor ->
                val title = anchor.text().trim().take(250)
                if (title.isBlank()) return@mapNotNull null
                val raw = anchor.absUrl("href")
                val link = runCatching {
                    val uri = URI(raw)
                    if (uri.host in setOf("duckduckgo.com", "www.duckduckgo.com") && uri.path == "/l/") {
                        uri.rawQuery.orEmpty().split('&').singleOrNull { it.substringBefore('=') == "uddg" }
                            ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }
                    } else raw
                }.getOrNull() ?: return@mapNotNull null
                val video = OrezVideoDiscoveryLinks.youtubePage(link) ?: return@mapNotNull null
                val snippet = if (anchor.hasClass("result__a")) anchor.closest(".result")?.selectFirst(".result__snippet")?.text().orEmpty()
                    else {
                        var row = anchor.closest("tr")?.nextElementSibling(); var found = ""
                        repeat(2) { if (row?.selectFirst("a.result-link") == null && found.isEmpty()) found = row?.selectFirst(".result-snippet")?.text().orEmpty(); row = row?.nextElementSibling() }
                        found
                    }
                if (terms(title + " " + snippet).none { it in queryTerms }) return@mapNotNull null
                OrezVideoResult(title, video, null, "YouTube · public search index", null, null, snippet.take(300))
            }.distinctBy { it.url }.take(5)
        }
    }
}
