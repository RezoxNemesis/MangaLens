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
        val request = YoutubeDLRequest(url).apply {
            addOption("--ignore-config")
            addOption("--no-playlist")
            addOption("--skip-download")
            // Restrict stdout to selected-stream metadata rather than every format/thumbnail/subtitle.
            addOption("--print", "%(.{url,protocol,ext,height,vcodec,acodec,title,extractor_key,http_headers,has_drm,_type,requested_formats})j")
            addOption("--no-warnings")
            addOption("--socket-timeout", "30")
            addOption("--retries", "3")
            addOption("--extractor-retries", "2")
            addOption("--user-agent", "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 Chrome/140.0.0.0 Mobile Safari/537.36")
            addOption("--extractor-args", "youtube:player_client=android_vr,android,web")
            // Prefer the requested ceiling, but always retain a no-height fallback. Some supported
            // sites (notably KVS-style pages) temporarily expose valid MP4 formats with unknown
            // resolution metadata; filtering only by [height<=N] made those pages appear unplayable.
            addOption("-f", if (allowSeparateStreams) {
                "bestvideo[height<=${quality.height}][ext=mp4]+bestaudio[ext=m4a]/" +
                    "bestvideo[height<=${quality.height}]+bestaudio/" +
                    "best[height<=${quality.height}]/" +
                    "bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best"
            } else {
                "best[height<=${quality.height}]/best"
            })
        }
        val processId = "mangalens-resolve-${UUID.randomUUID()}"
        val timeout = timer.schedule({ YoutubeDL.destroyProcessById(processId) }, 90, TimeUnit.SECONDS)
        return try {
            val response = YoutubeDL.execute(request, processId = processId, callback = null)
            SiteMediaInfoParser.parse(response.out, url, allowSeparateStreams)?.copy(requestedHeight = quality.height)
        } catch (failure: Exception) {
            if (failure is InterruptedException) throw failure
            throw IllegalArgumentException(
                "The site extractor could not find an accessible combined video and audio stream. " +
                    "Try opening the page in Web and using Open in Video; protected or login-only media may be unavailable.",
                failure
            )
        } finally {
            timeout.cancel(false)
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
                if (key.lowercase() in setOf("user-agent", "referer", "accept", "cookie") &&
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
