package com.mangalens.ui.web

import com.mangalens.orez.research.OrezResearchPublicNetworkPolicy
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

internal data class BrowserPublicLink(val address: String, val detail: String)

/** Public copy/share export never includes cookies, userinfo, signatures or generic query data. No DNS lookup. */
internal object BrowserPublicLinkPolicy {
    private val videoId = Regex("[A-Za-z0-9_-]{11}")
    private val publicTime = Regex("(?:[0-9]{1,6}|(?:[0-9]{1,3}h)?(?:[0-9]{1,3}m)?(?:[0-9]{1,3}s)?)")
    fun prepare(source: String): BrowserPublicLink? = runCatching {
        require(BrowserDomPolicy.permittedAddress(source))
        val uri = URI(source); val host = uri.host.lowercase(Locale.ROOT)
        require('.' in host || ':' in host)
        require(host != "localhost" && listOf(".localhost", ".local", ".internal", ".lan", ".home", ".invalid", ".test").none(host::endsWith))
        // Strip delimiters without decoding/re-encoding the path (encoded chapter IDs stay functional).
        val publicBase = source.substringBefore('#').substringBefore('?')
        OrezResearchPublicNetworkPolicy.requireUrl(publicBase.toHttpUrl())
        val query = uri.rawQuery.orEmpty().split('&').filter { it.isNotBlank() }.map { item ->
            URLDecoder.decode(item.substringBefore('='), "UTF-8") to URLDecoder.decode(item.substringAfter('=', ""), "UTF-8")
        }
        val youtube = host in setOf("youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "www.youtube-nocookie.com")
        val path = uri.path.trimEnd('/')
        val id = when {
            host == "youtu.be" -> path.removePrefix("/").takeIf { '/' !in it }
            youtube && path == "/watch" -> query.filter { it.first == "v" }.singleOrNull()?.second
            youtube && Regex("/(?:shorts|embed|live)/[A-Za-z0-9_-]{11}").matches(path) -> path.substringAfterLast('/')
            else -> null
        }?.takeIf(videoId::matches)
        if (id != null) {
            val time = query.filter { it.first == "t" }.singleOrNull()?.second?.takeIf { it.isNotBlank() && publicTime.matches(it) }
            BrowserPublicLink("https://www.youtube.com/watch?v=$id" + time?.let { "&t=$it" }.orEmpty(),
                "Public YouTube video${if (time != null) " and time" else ""} link. Other query data and fragments are omitted.")
        } else BrowserPublicLink(publicBase, if (uri.rawQuery != null || uri.rawFragment != null)
            "Query data and fragments are omitted. This address may open a different view of the page." else "Public page address.")
    }.getOrNull()
}
