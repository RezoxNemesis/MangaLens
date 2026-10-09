package com.mangalens.ui.video

import java.net.URI
import java.net.URLDecoder

internal sealed interface VideoPlaybackEntry {
    data class Url(val url: String) : VideoPlaybackEntry
    data class Session(val sessionId: String) : VideoPlaybackEntry
}

/** This entry grants a URL to the resolver, never header, private-path or split-source authority. */
internal fun parseVideoPlaybackEntry(value: String?): VideoPlaybackEntry? {
    if (value == null || value.length !in 1..16_384 || value.any(Char::isISOControl)) return null
    return runCatching {
        val uri = URI(value)
        if (!uri.scheme.equals("mangalens", true) || !uri.host.equals("video", true) || uri.userInfo != null ||
            uri.port != -1 || uri.rawFragment != null) return null
        val path = uri.rawPath.orEmpty()
        if (path.startsWith("/session/")) {
            val id = path.removePrefix("/session/")
            return if (uri.rawQuery == null && id.matches(Regex("[a-f0-9]{32}"))) VideoPlaybackEntry.Session(id) else null
        }
        if (path !in setOf("", "/")) return null
        val query = uri.rawQuery ?: return null
        if (!query.startsWith("url=") || '&' in query) return null
        val url = URLDecoder.decode(query.removePrefix("url="), "UTF-8")
        if (url.length !in 1..8192 || url.any(Char::isISOControl)) return null
        val source = URI(url)
        if ((!source.scheme.equals("http", true) && !source.scheme.equals("https", true)) ||
            source.host.isNullOrBlank() || source.userInfo != null) return null
        VideoPlaybackEntry.Url(url)
    }.getOrNull()
}
