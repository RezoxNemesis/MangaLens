package com.mangalens.orez

import java.net.URI
import java.util.Locale

/** Search intent is separate from questions about playback, subtitles and app controls. */
object OrezDiscoveryPolicy {
    fun isVideoQuery(query: String): Boolean {
        val text = query.trim().lowercase(Locale.ROOT)
        if (Regex("\\b(translate|translation|subtitle|subtitles|download|fix|error|broken)\\b").containsMatchIn(text)) return false
        if (Regex("^(how|why|what|can|does|is|explain)\\b").containsMatchIn(text)) return false
        val medium = Regex("\\b(videos?|youtube|recaps?)\\b").containsMatchIn(text)
        val lookup = Regex("\\b(find|search|show|recommend|watch|latest|recent|newest)\\b").containsMatchIn(text)
        val topic = text.split(Regex("\\s+")).size in 2..12 && !text.endsWith("?")
        return medium && (lookup || topic)
    }

    fun prefersYoutube(query: String): Boolean {
        val text = query.lowercase(Locale.ROOT)
        if (Regex("\\byoutube\\b|youtu\\.be").containsMatchIn(text)) return true
        return !Regex("\\b(vimeo|dailymotion|tiktok|instagram|facebook|twitter|adult|nsfw)\\b|x\\.com|search the web|across the web|websites?").containsMatchIn(text)
    }

    /** A site/channel landing page is a source link, not a verified video result. */
    fun isVideoResult(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        val path = uri.path.orEmpty()
        fun domain(value: String) = host == value || host.endsWith(".$value")
        when {
            host == "youtu.be" -> path.trim('/').matches(Regex("[A-Za-z0-9_-]{11}"))
            domain("youtube.com") ->
                (path == "/watch" && Regex("(?:^|&)v=[A-Za-z0-9_-]{11}(?:&|$)").containsMatchIn(uri.rawQuery.orEmpty())) ||
                    path.matches(Regex("/(shorts|embed)/[A-Za-z0-9_-]{11}/?"))
            domain("dailymotion.com") -> path.startsWith("/video/") && path.substringAfter("/video/").isNotBlank()
            domain("vimeo.com") -> path.trim('/').matches(Regex("[0-9]+"))
            else -> com.mangalens.ui.video.VideoSourcePolicy.isLikelyMediaRequest(url) ||
                path.matches(Regex(".*/(watch|video|embed|reel|reels)/[^/]+/?"))
        }
    }.getOrDefault(false)
}
