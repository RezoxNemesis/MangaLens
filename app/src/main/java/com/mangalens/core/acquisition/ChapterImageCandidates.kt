package com.mangalens.core.acquisition

import com.mangalens.core.reader.ReaderPromoPolicy
import com.mangalens.core.router.UrlEngineRouter
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

/** Presentation evidence only. Every selected original is still acquired and can be revealed. */
enum class ChapterImagePromotion { AD_CONTAINER, COMMERCIAL_LINK, PROMOTIONAL_LABEL }

data class ChapterImageCandidate(val url: String, val promotion: ChapterImagePromotion? = null)

/** One preferred original per node, in document order; request order never supplies chapter order. */
object ChapterImageCandidates {
    const val MAX_IMAGES = 3000
    const val MAX_URL_CHARS = 4096
    const val MAX_JSON_CHARS = 1_500_000
    const val READER_SELECTOR = ".reading-content,.reader-area,.chapter-content,#readerarea,#chapter-images,.manga-reader,#manga-reader,.chapter-reader,.container-chapter-reader"
    private val advertisementToken = Regex("(?:^|[\\s_-])(?:ad|ads|advert|advertisement|advertising|adbanner|adslot|sponsored)(?:$|[\\s_-])", RegexOption.IGNORE_CASE)
    internal val commercialHosts = setOf("girlfriendgpt.com", "girlfriendgpt.ai", "girlfriendgpt.co", "veyragame.com")

    /** Existing provider scope: its explicit caption, selected chapter and source CDN must agree. */
    fun standaloneProviderPage(url: String, label: String, source: String): Boolean {
        val page = source.toHttpUrlOrNull() ?: return false
        val path = page.pathSegments
        if (page.host !in setOf("demonicscans.org", "www.demonicscans.org") || path.size !in 4..5 ||
            path[0] != "title" || path[2] != "chapter" || !path[3].matches(Regex("\\d+(?:\\.\\d+)?"))) return false
        val title = path[1].replace('-', ' ')
        val caption = Regex("^${Regex.escape(title)}\\s+Chapter\\s+${Regex.escape(path[3])}\\s+(\\d+)$", RegexOption.IGNORE_CASE)
        val index = caption.matchEntire(label.trim())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 0 until MAX_IMAGES } ?: return false
        val image = url.toHttpUrlOrNull() ?: return false
        val imagePath = image.pathSegments
        return image.host in setOf("cdn.demoniclibs.com", "cdn.librarydm.com") && imagePath.size == 3 &&
            imagePath[0].equals(title, true) && imagePath[1] == path[3] &&
            imagePath[2].lowercase(java.util.Locale.ROOT).matches(Regex("$index\\.(jpg|jpeg|png|webp|avif)"))
    }

    fun normalizedUrl(value: String, base: String): String? {
        if (value.length > MAX_URL_CHARS) return null
        val candidate = value.trim().substringBefore('#')
        // Missing lazy-image attributes must not resolve to the chapter document itself.
        if (candidate.isBlank()) return null
        val resolved = base.toHttpUrlOrNull()?.resolve(candidate) ?: return null
        return resolved.toString().takeIf { resolved.username.isEmpty() && resolved.password.isEmpty() && UrlEngineRouter.isSafeWebUrl(it) }
    }

    fun promotion(label: String, containerTokens: String, adSlot: Boolean, link: String?, source: String): ChapterImagePromotion? {
        if (adSlot || advertisementToken.containsMatchIn(containerTokens.take(2048)) ||
            label.trim().matches(Regex("(?:advertisement|sponsored|advert)\\s*", RegexOption.IGNORE_CASE))) return ChapterImagePromotion.AD_CONTAINER
        val target = link?.toHttpUrlOrNull()
        val sourceHost = source.toHttpUrlOrNull()?.host
        if (target != null && target.host != sourceHost && commercialHosts.any { target.host == it || target.host.endsWith(".$it") })
            return ChapterImagePromotion.COMMERCIAL_LINK
        return ChapterImagePromotion.PROMOTIONAL_LABEL.takeIf { ReaderPromoPolicy.isLikelyPromo(label.take(1000)) }
    }

    fun largestSrcset(value: String): String? = value.takeIf { it.length <= 32_768 }?.split(',')?.mapNotNull { part ->
        val pieces = part.trim().split(Regex("\\s+"))
        val url = pieces.firstOrNull()?.takeIf { it.isNotBlank() && !it.startsWith("data:", true) } ?: return@mapNotNull null
        val score = pieces.getOrNull(1)?.removeSuffix("w")?.removeSuffix("x")?.toDoubleOrNull() ?: 1.0
        url to score.takeIf { it.isFinite() && it > 0 }.orEmptyScore()
    }?.maxByOrNull { it.second }?.first

    private fun Double?.orEmptyScore(): Double = this ?: 1.0

    internal data class Observation(val images: List<ChapterImageCandidate>, val videos: List<String>,
        val viewport: ChapterObservationViewport?, val limited: Boolean)
    internal fun decodeObservation(raw: String, expectedPage: String): Observation = Observation(
        decode(raw, expectedPage), decodeVideos(raw, expectedPage), decodeViewport(raw, expectedPage), decodeLimited(raw, expectedPage))
    internal fun decodeLimited(raw: String, expectedPage: String): Boolean {
        if (raw.length > MAX_JSON_CHARS * 2) return true
        return runCatching {
            val text = org.json.JSONTokener(raw).nextValue() as? String ?: return@runCatching true
            require(text.length <= MAX_JSON_CHARS)
            val json = JSONObject(text)
            require(normalizedUrl(json.getString("pageUrl"), expectedPage) == normalizedUrl(expectedPage, expectedPage))
            json.optBoolean("limited", false) || (json.optJSONArray("images")?.length() ?: 0) >= MAX_IMAGES ||
                (json.optJSONArray("videos")?.length() ?: 0) >= 500
        }.getOrDefault(true)
    }

    internal fun decodeViewport(raw: String, expectedPage: String): ChapterObservationViewport? {
        if (raw.length > MAX_JSON_CHARS * 2) return null
        return runCatching {
            val text = org.json.JSONTokener(raw).nextValue() as? String ?: return@runCatching null
            require(text.length <= MAX_JSON_CHARS)
            val json = JSONObject(text)
            require(normalizedUrl(json.getString("pageUrl"), expectedPage) == normalizedUrl(expectedPage, expectedPage))
            val viewport = json.getJSONObject("viewport")
            ChapterObservationViewport(viewport.getLong("top"), viewport.getLong("height"), viewport.getLong("total"))
        }.getOrNull()
    }

    fun decodeVideos(raw: String, expectedPage: String): List<String> {
        if (raw.length > MAX_JSON_CHARS * 2) return emptyList()
        return runCatching {
            val text = org.json.JSONTokener(raw).nextValue() as? String ?: return@runCatching emptyList()
            require(text.length <= MAX_JSON_CHARS)
            val json = JSONObject(text)
            require(normalizedUrl(json.getString("pageUrl"), expectedPage) == normalizedUrl(expectedPage, expectedPage))
            val videos = json.optJSONArray("videos") ?: return@runCatching emptyList()
            require(videos.length() <= 500)
            (0 until videos.length()).mapNotNull { normalizedUrl(videos.getString(it), expectedPage) }.distinct()
        }.getOrDefault(emptyList())
    }

    /** Decode structured native-owned output; reject a stale document and cap every record. */
    fun decode(raw: String, expectedPage: String): List<ChapterImageCandidate> {
        if (raw.length > MAX_JSON_CHARS * 2) return emptyList()
        return runCatching {
            val decoded = org.json.JSONTokener(raw).nextValue() as? String ?: return@runCatching emptyList()
            require(decoded.length <= MAX_JSON_CHARS)
            val json = JSONObject(decoded)
            require(normalizedUrl(json.getString("pageUrl"), expectedPage) == normalizedUrl(expectedPage, expectedPage))
            val images = json.getJSONArray("images")
            require(images.length() <= MAX_IMAGES)
            (0 until images.length()).mapNotNull { index ->
                val row = images.getJSONObject(index)
                val url = normalizedUrl(row.getString("url"), expectedPage) ?: return@mapNotNull null
                val reason = row.optString("promotion").takeIf { it.isNotEmpty() }?.let { value ->
                    ChapterImagePromotion.entries.firstOrNull { it.name == value }
                }
                ChapterImageCandidate(url, reason)
            }.distinctBy { it.url }
        }.getOrDefault(emptyList())
    }
}
