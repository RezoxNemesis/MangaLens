package com.mangalens.ui.video

import java.net.URI
import java.util.Locale

/**
 * Classifies watch pages separately from concrete media requests.
 *
 * Modern social/video CDNs often omit file extensions entirely, so request headers and known
 * delivery hosts are considered in addition to .mp4/.m3u8/.mpd suffixes.
 */
object VideoSourcePolicy {
    private val directPath = Regex("\\.(mp4|m4v|mkv|webm|mov|ts|m3u8|mpd)$", RegexOption.IGNORE_CASE)

    fun isSourcePage(value: String): Boolean = runCatching {
        val uri = URI(value)
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        if (directPath.containsMatchIn(path)) return@runCatching false
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        path.endsWith(".html") || path.endsWith(".htm") ||
            path.split('/').any { it in setOf("watch", "video", "videos", "embed", "reel", "reels", "shorts") } ||
            matchesHost(host, "youtube.com") || host == "youtu.be" ||
            matchesHost(host, "instagram.com") ||
            matchesHost(host, "facebook.com") || host == "fb.watch" ||
            matchesHost(host, "x.com") || matchesHost(host, "twitter.com") ||
            matchesHost(host, "tiktok.com") ||
            matchesHost(host, "vimeo.com") ||
            matchesHost(host, "rule34video.com") ||
            matchesHost(host, "spankbang.com")
    }.getOrDefault(false)

    fun isLikelyMediaRequest(value: String, headers: Map<String, String> = emptyMap()): Boolean =
        mediaScore(value, headers) > 0

    fun preferredMediaUrl(values: Iterable<String>): String? =
        values.asSequence()
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .map { it to mediaScore(it) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first

    internal fun mediaScore(value: String, headers: Map<String, String> = emptyMap()): Int {
        val lower = value.lowercase(Locale.ROOT)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return 0
        val uri = runCatching { URI(value) }.getOrNull() ?: return 0
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        val decoded = lower
            .replace("%2f", "/")
            .replace("%3a", ":")
            .replace("%3d", "=")
            .replace("%2c", ",")
        val headerMap = headers.entries.associate { it.key.lowercase(Locale.ROOT) to it.value.lowercase(Locale.ROOT) }
        val accept = headerMap["accept"].orEmpty()
        val destination = headerMap["sec-fetch-dest"].orEmpty()
        val range = headerMap["range"].orEmpty()

        // Audio-only adaptive requests must never replace the primary video candidate.
        if (
            "mime=audio" in decoded || "mime%3daudio" in lower ||
            "mime_type=audio" in decoded || "audio/mp4" in decoded ||
            (destination == "audio" && destination != "video")
        ) return 0

        return when {
            ".m3u8" in lower -> 900
            ".mpd" in lower -> 880
            ".mp4" in lower -> 860
            ".m4v" in lower -> 820
            ".webm" in lower -> 800
            ".mkv" in lower || ".mov" in lower -> 760

            // YouTube's actual playback URLs normally have no extension.
            matchesHost(host, "googlevideo.com") && path.contains("/videoplayback") -> 850

            // Instagram/Reels commonly use Facebook/Instagram CDN paths such as /o1/v/t16/.
            (matchesHost(host, "cdninstagram.com") || matchesHost(host, "fbcdn.net")) &&
                (
                    "/v/t16/" in path || "/o1/v/" in path || "video" in decoded ||
                    "video/" in accept || destination == "video" || range.startsWith("bytes=")
                ) -> 830

            // X/Twitter video delivery.
            matchesHost(host, "video.twimg.com") -> 810

            // TikTok CDN URLs frequently omit a familiar extension.
            (
                "tiktok" in host || "byteoversea" in host || "ibytedtos" in host
            ) && (
                "/video/" in path || "video/" in accept || destination == "video" ||
                    range.startsWith("bytes=")
                ) -> 790

            destination == "video" || "video/" in accept -> 720
            "/hls/" in lower -> 700
            "/dash/" in lower -> 680
            "/manifest/" in lower -> 640
            ".ts" in lower -> 120
            else -> 0
        }
    }

    private fun matchesHost(host: String, domain: String): Boolean =
        host == domain || host.endsWith(".$domain")
}
