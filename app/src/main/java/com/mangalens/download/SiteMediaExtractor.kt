package com.mangalens.download

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A real local extractor, with no remote paid API and no authentication/DRM bypass. */
fun interface SiteMediaExtractor {
    fun extract(url: String, quality: DownloadQuality): ResolvedMediaLink?
}

class YtDlpSiteMediaExtractor(context: Context, private val allowSeparateStreams: Boolean = false) : SiteMediaExtractor {
    private val app = context.applicationContext

    override fun extract(url: String, quality: DownloadQuality): ResolvedMediaLink? = runBlocking {
        MediaResolutionRunner.run(90_000L) { extractWithin(url, quality, it) }
    }

    internal fun extractWithin(url: String, quality: DownloadQuality, session: MediaResolutionSession): ResolvedMediaLink? {
        session.checkActive()
        val extractorDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
            SiteExtractionPolicy.extractorBudgetMs(session.remainingMillis())
        )
        // Use the installed extractor immediately. The library's synchronous updater fetches
        // metadata without a network deadline and holds the init monitor, so it must never run
        // on the playback path. A stale extractor reports an actionable source-page fallback.
        BundledYtDlpRuntime.initialize(app, session::checkActive)
        session.checkActive()
        // Do not mix YouTube clients in one request. Mixed client URLs can be signed for a
        // different player and later fail with HTTP 403. Try the current yt-dlp default first
        // for full quality, then bounded single-client fallbacks.
        val failures = mutableListOf<Throwable>()
        for ((index, client) in SiteExtractionPolicy.clients(url).withIndex()) {
            session.checkActive()
            val remaining = minOf(
                session.remainingMillis(),
                TimeUnit.NANOSECONDS.toMillis(extractorDeadline - System.nanoTime())
            )
            if (remaining < 1_000L) break
            try {
                execute(url, quality, client, session, SiteExtractionPolicy.attemptBudgetMs(index, remaining))
                    ?.let { return it.copy(requestedHeight = quality.height) }
            } catch (failure: Exception) {
                session.checkActive()
                if (failure is InterruptedException) throw failure
                failures += failure
            }
        }
        throw MediaSourceException.fromFailures(failures)
    }

    private fun execute(
        url: String, quality: DownloadQuality, youtubeClient: String?,
        session: MediaResolutionSession, timeoutMs: Long
    ): ResolvedMediaLink? {
        session.checkActive()
        val browserCookies = browserCookieFile(url)
        val heightFilter = if (quality == DownloadQuality.BEST) "" else "[height<=${quality.height}]"
        val format = if (allowSeparateStreams) {
            "bestvideo$heightFilter[ext=mp4]+bestaudio[ext=m4a]/" +
                "bestvideo$heightFilter+bestaudio/" +
                "best$heightFilter/" +
                "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best"
        } else {
            "best$heightFilter/best"
        }
        val request = YoutubeDLRequest(url).apply {
            addOption("--ignore-config")
            addOption("--no-plugin-dirs")
            addOption("--no-remote-components")
            addOption("--no-geo-bypass")
            addOption("--no-playlist")
            addOption("--skip-download")
            addOption("--print", "%(.{url,protocol,ext,height,vcodec,acodec,title,duration,extractor_key,http_headers,has_drm,_type,requested_formats})j")
            addOption("--no-warnings")
            addOption("--socket-timeout", "8")
            addOption("--retries", "1")
            addOption("--extractor-retries", "1")
            addOption("--fragment-retries", "1")
            addOption("--user-agent", "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 Chrome/140.0.0.0 Mobile Safari/537.36")
            addOption("--referer", url)
            browserCookies?.let { addOption("--cookies", it.absolutePath) }
            if (!youtubeClient.isNullOrBlank()) {
                addOption("--extractor-args", "youtube:player_client=$youtubeClient")
            }
            addOption("-f", format)
        }
        val processId = "mangalens-resolve-${UUID.randomUUID()}"
        val guard = MediaProcessGuard(session, processId, timeoutMs, YoutubeDL::destroyProcessById)
        return try {
            session.checkActive()
            AndroidNativeExtractorNetworking.configure(app, request, url, session::checkActive)
            session.checkActive()
            val response = YoutubeDL.execute(request, processId = processId, callback = null)
            session.checkActive()
            SiteMediaInfoParser.parse(response.out, url, allowSeparateStreams)
        } finally {
            guard.close()
            browserCookies?.delete()
        }
    }

    /**
     * Export only cookies Android WebView would send to this exact site into a temporary
     * Netscape cookie file. It stays in app-private cache and is deleted after extraction.
     */
    private fun browserCookieFile(url: String): java.io.File? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val raw = runCatching { android.webkit.CookieManager.getInstance().getCookie(url) }
            .getOrNull()?.takeIf { it.isNotBlank() && it.length <= 64 * 1024 } ?: return null
        val rows = raw.split(';').mapNotNull { token ->
            val value = token.trim()
            val separator = value.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val name = value.substring(0, separator).trim()
            val cookieValue = value.substring(separator + 1).trim()
            if (name.isBlank() || name.any { it <= ' ' || it == ';' || it == '\t' } ||
                cookieValue.any { it == '\r' || it == '\n' || it == '\t' }) return@mapNotNull null
            listOf(
                host,
                "FALSE",
                "/",
                if (uri.scheme.equals("https", true)) "TRUE" else "FALSE",
                "0",
                name,
                cookieValue
            ).joinToString("\t")
        }
        if (rows.isEmpty()) return null
        val dir = java.io.File(app.cacheDir, "yt_dlp_session").apply { mkdirs() }
        return java.io.File(dir, "cookies-${UUID.randomUUID()}.txt").apply {
            writeText("# Netscape HTTP Cookie File\n" + rows.joinToString("\n") + "\n")
        }
    }

}

/** Kept separate from the native runtime so format/security decisions have JVM tests. */
internal object SiteMediaInfoParser {
    fun parse(json: String, sourcePage: String, allowSeparateStreams: Boolean = false): ResolvedMediaLink? {
        if (json.length > 16 * 1024 * 1024) return null
        val info = JSONObject(json)
        if (info.optString("_type") in setOf("playlist", "multi_video") || info.optBoolean("has_drm")) return null
        val formats = info.optJSONArray("requested_formats")
        if (allowSeparateStreams && formats?.length() == 2) {
            val parts = (0 until formats.length()).map { formats.getJSONObject(it) }
            if (parts.any { it.optBoolean("has_drm") }) return null
            val video = parts.singleOrNull { it.optString("vcodec") != "none" && it.optString("acodec") == "none" } ?: return null
            val audio = parts.singleOrNull { it.optString("vcodec") == "none" && it.optString("acodec") != "none" } ?: return null
            if (video.optString("ext") != "mp4" || audio.optString("ext") !in setOf("m4a", "mp4")) return null
            val videoUrl = video.optString("url").takeIf(::isWebUrl) ?: return null
            val audioUrl = audio.optString("url").takeIf(::isWebUrl) ?: return null
            return ResolvedMediaLink(videoUrl, "video/mp4", info.optString("extractor_key", "yt-dlp"),
                detectedHeight = heightHint(video), title = info.optString("title"),
                sourcePageUrl = sourcePage, headers = safeHeaders(info) + safeHeaders(video),
                audioUrl = audioUrl, audioHeaders = safeHeaders(info) + safeHeaders(audio),
                expectedDurationUs = durationUs(info))
        }
        if (info.optString("vcodec") == "none" || info.optString("acodec") == "none") return null
        // Never substitute one of requested_formats: those commonly contain separate tracks.
        if (info.optJSONArray("requested_formats")?.length()?.let { it > 1 } == true) return null
        val url = info.optString("url").takeIf(::isWebUrl) ?: return null
        val protocol = info.optString("protocol")
        if (protocol.contains("drm", true) || protocol.contains("rtmp", true)) return null
        val mime = when {
            protocol.startsWith("m3u8") -> "application/x-mpegURL"
            protocol.contains("dash") || url.substringBefore('?').endsWith(".mpd", true) -> "application/dash+xml"
            info.optString("ext") == "webm" -> "video/webm"
            info.optString("ext") == "mov" -> "video/quicktime"
            else -> "video/mp4"
        }
        val headers = safeHeaders(info)
        return ResolvedMediaLink(
            url = url, mimeType = mime, provider = info.optString("extractor_key", "yt-dlp"),
            detectedHeight = heightHint(info),
            title = info.optString("title").takeIf { it.isNotBlank() },
            sourcePageUrl = sourcePage, headers = headers, expectedDurationUs = durationUs(info)
        )
    }

    private fun durationUs(info: JSONObject): Long? = info.optDouble("duration", Double.NaN)
        .takeIf { it.isFinite() && it > 0.0 && it < Long.MAX_VALUE / 1_000_000.0 }
        ?.let { (it * 1_000_000.0).toLong() }

    private fun heightHint(info: JSONObject): Int? {
        info.optInt("height", 0).takeIf { it > 0 }?.let { return it }
        // Some site extractors historically exposed pixel resolution through yt-dlp's "quality"
        // field. Only accept values that are unmistakably video heights; never treat rank 1/2/3
        // quality preferences as pixels.
        return info.optInt("quality", 0).takeIf {
            it in setOf(240, 360, 480, 540, 720, 1080, 1440, 2160, 4320)
        }
    }

    private fun safeHeaders(info: JSONObject): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        info.optJSONObject("http_headers")?.let { values ->
            values.keys().forEach { key ->
                val value = values.optString(key)
                if (key.lowercase() in setOf("user-agent", "referer", "origin", "accept", "accept-language", "cookie") &&
                    value.length <= 16_384 && value.none { it < ' ' || it == '\u007f' }) headers[key] = value
            }
        }
        return headers
    }

    private fun isWebUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)
}

