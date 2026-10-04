package com.mangalens.ui.video

import java.net.URI
import java.util.Locale

/** Watch webpages are handled by the site player; opaque media URLs still reach Media3. */
object VideoSourcePolicy {
    fun isSourcePage(value: String): Boolean = runCatching {
        val uri = URI(value)
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        if (Regex("\\.(mp4|m4v|mkv|webm|mov|ts|m3u8|mpd)$").containsMatchIn(path)) return@runCatching false
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        path.endsWith(".html") || path.endsWith(".htm") ||
            path.split('/').any { it in setOf("watch", "video", "videos", "embed") } ||
            host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "vimeo.com" || host.endsWith(".vimeo.com")
    }.getOrDefault(false)
}
