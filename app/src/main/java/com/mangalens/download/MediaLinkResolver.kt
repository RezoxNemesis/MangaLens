package com.mangalens.download

import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import kotlin.math.abs

enum class DownloadQuality(val height: Int, val label: String) {
    P480(480, "480p"),
    P720(720, "720p"),
    P1080(1080, "1080p"),
    P1440(1440, "1440p"),
    P2160(2160, "4K");

    companion object { val selectable = values().toList() }
}

data class ResolvedMediaLink(
    val url: String,
    val mimeType: String?,
    val provider: String = "generic",
    val detectedHeight: Int? = null,
    val title: String? = null
)

class MediaLinkResolver {
    private val client = OkHttpClient.Builder().followRedirects(true).followSslRedirects(true).build()

    fun resolve(input: String, quality: DownloadQuality = DownloadQuality.P2160): ResolvedMediaLink? {
        val clean = input.trim()
        require(clean.startsWith("http://") || clean.startsWith("https://")) { "Only HTTP(S) links are supported." }
        val lower = clean.substringBefore("?").lowercase()

        if (isDirect(lower)) {
            return ResolvedMediaLink(
                clean,
                guessMime(lower),
                "direct",
                detectHeight(clean),
                clean.substringAfterLast('/').substringBefore('?').ifBlank { null }
            )
        }

        val request = Request.Builder()
            .url(clean)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/json,*/*")
            .header("Accept-Language", "en-US,en;q=0.9,hi;q=0.8")
            .header("Referer", clean)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body ?: return null
            val contentLength = body.contentLength()
            if (contentLength > MAX_HTML_BYTES) return null
            val html = body.source().readUtf8(MAX_HTML_BYTES)
            val provider = providerFor(clean)
            val title = Regex("""(?is)<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)""")
                .find(html)?.groupValues?.getOrNull(1)?.let(::unescape)
                ?: Regex("""(?is)<title[^>]*>(.*?)</title>""").find(html)?.groupValues?.getOrNull(1)?.let(::stripTags)

            val candidates = linkedSetOf<String>()
            Regex("""(?is)<meta[^>]+property=["']og:(?:video|image)["'][^>]+content=["']([^"']+)""")
                .findAll(html).forEach { candidates += unescape(it.groupValues[1]) }
            Regex("""(?is)<(?:video|source|img)[^>]+(?:src|data-src|data-original|poster)=["']([^"']+)""")
                .findAll(html).forEach { candidates += unescape(it.groupValues[1]) }
            Regex("""(?is)srcset=["']([^"']+)["']""")
                .findAll(html).forEach { match ->
                    match.groupValues[1].split(',').forEach { entry ->
                        candidates += unescape(entry.trim().substringBefore(' '))
                    }
                }
            Regex("""(?i)["'](?:video|image|contentUrl|playbackUrl|playback_url)["']\s*:\s*["']([^"']+)""")
                .findAll(html).forEach { candidates += unescape(it.groupValues[1]) }
            Regex("""(?is)(?:video_url|contentUrl|playbackUrl|playback_url|content_url|file)["']?\s*[:=]\s*["']([^"']+)""")
                .findAll(html).forEach { candidates += unescape(it.groupValues[1]) }
            Regex("""(?i)https?://[^\s<>"']+\.(?:m3u8|mpd|mp4|webm|mkv|mov|jpg|jpeg|png|webp|avif|gif|bmp|heic)(?:\?[^\s<>"']*)?""")
                .findAll(html).forEach { candidates += unescape(it.value) }

            val resolved = candidates.mapNotNull { normalize(it, clean) }
                .filter { isDirect(it.substringBefore("?").lowercase()) }
                .map {
                    ResolvedMediaLink(
                        it,
                        guessMime(it.substringBefore("?").lowercase()),
                        provider,
                        detectHeight(it),
                        title
                    )
                }
                .toList()

            if (resolved.isEmpty()) return null
            val videoCandidates = resolved.filter {
                it.mimeType?.startsWith("video/") == true ||
                    it.mimeType == "application/x-mpegURL" ||
                    it.mimeType == "application/dash+xml"
            }
            val pool = if (videoCandidates.isNotEmpty()) videoCandidates else resolved

            return pool.maxByOrNull { candidate ->
                val height = candidate.detectedHeight ?: 0
                val qualityFit = if (height <= quality.height) height * 1000 else -abs(height - quality.height)
                qualityFit + if (candidate.mimeType == "video/mp4") 50 else 0
            }
        }
    }

    private fun providerFor(url: String): String {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return when {
            host.contains("youtube.com") || host == "youtu.be" -> "youtube"
            host.contains("instagram.com") -> "instagram"
            host.contains("facebook.com") || host.contains("fb.watch") -> "facebook"
            host.contains("twitter.com") || host.contains("x.com") -> "x"
            host.contains("tiktok.com") -> "tiktok"
            else -> "generic"
        }
    }

    private fun detectHeight(url: String): Int? {
        val normalized = url.replace("%2F", "/").replace("%3A", ":")
        val match = Regex("""(?i)(?:height|quality|resolution|res|size)[=_:-]?(2160|1440|1080|720|480)(?:p)?""").find(normalized)
            ?: Regex("""(?i)(2160|1440|1080|720|480)p""").find(normalized)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun unescape(value: String): String =
        value.replace("\\/", "/").replace("\\u0026", "&").replace("&amp;", "&").trim()

    private fun stripTags(value: String): String =
        value.replace(Regex("""(?is)<[^>]+>"""), " ").replace(Regex("""\s+"""), " ").trim()

    private fun normalize(value: String, base: String): String? =
        runCatching { URI(base).resolve(unescape(value)).toString() }
            .getOrNull()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

    private fun isDirect(value: String): Boolean =
        value.endsWith(".m3u8") || value.endsWith(".mpd") || value.endsWith(".mp4") ||
            value.endsWith(".webm") || value.endsWith(".mkv") || value.endsWith(".mov") ||
            value.endsWith(".jpg") || value.endsWith(".jpeg") || value.endsWith(".png") ||
            value.endsWith(".webp") || value.endsWith(".avif") || value.endsWith(".gif") ||
            value.endsWith(".bmp") || value.endsWith(".heic")

    private fun guessMime(value: String): String? = when {
        value.endsWith(".m3u8") -> "application/x-mpegURL"
        value.endsWith(".mpd") -> "application/dash+xml"
        value.endsWith(".mp4") -> "video/mp4"
        value.endsWith(".webm") -> "video/webm"
        value.endsWith(".mkv") -> "video/x-matroska"
        value.endsWith(".mov") -> "video/quicktime"
        value.endsWith(".jpg") || value.endsWith(".jpeg") -> "image/jpeg"
        value.endsWith(".png") -> "image/png"
        value.endsWith(".webp") -> "image/webp"
        value.endsWith(".avif") -> "image/avif"
        value.endsWith(".gif") -> "image/gif"
        value.endsWith(".bmp") -> "image/bmp"
        value.endsWith(".heic") -> "image/heic"
        else -> null
    }

    companion object {
        private const val MAX_HTML_BYTES = 8L * 1024L * 1024L
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 Chrome/140.0.0.0 Mobile Safari/537.36 MangaLens/13"
    }
}
