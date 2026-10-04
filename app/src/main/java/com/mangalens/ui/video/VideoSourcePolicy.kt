package com.mangalens.ui.video

import java.net.URI
import java.util.Locale

/** Watch webpages stay in Web mode until MangaLens has a concrete media request. */
object VideoSourcePolicy {
    private val directPath = Regex("\\.(mp4|m4v|mkv|webm|mov|ts|m3u8|mpd)$", RegexOption.IGNORE_CASE)

    fun isSourcePage(value: String): Boolean = runCatching {
        val uri = URI(value)
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        if (directPath.containsMatchIn(path)) return@runCatching false
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        path.endsWith(".html") || path.endsWith(".htm") ||
            path.split('/').any { it in setOf("watch", "video", "videos", "embed") } ||
            host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "vimeo.com" || host.endsWith(".vimeo.com")
    }.getOrDefault(false)

    fun isLikelyMediaRequest(value: String): Boolean = mediaScore(value) > 0

    fun preferredMediaUrl(values: Iterable<String>): String? =
        values.asSequence()
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .map { it to mediaScore(it) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first

    internal fun mediaScore(value: String): Int {
        val lower = value.lowercase(Locale.ROOT)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return 0
        return when {
            ".m3u8" in lower -> 600
            ".mpd" in lower -> 550
            ".mp4" in lower -> 500
            ".m4v" in lower -> 450
            ".webm" in lower -> 400
            ".mkv" in lower || ".mov" in lower -> 350
            "/hls/" in lower -> 300
            "/dash/" in lower -> 290
            "/manifest/" in lower -> 250
            ".ts" in lower -> 50
            else -> 0
        }
    }
}
