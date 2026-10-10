package com.mangalens.core.acquisition

import com.mangalens.core.router.UrlEngineRouter
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

data class SourceChapter(val title: String, val url: String)
data class SourceContent(val title: String, val pageUrls: List<String>, val chapters: List<SourceChapter>,
    val pageCandidates: List<ChapterImageCandidate> = pageUrls.map { ChapterImageCandidate(it) })

interface MangaSourceAdapter {
    val id: String
    fun parse(html: String, sourceUrl: String): SourceContent
}

/** Conservative HTML fallback: only chapter reader containers supply pages, never site logos. */
class GenericMangaSourceAdapter : MangaSourceAdapter {
    override val id = "generic-html-v2"
    private val chapterPattern = Regex("(?i)(chapter|chap|episode|ep)[/ _-]*(\\d+(?:\\.\\d+)?)")
    private fun isLikelyChapterImage(url: String): Boolean {
        val lower = url.lowercase()
        return listOf(
            "google.com/recaptcha",
            "gstatic.com/recaptcha",
            "hcaptcha.com/",
            "challenges.cloudflare.com/",
            "/cdn-cgi/challenge-platform/",
            "cf-chl-",
            "/captcha/",
            "captcha.php"
        ).none(lower::contains)
    }

    private fun normalizedWebUrl(value: String, baseUrl: String): String? {
        val candidate = value.trim().substringBefore('#').replace(" ", "%20")
        if (candidate.isBlank()) return null
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        if (uri.rawUserInfo != null) return null
        val resolved = baseUrl.toHttpUrlOrNull()?.resolve(candidate)?.toString() ?: return null
        return resolved.takeIf(UrlEngineRouter::isSafeWebUrl)
    }

    private fun imageUrl(image: Element): String? {
        val sources = listOf("data-original", "data-src", "data-lazy-src", "data-url")
            .map { image.attr(it) } + listOfNotNull(ChapterImageCandidates.largestSrcset(image.attr("data-srcset")),
                ChapterImageCandidates.largestSrcset(image.attr("srcset"))) +
            image.parent()?.takeIf { it.tagName() == "picture" }?.select("source")?.take(8)?.mapNotNull {
                ChapterImageCandidates.largestSrcset(it.attr("data-srcset")) ?: ChapterImageCandidates.largestSrcset(it.attr("srcset"))
            }.orEmpty() + image.attr("src")
        return sources.asSequence().mapNotNull { ChapterImageCandidates.normalizedUrl(it, image.baseUri()) }
            .firstOrNull(::isLikelyChapterImage)
    }

    private fun candidate(image: Element, sourceUrl: String): ChapterImageCandidate? {
        val url = imageUrl(image) ?: return null
        var parent: Element? = image
        val tokens = StringBuilder()
        var adSlot = false
        var href: String? = null
        for (depth in 0 until 6) {
            val node = parent ?: break
            if (node.tagName() in setOf("body", "html") || node.`is`(ChapterImageCandidates.READER_SELECTOR)) break
            tokens.append(' ').append(node.id().take(256)).append(' ').append(node.className().take(256))
            adSlot = adSlot || node.hasAttr("data-ad-slot")
            if (href == null && node.tagName() == "a") href = normalizedWebUrl(node.attr("href"), sourceUrl)
            parent = node.parent()
        }
        return ChapterImageCandidate(url, ChapterImageCandidates.promotion(
            image.attr("alt").take(500) + " " + image.attr("title").take(500), tokens.toString(), adSlot, href, sourceUrl))
    }

    override fun parse(html: String, sourceUrl: String): SourceContent {
        require(html.length <= 1_500_000) { "Source page is too large for lightweight extraction." }
        require(UrlEngineRouter.isSafeWebUrl(sourceUrl))
        val document = Jsoup.parse(html, sourceUrl)
        val readers = document.select(ChapterImageCandidates.READER_SELECTOR)
        // The standalone provider also labels genuine and promotional originals with imgholder.
        // Preserve those nodes; presentation evidence can collapse a promo without losing its file.
        val standalone = sourceUrl.toHttpUrlOrNull()?.let {
            val path = it.pathSegments
            it.host in setOf("demonicscans.org", "www.demonicscans.org") && path.size in 4..5 &&
                path[0] == "title" && path[2] == "chapter" && path[3].matches(Regex("\\d+(?:\\.\\d+)?"))
        } == true
        val standaloneImages = if (standalone) document.select("img.imgholder").filter { image ->
            val item = candidate(image, sourceUrl)
            item != null && (ChapterImageCandidates.standaloneProviderPage(item.url, image.attr("alt"), sourceUrl) || item.promotion != null)
        } else emptyList()
        val selected = (readers.select("img") + standaloneImages).toSet()
        val candidates = document.select("img").asSequence().take(6000).filter { it in selected }
            .filter { it.closest("form,[role=dialog],[aria-modal=true]") == null }
            .mapNotNull { candidate(it, sourceUrl) }.distinctBy { it.url }.take(ChapterImageCandidates.MAX_IMAGES).toList()
        val pages = candidates.map { it.url }
        val host = sourceUrl.toHttpUrlOrNull()?.host
        val chapters = document.select("a[href]").asSequence().mapNotNull { link ->
            val url = normalizedWebUrl(link.attr("href"), link.baseUri()) ?: return@mapNotNull null
            val title = link.text().trim().take(300)
            if (!UrlEngineRouter.isSafeWebUrl(url) || URI(url).host != host ||
                !(chapterPattern.containsMatchIn(title) || chapterPattern.containsMatchIn(URI(url).path.orEmpty()))) null
            else SourceChapter(title.ifBlank { URI(url).path.substringAfterLast('/') }, url)
        }.distinctBy { it.url }.take(2000).toList()
            .sortedWith(compareBy<SourceChapter> { chapterPattern.find(it.title)?.groupValues?.get(2)?.toDoubleOrNull() ?: Double.MAX_VALUE }.thenBy { it.title })
        return SourceContent(document.title().take(300).ifBlank { "Chapter" }, pages, chapters, candidates)
    }
}
