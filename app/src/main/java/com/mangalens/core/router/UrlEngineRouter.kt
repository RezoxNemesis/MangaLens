package com.mangalens.core.router

import com.mangalens.core.model.ContentType
import java.net.URI
import java.util.Locale

class UrlEngineRouter {
    private val videoExtensions = setOf(".m3u8", ".mpd", ".mp4", ".ts", ".webm", ".mkv", ".mov")
    private val videoDomainKeywords = setOf("youtube", "vimeo", "dailymotion", "twitch", "video", "player", "stream")
    private val mangaDomainKeywords = setOf("mangadex", "manhua", "manhwa", "manga", "readmanga", "asura", "reaper", "flame")

    private val mangaPathRegex = Regex(
        pattern = """(^|/)(chapter|manga|comic|manhwa|manhua|ep)(/|[-_]|\b)""",
        option = RegexOption.IGNORE_CASE
    )

    companion object {
        fun isSafeWebUrl(value: String): Boolean = runCatching {
            val uri = URI(value.trim())
            uri.scheme?.lowercase(Locale.ROOT) in setOf("http", "https") &&
                !uri.host.isNullOrBlank() && uri.rawUserInfo == null
        }.getOrDefault(false)
    }

    fun classifyUrl(url: String): ContentType {
        val normalized = url.trim()
        if (normalized.isBlank()) return ContentType.GENERIC_WEB
        if (!isSafeWebUrl(normalized)) return ContentType.GENERIC_WEB
        val uri = URI(normalized)
        val lower = ((uri.host ?: "") + (uri.rawPath ?: "")).lowercase(Locale.ROOT)

        if (videoExtensions.any { lower.endsWith(it) || lower.contains("$it?") || lower.contains("$it#") }) {
            return ContentType.VIDEO_STREAM
        }

        val path = runCatching { URI(normalized).rawPath.orEmpty() }
            .getOrDefault(normalized.substringAfter("://", normalized).substringAfter("/", ""))
        if (mangaPathRegex.containsMatchIn("/$path")) return ContentType.IMAGE_CHAPTER
        if (mangaDomainKeywords.any { lower.contains(it) }) return ContentType.IMAGE_CHAPTER
        if (lower.contains("/chapter-") || lower.contains("/read/") || lower.contains("/page/") || lower.contains("/episode/")) {
            return ContentType.IMAGE_CHAPTER
        }

        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        val videoPath = path.lowercase(Locale.ROOT).split('/').filter(String::isNotBlank)
        if (
            videoDomainKeywords.any { host.contains(it) } ||
            videoPath.any { it in setOf("watch", "video", "videos", "player", "embed", "stream") }
        ) {
            return ContentType.VIDEO_STREAM
        }

        return ContentType.GENERIC_WEB
    }
}
