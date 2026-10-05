package com.mangalens.ui.video

import java.net.URI
import java.util.Locale

/** Immutable playback context. Captured cookies have no domain/path metadata, so they
 * are usable only for the exact captured URL; subsequent requests use the browser jar. */
class MediaRequestContext(
    private val mediaUrl: String,
    sourcePageUrl: String? = null,
    headers: Map<String, String> = emptyMap()
) {
    private val captured = headers.entries.mapNotNull { (name, value) ->
        val key = name.lowercase(Locale.ROOT)
        if (key in ALLOWED && safeValue(value)) key to value else null
    }.toMap()
    private val page = sourcePageUrl?.let(::webUri)
        ?: captured["referer"]?.let(::webUri)

    fun headersFor(requestUrl: String, browserCookie: String?): Map<String, String> {
        val target = webUri(requestUrl) ?: return emptyMap()
        val result = linkedMapOf(
            "User-Agent" to (captured["user-agent"] ?: USER_AGENT),
            "Accept" to (captured["accept"] ?: "*/*"),
            "Accept-Language" to (captured["accept-language"] ?: "en-US,en;q=0.9")
        )
        if (page != null && !(page.scheme.equals("https", true) && target.scheme.equals("http", true))) {
            result["Referer"] = page.toASCIIString()
            result["Origin"] = captured["origin"]
                ?: "${page.scheme.lowercase(Locale.ROOT)}://${page.rawAuthority}"
        }
        val cookie = browserCookie?.takeIf { it.isNotBlank() && safeValue(it) }
            ?: captured["cookie"]?.takeIf { requestUrl == mediaUrl }
        if (cookie != null) result["Cookie"] = cookie
        return result
    }

    companion object {
        private val ALLOWED = setOf("accept", "accept-language", "cookie", "origin", "referer", "user-agent")
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 Chrome/140.0.0.0 Mobile Safari/537.36 MangaLens/2.1"
        private fun safeValue(value: String) = value.length <= 16_384 &&
            value.none { it < ' ' || it == '\u007f' }
        private fun webUri(value: String): URI? = runCatching { URI(value) }.getOrNull()?.takeIf {
            (it.scheme.equals("http", true) || it.scheme.equals("https", true)) &&
                !it.host.isNullOrBlank() && it.userInfo == null && safeValue(value)
        }
    }
}
