package com.mangalens.core.acquisition

import com.mangalens.core.router.UrlEngineRouter
import org.jsoup.Jsoup
import java.net.URI

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

    override fun parse(html: String, sourceUrl: String): SourceContent {
        require(html.length <= 1_500_000) { "Source page is too large for lightweight extraction." }
        require(UrlEngineRouter.isSafeWebUrl(sourceUrl))
        val document = Jsoup.parse(html, sourceUrl)
        val readers = document.select(".reading-content, .reader-area, .chapter-content, #readerarea, #chapter-images, .manga-reader")
        val pages = readers.select("img").mapNotNull { image ->
            listOf("data-src", "data-original", "data-lazy-src", "src").asSequence()
                .map { image.absUrl(it).trim().substringBefore('#') }
                .firstOrNull { candidate ->
                    UrlEngineRouter.isSafeWebUrl(candidate) && isLikelyChapterImage(candidate)
                }
        }.distinct().take(3000)
        val host = URI(sourceUrl).host
        val chapters = document.select("a[href]").asSequence().mapNotNull { link ->
            val url = link.absUrl("href").substringBefore('#')
            val title = link.text().trim().take(300)
            if (!UrlEngineRouter.isSafeWebUrl(url) || URI(url).host != host ||
                !(chapterPattern.containsMatchIn(title) || chapterPattern.containsMatchIn(URI(url).path.orEmpty()))) null
            else SourceChapter(title.ifBlank { URI(url).path.substringAfterLast('/') }, url)
        }.distinctBy { it.url }.take(2000).toList()
            .sortedWith(compareBy<SourceChapter> { chapterPattern.find(it.title)?.groupValues?.get(2)?.toDoubleOrNull() ?: Double.MAX_VALUE }.thenBy { it.title })
        return SourceContent(document.title().take(300).ifBlank { "Chapter" }, pages, chapters)
    }
}
