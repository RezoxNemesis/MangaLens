package com.mangalens.download

import org.json.JSONObject
import java.net.URI

/** Actual selected extractor facts are hints until both finite playlists are captured. */
data class CapturedHlsTrackSource(val sourceUrl: String, val formatId: String, val mediaMime: String, val protocol: String) {
    internal fun validate() {
        require(protocol in PROTOCOLS && mediaMime in setOf("video/mp4", "audio/mp4"))
        require(OriginalMediaFormatPolicy.safeFormatId(formatId) == formatId)
        require(OriginalHlsVodPolicy.safeUrl(sourceUrl))
    }
    companion object {
        internal val PROTOCOLS = setOf("m3u8", "m3u8_native")
        internal fun capture(info: JSONObject, format: JSONObject, mime: String): CapturedHlsTrackSource? {
            val protocol = format.optString("protocol")
            if (!protocol.startsWith("m3u8")) return null
            return try {
                require(protocol in PROTOCOLS && !info.optBoolean("has_drm") && !format.optBoolean("has_drm") &&
                    !info.optBoolean("is_live") && !format.optBoolean("is_live") && !info.optBoolean("is_upcoming") &&
                    info.optString("live_status") !in setOf("is_live", "is_upcoming", "post_live"))
                require(mime in setOf("video/mp4", "audio/mp4"))
                CapturedHlsTrackSource(format.getString("url"), format.getString("format_id"), mime, protocol).also { it.validate() }
            } catch (failure: Exception) {
                if (failure is java.util.concurrent.CancellationException || failure is InterruptedException) throw failure
                throw OriginalHlsSelectedSourceException.unsupported(failure)
            }
        }
    }
}

/** Observed source data; only the existing complete source/native gates can publish media. */
data class OriginalHlsVodReceipt(
    val protocol: String,
    val playlistFinalUrl: String,
    val playlistSha256: String,
    val playlistContentType: String,
    val mediaSequence: Long,
    val observedDurationUs: Long,
    val initialization: OriginalMediaFragment
) {
    internal fun validate(plan: OriginalFragmentPlan) {
        require(protocol in CapturedHlsTrackSource.PROTOCOLS)
        val final = OriginalHlsPublicTransport.requireUrl(playlistFinalUrl)
        val anchor = OriginalHlsPublicTransport.requireUrl(plan.sourceUrl)
        require(!anchor.isHttps || final.isHttps)
        plan.fragments.forEach { fragment ->
            val url = OriginalHlsPublicTransport.requireUrl(fragment.url)
            require(!final.isHttps || url.isHttps)
        }
        require(playlistSha256.matches(Regex("[a-f0-9]{64}")) && playlistContentType in OriginalHlsVodPolicy.CONTENT_TYPES)
        require(mediaSequence >= 0L && observedDurationUs in 1..OriginalFragmentPlan.MAX_DURATION_US)
        // A self-consistent shortened playlist is still incomplete relative to the selected source.
        // Bound metadata rounding by the existing native tail gate's maximum one-packet allowance.
        require(kotlin.math.abs(Math.subtractExact(observedDurationUs, plan.durationUs)) <=
            OriginalHlsVodPolicy.MAX_CAPTURED_DURATION_DIFFERENCE_US) {
            "Selected HLS playlist differs from the captured source duration. No complete download was created."
        }
        require(plan.mediaMime in setOf("video/mp4", "audio/mp4") && plan.fragments.size >= 2 && plan.fragments.first() == initialization)
        require(initialization.durationUs == null && plan.fragments.drop(1).all { it.durationUs != null })
        val declared = plan.fragments.drop(1).fold(0L) { n, f -> Math.addExact(n, requireNotNull(f.durationUs)) }
        require(kotlin.math.abs(Math.subtractExact(declared, observedDurationUs)) <= plan.fragments.size.toLong())
    }
    internal fun toJson() = JSONObject().put("protocol", protocol).put("playlistFinalUrl", playlistFinalUrl)
        .put("playlistSha256", playlistSha256).put("playlistContentType", playlistContentType).put("mediaSequence", mediaSequence)
        .put("observedDurationUs", observedDurationUs).put("initialization", JSONObject().put("url", initialization.url)
            .put("rangeStart", initialization.rangeStart ?: JSONObject.NULL).put("rangeEndExclusive", initialization.rangeEndExclusive ?: JSONObject.NULL)
            .put("expectedBytes", initialization.expectedBytes ?: JSONObject.NULL))
    companion object {
        internal fun fromJson(json: JSONObject): OriginalHlsVodReceipt {
            fun number(row: JSONObject, key: String): Long {
                val value = row.get(key)
                require(value is Number && value.toString().matches(Regex("[0-9]+")))
                return value.toString().toLong()
            }
            val init = json.getJSONObject("initialization")
            fun optional(key: String) = if (!init.has(key) || init.isNull(key)) null else number(init, key)
            return OriginalHlsVodReceipt(json.getString("protocol"), json.getString("playlistFinalUrl"), json.getString("playlistSha256"),
                json.getString("playlistContentType"), number(json, "mediaSequence"), number(json, "observedDurationUs"),
                OriginalMediaFragment(init.getString("url"), optional("rangeStart"), optional("rangeEndExclusive"), optional("expectedBytes")))
        }
    }
}

internal object OriginalHlsVodPolicy {
    const val MAX_CAPTURED_DURATION_DIFFERENCE_US = 250_000L
    const val MAX_PLAYLIST_BYTES = 512 * 1024
    const val MAX_PLAYLIST_LINES = 16_384
    const val MAX_LINE_CHARS = 16_384
    val CONTENT_TYPES = setOf("application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl")
    fun safeUrl(value: String) = value.length in 1..8192 && value.none(Char::isISOControl) && runCatching {
        val uri = URI(value)
        uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null
    }.getOrDefault(false)
}

/** A real selected HLS representation failed: generic HTML cannot replace this source. */
internal class OriginalHlsSelectedSourceException(val failure: MediaSourceFailure, cause: Throwable? = null) : java.io.IOException(failure.message, cause) {
    companion object {
        fun unsupported(cause: Throwable? = null) = OriginalHlsSelectedSourceException(MediaSourceFailure(
            MediaSourceFailureKind.UNSUPPORTED_TRANSPORT,
            "This selected HLS pair is outside finite unencrypted fMP4 VOD support. No complete download was created."), cause)
    }
}
