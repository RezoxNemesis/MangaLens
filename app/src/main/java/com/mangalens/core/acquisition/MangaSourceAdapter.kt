package com.mangalens.core.acquisition

import com.mangalens.core.router.UrlEngineRouter
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Locale

data class SourceChapter(val title: String, val url: String)
data class SourceContent(val title: String, val pageUrls: List<String>, val chapters: List<SourceChapter>)

interface MangaSourceAdapter {
    val id: String
    fun parse(html: String, sourceUrl: String): SourceContent
}

/** Conservative HTML fallback: only chapter reader containers supply pages, never site logos. */
class GenericMangaSourceAdapter : MangaSourceAdapter {
    override val id = "generic-html-v1"
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

    private fun imageUrl(image: Element): String? =
        listOf("data-src", "data-original", "data-lazy-src", "src").asSequence()
            .mapNotNull { normalizedWebUrl(image.attr(it), image.baseUri()) }
            .firstOrNull(::isLikelyChapterImage)

    /** This provider uses standalone images; its advertisement shares the reader CSS class. */
    private fun demonicChapterImages(document: Document, sourceUrl: String): List<String> {
        val source = sourceUrl.toHttpUrlOrNull() ?: return emptyList()
        if (source.host !in setOf("demonicscans.org", "www.demonicscans.org")) return emptyList()
        val sourcePath = source.pathSegments
        if (sourcePath.size !in 4..5 || sourcePath[0] != "title" || sourcePath[2] != "chapter" ||
            !sourcePath[3].matches(Regex("\\d+(?:\\.\\d+)?"))) return emptyList()
        val title = sourcePath[1].replace('-', ' ')
        val chapter = sourcePath[3]
        val caption = Regex("^${Regex.escape(title)}\\s+Chapter\\s+${Regex.escape(chapter)}\\s+(\\d+)$", RegexOption.IGNORE_CASE)
        return document.select("img.imgholder[alt]").mapNotNull { image ->
            val index = caption.matchEntire(image.attr("alt").trim())?.groupValues?.get(1)?.toIntOrNull()
                ?.takeIf { it in 0 until 3000 } ?: return@mapNotNull null
            val url = imageUrl(image)?.toHttpUrlOrNull() ?: return@mapNotNull null
            val path = url.pathSegments
            if (url.host !in setOf("cdn.demoniclibs.com", "cdn.librarydm.com") || path.size != 3 ||
                !path[0].equals(title, ignoreCase = true) || path[1] != chapter ||
                !path[2].lowercase(Locale.ROOT).matches(Regex("$index\\.(jpg|jpeg|png|webp|avif)"))) null
            else url.toString()
        }
    }

    override fun parse(html: String, sourceUrl: String): SourceContent {
        require(html.length <= 1_500_000) { "Source page is too large for lightweight extraction." }
        require(UrlEngineRouter.isSafeWebUrl(sourceUrl))
        val document = Jsoup.parse(html, sourceUrl)
        val readers = document.select(".reading-content, .reader-area, .chapter-content, #readerarea, #chapter-images, .manga-reader")
        val pages = (readers.select("img").mapNotNull(::imageUrl) + demonicChapterImages(document, sourceUrl))
            .distinct().take(3000)
        val host = sourceUrl.toHttpUrlOrNull()?.host
        val chapters = document.select("a[href]").asSequence().mapNotNull { link ->
            val url = normalizedWebUrl(link.attr("href"), link.baseUri()) ?: return@mapNotNull null
            val title = link.text().trim().take(300)
            if (!UrlEngineRouter.isSafeWebUrl(url) || URI(url).host != host ||
                !(chapterPattern.containsMatchIn(title) || chapterPattern.containsMatchIn(URI(url).path.orEmpty()))) null
            else SourceChapter(title.ifBlank { URI(url).path.substringAfterLast('/') }, url)
        }.distinctBy { it.url }.take(2000).toList()
            .sortedWith(compareBy<SourceChapter> { chapterPattern.find(it.title)?.groupValues?.get(2)?.toDoubleOrNull() ?: Double.MAX_VALUE }.thenBy { it.title })
        return SourceContent(document.title().take(300).ifBlank { "Chapter" }, pages, chapters)
    }
}
