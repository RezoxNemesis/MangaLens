package com.mangalens.download

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A real local extractor, with no remote paid API and no authentication/DRM bypass. */
fun interface SiteMediaExtractor {
    fun extract(url: String, quality: DownloadQuality): ResolvedMediaLink?
}

class YtDlpSiteMediaExtractor(context: Context, private val allowSeparateStreams: Boolean = false) : SiteMediaExtractor {
    private val app = context.applicationContext

    override fun extract(url: String, quality: DownloadQuality): ResolvedMediaLink? {
        YoutubeDL.init(app)
        refreshExtractorIfUseful(url)
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        val youtube = host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
        // Do not mix YouTube clients in one request. Mixed client URLs can be signed for a
        // different player and later fail with HTTP 403. Try the current yt-dlp default first
        // for full quality, then bounded single-client fallbacks.
        val clients: List<String?> = if (youtube) {
            // Current yt-dlp gives the default client first. Then try clients that can expose
            // ordinary HTTPS/DASH/HLS media without intentionally capping quality. Keep Android
            // last because YouTube periodically applies stricter token checks to that client.
            listOf(null, "tv", "web_embedded", "web_safari", "ios", "android")
        } else listOf(null)
        var lastFailure: Exception? = null
        clients.forEach { client ->
            try {
                execute(url, quality, client)?.let { return it.copy(requestedHeight = quality.height) }
            } catch (failure: Exception) {
                if (failure is InterruptedException) throw failure
                lastFailure = failure
            }
        }
        throw IllegalArgumentException(
            "The site extractor could not find an accessible video/audio source. MangaLens tried fresh extractor data and safe site-specific fallbacks; protected, private or login-only media may still be unavailable.",
            lastFailure
        )
    }

    private fun execute(url: String, quality: DownloadQuality, youtubeClient: String?): ResolvedMediaLink? {
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
            addOption("--no-playlist")
            addOption("--skip-download")
            addOption("--print", "%(.{url,protocol,ext,height,vcodec,acodec,title,extractor_key,http_headers,has_drm,_type,requested_formats})j")
            addOption("--no-warnings")
            addOption("--socket-timeout", "30")
            addOption("--retries", "3")
            addOption("--extractor-retries", "3")
            addOption("--fragment-retries", "3")
            addOption("--user-agent", "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 Chrome/140.0.0.0 Mobile Safari/537.36")
            addOption("--referer", url)
            browserCookies?.let { addOption("--cookies", it.absolutePath) }
            if (!youtubeClient.isNullOrBlank()) {
                addOption("--extractor-args", "youtube:player_client=$youtubeClient")
            }
            addOption("-f", format)
        }
        val processId = "mangalens-resolve-${UUID.randomUUID()}"
        val timeout = timer.schedule({ YoutubeDL.destroyProcessById(processId) }, 90, TimeUnit.SECONDS)
        return try {
            val response = YoutubeDL.execute(request, processId = processId, callback = null)
            SiteMediaInfoParser.parse(response.out, url, allowSeparateStreams)
        } finally {
            timeout.cancel(false)
            YoutubeDL.destroyProcessById(processId)
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

    /**
     * YouTube changes signatures frequently. Prefer the current yt-dlp nightly on Android because
     * extractor fixes often land before the next stable release. A failed update is non-fatal.
     */
    private fun refreshExtractorIfUseful(url: String) {
        val host = runCatching { URI(url).host.orEmpty().lowercase() }.getOrDefault("")
        val dynamicSite = host == "youtu.be" ||
            host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "instagram.com" || host.endsWith(".instagram.com") ||
            host == "x.com" || host.endsWith(".x.com") ||
            host == "twitter.com" || host.endsWith(".twitter.com") ||
            host == "tiktok.com" || host.endsWith(".tiktok.com") ||
            host == "facebook.com" || host.endsWith(".facebook.com") ||
            host == "rule34video.com" || host.endsWith(".rule34video.com") ||
            host == "spankbang.com" || host.endsWith(".spankbang.com")
        if (!dynamicSite) return
        val prefs = app.getSharedPreferences("mangalens_ytdlp", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val lastAttempt = prefs.getLong("nightly_update_attempt", 0L)
        if (now - lastAttempt < 12L * 60L * 60L * 1000L) return
        prefs.edit().putLong("nightly_update_attempt", now).apply()
        runCatching {
            YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel._NIGHTLY)
        }.onSuccess {
            prefs.edit().putLong("nightly_update_success", now).apply()
        }
    }

    companion object {
        private val timer = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "mangalens-extractor-timeout").apply { isDaemon = true }
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
                audioUrl = audioUrl, audioHeaders = safeHeaders(info) + safeHeaders(audio))
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
            sourcePageUrl = sourcePage, headers = headers
        )
    }

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
