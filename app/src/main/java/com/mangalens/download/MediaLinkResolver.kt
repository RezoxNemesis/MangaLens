package com.mangalens.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit
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
    val title: String? = null,
    val sourcePageUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val audioUrl: String? = null,
    val audioHeaders: Map<String, String> = emptyMap(),
    val requestedHeight: Int? = null
)

class MediaLinkResolver(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true).followSslRedirects(true)
        .callTimeout(45, TimeUnit.SECONDS).build(),
    private val siteExtractor: SiteMediaExtractor? = null
) {

    suspend fun resolveCancellable(input: String, quality: DownloadQuality = DownloadQuality.P2160): ResolvedMediaLink? =
        runInterruptible(Dispatchers.IO) { resolve(input, quality) }

    fun resolve(input: String, quality: DownloadQuality = DownloadQuality.P2160): ResolvedMediaLink? {
        val clean = input.trim()
        val inputUri = runCatching { URI(clean) }.getOrNull()
        require(inputUri?.scheme in setOf("http", "https") && !inputUri?.host.isNullOrBlank() && inputUri?.userInfo == null) {
            "Only HTTP(S) links without embedded credentials are supported."
        }
        val lower = clean.substringBefore("?").lowercase()
        // Video/watch pages are sent through yt-dlp before generic HTML scraping. This prevents a
        // poster, preview clip or advertising MP4 from winning merely because it appears first in
        // the markup. Generic scraping remains the bounded fallback when an extractor is stale.
        var extractorFailure: Exception? = null
        val provider = providerFor(clean)
        val pathLooksLikeVideo = inputUri.path.orEmpty().split('/').any {
            it.lowercase() in setOf("video", "videos", "watch", "reel", "reels", "embed", "player")
        }
        val dedicated = !isDirect(lower) && siteExtractor != null && (provider != "generic" || pathLooksLikeVideo)
        if (dedicated) {
            try { siteExtractor?.extract(clean, quality)?.let { return it } }
            catch (failure: Exception) {
                if (failure is InterruptedException) throw failure
                extractorFailure = failure
            }
        }

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

        val genericResult = try { client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body ?: return@use null
            val contentType = response.header("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
            if (contentType?.startsWith("video/") == true || contentType in setOf(
                    "application/vnd.apple.mpegurl", "application/x-mpegurl", "application/dash+xml")) {
                return@use ResolvedMediaLink(response.request.url.toString(), contentType, "direct")
            }
            val contentLength = body.contentLength()
            if (contentLength > MAX_HTML_BYTES) return@use null
            val source = body.source()
            // readUtf8(byteCount) requires exactly that many bytes and throws on ordinary short pages.
            // Request one byte beyond the limit so unknown-length/chunked responses stay bounded too.
            source.request(MAX_HTML_BYTES + 1L)
            if (source.buffer.size > MAX_HTML_BYTES) return@use null
            val html = source.readUtf8()
            val provider = providerFor(clean)
            val title = Regex("""(?is)<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)""")
                .find(html)?.groupValues?.getOrNull(1)?.let(::unescape)
                ?: Regex("""(?is)<title[^>]*>(.*?)</title>""").find(html)?.groupValues?.getOrNull(1)?.let(::stripTags)

            val candidates = linkedSetOf<String>()
            Regex("""(?is)<meta[^>]+property=["']og:(?:video(?::(?:url|secure_url))?|image)["'][^>]+content=["']([^"']+)""")
                .findAll(html).forEach { candidates += unescape(it.groupValues[1]) }
            Regex("""(?is)<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:(?:video(?::(?:url|secure_url))?|image)["']""")
                .findAll(html).forEach { candidates += unescape(it.groupValues[1]) }
            Regex("""(?is)<(?:video|source|img)[^>]+(?:src|data-src|data-original|poster)=["']([^"']+)""")
                .findAll(html).forEach { candidates += unescape(it.groupValues[1]) }
            // KVS-style sites such as Rule34Video expose the actual MP4 variants as download
            // anchors rather than <source> nodes. Keep these as playable candidates and preserve
            // the page Referer when Media3 requests them.
            Regex("""(?is)<a[^>]+href=["']([^"']+(?:download=true|\.mp4(?:\?|/))[^"']*)["'][^>]*>""")
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
                .filter { isDirect(it.substringBefore("?").lowercase()) && !isObviousAd(it) }
                .map {
                    ResolvedMediaLink(
                        it,
                        guessMime(it.substringBefore("?").lowercase()),
                        provider,
                        detectHeight(it),
                        title,
                        sourcePageUrl = response.request.url.toString(),
                        headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to response.request.url.toString(),
                            "Accept" to "*/*"
                        )
                    )
                }
                .toList()

            if (resolved.isEmpty()) return@use null
            val videoCandidates = resolved.filter {
                it.mimeType?.startsWith("video/") == true ||
                    it.mimeType == "application/x-mpegURL" ||
                    it.mimeType == "application/dash+xml"
            }
            val pool = if (videoCandidates.isNotEmpty()) videoCandidates else resolved

            pool.maxByOrNull { candidate ->
                val height = candidate.detectedHeight ?: 0
                val qualityFit = if (height <= quality.height) height * 1000 else -abs(height - quality.height)
                qualityFit + if (candidate.mimeType == "video/mp4") 50 else 0
            }
        } } catch (failure: java.io.IOException) {
            if (siteExtractor == null) throw failure
            null
        }
        val videoPage = runCatching { URI(clean).path.orEmpty() }.getOrDefault("")
            .split('/').any { it.lowercase() in setOf("video", "videos", "watch", "reel", "reels", "player") }
        // A social/adult player thumbnail is not a successful video download.
        val result = genericResult.takeUnless { videoPage && it?.mimeType?.startsWith("image/") == true }
        result?.let { return it }
        if (extractorFailure != null) throw extractorFailure
        return if (dedicated) null else siteExtractor?.extract(clean, quality)
    }

    private fun isObviousAd(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return true
        val host = uri.host.orEmpty().lowercase()
        if (listOf("doubleclick.net", "googlesyndication.com", "trafficjunky.net", "exoclick.com", "juicyads.com").any { matchesHost(host, it) }) return true
        return Regex("(?i)(?:^|[/_.-])(ads?|advertisement|preroll|pre-roll|vast)(?:$|[/_.-])").containsMatchIn(uri.path.orEmpty())
    }

    private fun providerFor(url: String): String {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        return when {
            matchesHost(host, "youtube.com") || host == "youtu.be" -> "youtube"
            matchesHost(host, "instagram.com") -> "instagram"
            matchesHost(host, "facebook.com") || matchesHost(host, "fb.watch") -> "facebook"
            matchesHost(host, "twitter.com") || matchesHost(host, "x.com") -> "x"
            matchesHost(host, "tiktok.com") -> "tiktok"
            matchesHost(host, "rule34video.com") -> "rule34video"
            matchesHost(host, "spankbang.com") -> "spankbang"
            matchesHost(host, "vimeo.com") -> "vimeo"
            else -> "generic"
        }
    }

    private fun matchesHost(host: String, domain: String) = host == domain || host.endsWith(".$domain")

    private fun detectHeight(url: String): Int? {
        val normalized = url.replace("%2F", "/").replace("%3A", ":")
        if (Regex("""(?i)(?:^|[/_.-])4k(?:60fps|30fps|p)?(?:$|[/_.?&#-])""").containsMatchIn(normalized)) return 2160
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
