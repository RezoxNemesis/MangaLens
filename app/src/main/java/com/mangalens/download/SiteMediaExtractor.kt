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
                if (failure is InterruptedException || failure is java.util.concurrent.CancellationException || failure is OriginalHlsSelectedSourceException) throw failure
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
        val format = OriginalMediaFormatPolicy.selector(quality, allowSeparateStreams)
        val request = YoutubeDLRequest(url).apply {
            addOption("--ignore-config")
            addOption("--no-plugin-dirs")
            addOption("--no-remote-components")
            addOption("--no-geo-bypass")
            addOption("--no-playlist")
            addOption("--skip-download")
            // Inventory is never persisted; the parser rejects JSON over 16 MiB.
            // Only safe selected facts persist with the complete source tuple.
            addOption("--print", "%(.{url,protocol,ext,height,vcodec,acodec,format_id,title,duration,extractor_key,http_headers,has_drm,_type,requested_formats,formats,id,language,original_language,subtitles,automatic_captions,is_live,live_status,extra_param_to_segment_url,fragments,fragment_base_url,manifest_url,fragment_query,is_upcoming})j")
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
            addOption("--format-sort", OriginalMediaFormatPolicy.SORT)
            addOption("--format-sort-force")
            if (allowSeparateStreams) addOption("--audio-multistreams")
        }
        val processId = "mangalens-resolve-${UUID.randomUUID()}"
        val guard = MediaProcessGuard(session, processId, timeoutMs, YoutubeDL::destroyProcessById)
        val selected = try { guard.run {
            guard.checkActive()
            NativeOwnedExtractorTools.configure(app, request, session)
            guard.checkActive()
            AndroidNativeExtractorNetworking.configure(app, request, url, session::checkActive)
            guard.checkActive()
            val response = YoutubeDL.execute(request, processId = processId, callback = null)
            guard.checkActive()
            SiteMediaInfoParser.parse(response.out, url, allowSeparateStreams)?.let { media ->
                media.copy(originalSelection = media.originalSelection?.copy(client = youtubeClient ?: "default"))
            }
        } } finally {
            guard.close()
            browserCookies?.delete()
        }
        // The native extractor has actually returned before the typed finite-playlist adapter starts.
        return selected?.let { media -> OriginalHlsVodCapture.enrich(media, session) { actual ->
            android.webkit.CookieManager.getInstance().getCookie(actual)
        } }
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
        if (info.optString("_type") in setOf("playlist", "multi_video")) return null
        val formats = info.optJSONArray("requested_formats")
        val selectedHls = allowSeparateStreams && (formats?.length() ?: 0) > 1 &&
            (0 until requireNotNull(formats).length()).any { formats?.optJSONObject(it)?.optString("protocol")?.startsWith("m3u8") == true }
        if (info.optBoolean("has_drm")) {
            if (selectedHls) throw OriginalHlsSelectedSourceException.unsupported()
            return null
        }
        if (selectedHls && formats?.length() != 2) throw OriginalHlsSelectedSourceException.unsupported()
        if (allowSeparateStreams && formats?.length() == 2) {
            fun incompleteSelectedPair(): ResolvedMediaLink? {
                if (selectedHls) throw OriginalHlsSelectedSourceException.unsupported()
                return null
            }
            val parts = (0 until formats.length()).map { index ->
                formats.optJSONObject(index) ?: if (selectedHls) throw OriginalHlsSelectedSourceException.unsupported()
                else formats.getJSONObject(index)
            }
            if (parts.any { it.optBoolean("has_drm") }) {
                if (selectedHls) throw OriginalHlsSelectedSourceException.unsupported()
                return null
            }
            val video = parts.singleOrNull { it.optString("vcodec").let { codec -> codec.isNotBlank() && codec != "none" } } ?: return incompleteSelectedPair()
            val audio = parts.singleOrNull { it.optString("vcodec") == "none" && it.optString("acodec") != "none" } ?: return incompleteSelectedPair()
            val videoMime = selectedTrackMime(video, audio = false) ?: return incompleteSelectedPair()
            if (parts.any { protectedOrUnsupportedProtocol(it) }) {
                if (selectedHls) throw OriginalHlsSelectedSourceException.unsupported()
                return null
            }
            val videoUrl = video.optString("url").takeIf(::isWebUrl) ?: return incompleteSelectedPair()
            val audioUrl = audio.optString("url").takeIf(::isWebUrl) ?: return incompleteSelectedPair()
            val audioMime = selectedTrackMime(audio, audio = true) ?: return incompleteSelectedPair()
            val videoHls = CapturedHlsTrackSource.capture(info, video, videoMime)
            val audioHls = CapturedHlsTrackSource.capture(info, audio, audioMime)
            val videoFragments = fragmentPlan(info, video)
            val audioFragments = fragmentPlan(info, audio)
            return ResolvedMediaLink(videoUrl, videoMime, info.optString("extractor_key", "yt-dlp"),
                detectedHeight = heightHint(video), title = info.optString("title"),
                sourcePageUrl = sourcePage, headers = safeHeaders(info) + safeHeaders(video),
                audioUrl = audioUrl, audioHeaders = safeHeaders(info) + safeHeaders(audio),
                expectedDurationUs = durationUs(info), originalSelection = selection(info, video, audio),
                providerCaptions = ProviderCaptionDiscovery.fromMetadata(info, sourcePage, audio.optString("language")),
                audioMimeType = audioMime,
                videoFragments = videoFragments, audioFragments = audioFragments,
                videoHlsSource = videoHls, audioHlsSource = audioHls)
        }
        if (info.optString("vcodec") == "none" || info.optString("acodec") == "none") return null
        // Never substitute one of requested_formats: those commonly contain separate tracks.
        if (info.optJSONArray("requested_formats")?.length()?.let { it > 1 } == true) return null
        val url = info.optString("url").takeIf(::isWebUrl) ?: return null
        val protocol = info.optString("protocol")
        if (protectedOrUnsupportedProtocol(info)) return null
        val mime = when {
            protocol.startsWith("http_dash_segments") -> OriginalMediaFormatPolicy.videoMime(info.optString("ext")) ?: return null
            protocol.startsWith("m3u8") -> "application/x-mpegURL"
            protocol.contains("dash") || url.substringBefore('?').endsWith(".mpd", true) -> "application/dash+xml"
            else -> OriginalMediaFormatPolicy.videoMime(info.optString("ext")) ?: "video/mp4"
        }
        val headers = safeHeaders(info)
        return ResolvedMediaLink(
            url = url, mimeType = mime, provider = info.optString("extractor_key", "yt-dlp"),
            detectedHeight = heightHint(info),
            title = info.optString("title").takeIf { it.isNotBlank() },
            sourcePageUrl = sourcePage, headers = headers, expectedDurationUs = durationUs(info),
            originalSelection = selection(info, info, info),
            providerCaptions = ProviderCaptionDiscovery.fromMetadata(info, sourcePage, info.optString("language")),
            videoFragments = fragmentPlan(info, info)
        )
    }

    private fun selectedTrackMime(format: JSONObject, audio: Boolean): String? {
        val mime = when {
            format.optString("protocol").contains("dash") && !format.optString("protocol").startsWith("http_dash_segments") -> "application/dash+xml"
            audio -> MediaTransportMime.audioContainer(format.optString("ext"))
            else -> OriginalMediaFormatPolicy.videoMime(format.optString("ext"))
        }
        if (format.optString("protocol").startsWith("m3u8") && mime !in setOf("video/mp4", "audio/mp4"))
            throw OriginalHlsSelectedSourceException.unsupported()
        return mime
    }

    private fun fragmentPlan(info: JSONObject, format: JSONObject): OriginalFragmentPlan? {
        val captured = JSONObject(format.toString())
        if (info.optBoolean("is_live") || info.optString("live_status") in setOf("is_live", "is_upcoming")) captured.put("is_live", true)
        val extra = info.optString("extra_param_to_segment_url").takeIf { it.isNotBlank() && it != "null" }
        return OriginalFragmentPlan.capture(captured, durationUs(info), extra)
    }

    private fun protectedOrUnsupportedProtocol(info: JSONObject): Boolean =
        info.optString("protocol").let { it.contains("drm", true) || it.contains("rtmp", true) }

    private fun selection(info: JSONObject, video: JSONObject, audio: JSONObject): OriginalMediaSelection {
        val formats = info.optJSONArray("formats")
        val maximum = formats?.let { rows -> (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            if (row.optBoolean("has_drm") || row.optString("vcodec") == "none") null
            else heightHint(row)
        }.maxOrNull() }
        return OriginalMediaSelection(
            videoFormatId = OriginalMediaFormatPolicy.safeFormatId(video.optString("format_id")),
            audioFormatId = OriginalMediaFormatPolicy.safeFormatId(audio.optString("format_id")),
            videoCodec = OriginalMediaFormatPolicy.safeCodec(video.optString("vcodec")),
            audioCodec = OriginalMediaFormatPolicy.safeCodec(audio.optString("acodec")),
            maximumReportedHeight = maximum
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

