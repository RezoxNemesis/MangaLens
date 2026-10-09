package com.mangalens.download

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.math.abs

enum class DownloadQuality(val height: Int, val label: String) {
    BEST(10_000, "Best available"),
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
    val requestedHeight: Int? = null,
    val expectedDurationUs: Long? = null,
    val originalSelection: OriginalMediaSelection? = null,
    val providerCaptions: ProviderCaptionInventory? = null
)

class MediaLinkResolver(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true).followSslRedirects(true)
        .callTimeout(45, TimeUnit.SECONDS).build(),
    private val siteExtractor: SiteMediaExtractor? = null,
    private val timeoutMs: Long = 90_000L
) {

    suspend fun resolveCancellable(input: String, quality: DownloadQuality = DownloadQuality.BEST,
        budgetMs: Long = timeoutMs): ResolvedMediaLink? =
        MediaResolutionRunner.run(minOf(timeoutMs, budgetMs)) { session -> resolveWithin(input, quality, session) }

    fun resolve(input: String, quality: DownloadQuality = DownloadQuality.BEST): ResolvedMediaLink? =
        runBlocking { resolveCancellable(input, quality) }

    private fun extract(clean: String, quality: DownloadQuality, session: MediaResolutionSession): ResolvedMediaLink? {
        session.checkActive()
        val result = if (siteExtractor is YtDlpSiteMediaExtractor) siteExtractor.extractWithin(clean, quality, session)
            else siteExtractor?.extract(clean, quality)
        session.checkActive()
        return result
    }

    private fun resolveWithin(input: String, quality: DownloadQuality, session: MediaResolutionSession): ResolvedMediaLink? {
        session.checkActive()
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
            try { extract(clean, quality, session)?.let { return it } }
            catch (failure: Exception) {
                session.checkActive()
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

        session.checkActive()
        val call = client.newCall(request)
        call.timeout().deadlineNanoTime(session.deadlineNanos)
        val cancellation = session.onCancel { call.cancel() }
        val genericResult = try { call.execute().use { response ->
            session.checkActive()
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
            session.checkActive()
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
            session.checkActive()
            if (siteExtractor == null) throw failure
            null
        } finally {
            cancellation.close()
            call.cancel()
        }
        session.checkActive()
        val videoPage = runCatching { URI(clean).path.orEmpty() }.getOrDefault("")
            .split('/').any { it.lowercase() in setOf("video", "videos", "watch", "reel", "reels", "player") }
        // A social/adult player thumbnail is not a successful video download.
        val result = genericResult.takeUnless { (dedicated || videoPage) && it?.mimeType?.startsWith("image/") == true }
        result?.let { return it }
        if (extractorFailure != null) throw extractorFailure
        return if (dedicated) null else extract(clean, quality, session)
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

    private fun isDirect(value: String): Boolean {
        val clean = value.trimEnd('/')
        return clean.endsWith(".m3u8") || clean.endsWith(".mpd") || clean.endsWith(".mp4") ||
            clean.endsWith(".webm") || clean.endsWith(".mkv") || clean.endsWith(".mov") ||
            clean.endsWith(".jpg") || clean.endsWith(".jpeg") || clean.endsWith(".png") ||
            clean.endsWith(".webp") || clean.endsWith(".avif") || clean.endsWith(".gif") ||
            clean.endsWith(".bmp") || clean.endsWith(".heic")
    }

    private fun guessMime(value: String): String? {
        val clean = value.trimEnd('/')
        return when {
            clean.endsWith(".m3u8") -> "application/x-mpegURL"
            clean.endsWith(".mpd") -> "application/dash+xml"
            clean.endsWith(".mp4") -> "video/mp4"
            clean.endsWith(".webm") -> "video/webm"
            clean.endsWith(".mkv") -> "video/x-matroska"
            clean.endsWith(".mov") -> "video/quicktime"
            clean.endsWith(".jpg") || clean.endsWith(".jpeg") -> "image/jpeg"
            clean.endsWith(".png") -> "image/png"
            clean.endsWith(".webp") -> "image/webp"
            clean.endsWith(".avif") -> "image/avif"
            clean.endsWith(".gif") -> "image/gif"
            clean.endsWith(".bmp") -> "image/bmp"
            clean.endsWith(".heic") -> "image/heic"
            else -> null
        }
    }

    companion object {
        private const val MAX_HTML_BYTES = 8L * 1024L * 1024L
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 Chrome/140.0.0.0 Mobile Safari/537.36 MangaLens/13"
    }
}

