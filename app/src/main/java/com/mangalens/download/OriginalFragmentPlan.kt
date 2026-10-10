package com.mangalens.download

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.URI
import java.security.MessageDigest

/** One selected encoded representation; these facts are private request data, never diagnostics. */
data class OriginalMediaFragment(
    val url: String,
    val rangeStart: Long? = null,
    val rangeEndExclusive: Long? = null,
    val expectedBytes: Long? = null,
    val durationUs: Long? = null
) {
    internal fun toJson() = JSONObject().put("url", url)
        .put("rangeStart", rangeStart ?: JSONObject.NULL).put("rangeEndExclusive", rangeEndExclusive ?: JSONObject.NULL)
        .put("expectedBytes", expectedBytes ?: JSONObject.NULL).put("durationUs", durationUs ?: JSONObject.NULL)
}

/** Ordered initialization/media bytes from the actual extractor, with no synthetic timestamps. */
data class OriginalFragmentPlan(
    val sourceUrl: String,
    val formatId: String,
    val mediaMime: String,
    val durationUs: Long,
    val fragments: List<OriginalMediaFragment>,
    val version: String = VERSION,
    val hls: OriginalHlsVodReceipt? = null
) {
    internal fun validate() {
        require((version == VERSION && hls == null || version == HLS_VERSION && hls != null) && safeUrl(sourceUrl)) { "Selected fragment source is invalid." }
        require(OriginalMediaFormatPolicy.safeFormatId(formatId) == formatId) { "Selected fragment format is invalid." }
        require(mediaMime in setOf("video/mp4", "video/webm", "audio/mp4", "audio/webm")) { "Fragment container is unsupported." }
        require(durationUs in 1..MAX_DURATION_US && fragments.size in 1..MAX_FRAGMENTS) { "A complete finite fragment sequence is required." }
        fragments.forEach { f ->
            require(safeUrl(f.url)) { "Selected fragment address is invalid." }
            require((f.rangeStart == null) == (f.rangeEndExclusive == null)) { "Fragment byte range is incomplete." }
            if (f.rangeStart != null) require(f.rangeStart >= 0L && requireNotNull(f.rangeEndExclusive) > f.rangeStart && f.rangeEndExclusive - f.rangeStart <= MAX_FRAGMENT_BYTES) { "Fragment byte range is invalid." }
            require(f.expectedBytes == null || f.expectedBytes in 1..MAX_FRAGMENT_BYTES) { "Fragment length is invalid." }
            require(f.durationUs == null || f.durationUs in 1..MAX_DURATION_US) { "Fragment duration is invalid." }
            if (f.rangeStart != null && f.expectedBytes != null) require(f.expectedBytes == f.rangeEndExclusive!! - f.rangeStart) { "Fragment length differs from its range." }
        }
        hls?.validate(this)
        require(toJsonUnchecked().toString().toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) { "Selected fragment sequence exceeds its private storage limit." }
    }

    internal fun captured(): OriginalFragmentPlan = copy(fragments = java.util.Collections.unmodifiableList(fragments.toList())).also { it.validate() }
    internal fun sha256(): String {
        validate()
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            fun text(value: String) { val b = value.toByteArray(Charsets.UTF_8); output.writeInt(b.size); output.write(b) }
            fun optional(value: Long?) { output.writeBoolean(value != null); value?.let(output::writeLong) }
            text(version); text(sourceUrl); text(formatId); text(mediaMime); output.writeLong(durationUs); output.writeInt(fragments.size)
            fragments.forEach { f -> text(f.url); optional(f.rangeStart); optional(f.rangeEndExclusive); optional(f.expectedBytes); optional(f.durationUs) }
            // Append only for the new version: existing DASHv1 hashes remain byte-for-byte unchanged.
            hls?.let { receipt ->
                text(receipt.protocol); text(receipt.playlistFinalUrl); text(receipt.playlistSha256); text(receipt.playlistContentType)
                output.writeLong(receipt.mediaSequence); output.writeLong(receipt.observedDurationUs)
                val init = receipt.initialization
                text(init.url); optional(init.rangeStart); optional(init.rangeEndExclusive); optional(init.expectedBytes); optional(init.durationUs)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    internal fun toJson(): JSONObject { validate(); return toJsonUnchecked() }
    private fun toJsonUnchecked() = JSONObject().put("version", version).put("sourceUrl", sourceUrl)
        .put("formatId", formatId).put("mediaMime", mediaMime).put("durationUs", durationUs)
        .put("fragments", JSONArray().also { rows -> fragments.forEach { f -> rows.put(f.toJson()) } })
        .also { json -> hls?.let { json.put("hls", it.toJson()) } }

    companion object {
        const val VERSION = "original-dash-fragment-sequence-v1"
        const val HLS_VERSION = "original-hls-fmp4-vod-fragment-sequence-v1"
        const val MAX_FRAGMENTS = 4096
        const val MAX_JSON_BYTES = 480 * 1024
        const val MAX_FRAGMENT_BYTES = 1024L * 1024L * 1024L
        const val MAX_DURATION_US = 21_600_000_000L
        internal fun capture(format: JSONObject, expectedDurationUs: Long?, extraParameters: String? = null): OriginalFragmentPlan? {
            val protocol = format.optString("protocol")
            if (!protocol.startsWith("http_dash_segments")) return null
            require(protocol == "http_dash_segments" && !format.optBoolean("has_drm") && !format.optBoolean("is_live")) { "Protected or live fragmented media cannot be saved as a complete video." }
            val duration = expectedDurationUs?.takeIf { it in 1..MAX_DURATION_US }
                ?: throw IllegalArgumentException("A finite duration is required for complete fragmented media.")
            val rows = format.optJSONArray("fragments") ?: throw IllegalArgumentException("Selected fragment sequence is missing.")
            require(rows.length() in 1..MAX_FRAGMENTS) { "Selected fragment sequence exceeds the supported bound." }
            val base = format.optString("fragment_base_url").takeIf { it.isNotBlank() }
            val fragments = (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                require(!row.has("range") && !row.has("key") && !row.has("decrypt_info")) { "Selected fragment requires unsupported transfer semantics." }
                val url = row.optString("url").takeIf { it.isNotBlank() } ?: run {
                    require(!base.isNullOrBlank() && safeUrl(base)) { "Relative fragment has no valid captured base." }
                    val path = row.optString("path").takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("Selected fragment has no address.")
                    URI(base).resolve(path).toString()
                }
                if (row.has("byte_range") && !row.isNull("byte_range")) require(row.get("byte_range") is JSONObject) { "Fragment byte range is invalid." }
                val range = row.optJSONObject("byte_range")
                val start = range?.let { strictLong(it, "start") }
                val end = range?.let { strictLong(it, "end") }
                val bytes = if (row.has("filesize") && !row.isNull("filesize")) strictLong(row, "filesize") else null
                val durationUs = if (row.has("duration") && !row.isNull("duration")) {
                    val value = row.getDouble("duration")
                    require(value.isFinite() && value > 0.0 && value <= MAX_DURATION_US / 1_000_000.0) { "Fragment duration is invalid." }
                    (value * 1_000_000.0).toLong()
                } else null
                val extra = format.optString("extra_param_to_segment_url").takeIf { it.isNotBlank() && it != "null" } ?: extraParameters
                OriginalMediaFragment(applyExtraParameters(url, extra), start, end, bytes, durationUs)
            }
            val video = format.optString("vcodec").let { it.isNotBlank() && it != "none" }
            val mime = if (video) OriginalMediaFormatPolicy.videoMime(format.optString("ext"))
                else MediaTransportMime.audioContainer(format.optString("ext"))
            return OriginalFragmentPlan(format.getString("url"), format.getString("format_id"),
                requireNotNull(mime) { "Fragment container is unsupported." }, duration, fragments).also { it.validate() }
        }
        internal fun fromJson(json: JSONObject): OriginalFragmentPlan {
            require(json.toString().toByteArray(Charsets.UTF_8).size <= MAX_JSON_BYTES) { "Saved fragment sequence exceeds its limit." }
            val rows = json.getJSONArray("fragments")
            require(rows.length() in 1..MAX_FRAGMENTS) { "Saved fragment count is invalid." }
            val fragments = (0 until rows.length()).map { i -> val f = rows.getJSONObject(i)
                fun optional(key: String) = if (f.has(key) && !f.isNull(key)) strictLong(f, key) else null
                OriginalMediaFragment(f.getString("url"), optional("rangeStart"), optional("rangeEndExclusive"), optional("expectedBytes"), optional("durationUs"))
            }
            return OriginalFragmentPlan(json.getString("sourceUrl"), json.getString("formatId"), json.getString("mediaMime"),
                strictLong(json, "durationUs"), fragments, json.getString("version"),
                if (json.has("hls") && !json.isNull("hls")) OriginalHlsVodReceipt.fromJson(json.getJSONObject("hls")) else null).captured()
        }
        private fun strictLong(json: JSONObject, key: String): Long {
            require(json.has(key) && !json.isNull(key)) { "Selected fragment numeric field is missing." }
            val value = json.get(key)
            require(value is Number && value.toString().matches(Regex("-?[0-9]+"))) { "Selected fragment numeric field is invalid." }
            return value.toString().toLongOrNull() ?: throw IllegalArgumentException("Selected fragment numeric field is out of range.")
        }
        /** Match yt-dlp update_url_query's decoded, ordered multivalue merge for this explicit field. */
        private fun applyExtraParameters(url: String, extra: String?): String {
            if (extra.isNullOrBlank()) return url
            require(extra.length <= 4096 && extra.none(Char::isISOControl) && safeUrl(url)) { "Fragment continuation parameters are invalid." }
            val values = linkedMapOf<String, List<String>>()
            fun decode(value: String) = java.net.URLDecoder.decode(value, "UTF-8")
            fun parse(query: String): Map<String, List<String>> {
                val parsed = linkedMapOf<String, MutableList<String>>()
                query.split('&').filter { it.isNotEmpty() }.forEach { pair ->
                    val key = decode(pair.substringBefore('=')); val value = decode(pair.substringAfter('=', ""))
                    if (value.isNotEmpty()) parsed.getOrPut(key) { mutableListOf() }.add(value)
                }
                return parsed
            }
            URI(url).rawQuery?.let { values.putAll(parse(it)) }; values.putAll(parse(extra))
            fun encode(value: String): String = buildString {
                value.toByteArray(Charsets.UTF_8).forEach { byte -> val n = byte.toInt() and 255
                    when {
                        n == 32 -> append('+')
                        n in 65..90 || n in 97..122 || n in 48..57 || n in listOf(45, 46, 95, 126) -> append(n.toChar())
                        else -> append('%').append("%02X".format(n))
                    }
                }
            }
            val query = values.entries.flatMap { (key, rows) -> rows.map { encode(key) + "=" + encode(it) } }.joinToString("&")
            return (url.substringBefore('?') + if (query.isEmpty()) "" else "?$query").also { require(safeUrl(it)) { "Fragment continuation address is invalid." } }
        }
        private fun safeUrl(value: String): Boolean = value.length in 1..8192 && value.none(Char::isISOControl) && runCatching {
            URI(value).let { it.scheme in setOf("https", "http") && !it.host.isNullOrBlank() && it.userInfo == null && it.fragment == null }
        }.getOrDefault(false)
    }
}

/** The private plan is part of one exact accepted source tuple, not a reusable URL hint. */
internal object OriginalFragmentTransport {
    private const val ROW_PREFIX = "original-fragments-v1:"
    fun isFragmentRow(value: String?): Boolean = value?.matches(Regex(Regex.escape(ROW_PREFIX) + "[a-f0-9]{64}")) == true
    fun downloadKind(media: ResolvedMediaLink?): String? {
        if (media == null || media.videoFragments == null && media.audioFragments == null) return null
        val video = capture(media.videoFragments, media.url, media.mimeType)
        val audio = capture(media.audioFragments, media.audioUrl, media.audioMimeType)
        val digest = MessageDigest.getInstance("SHA-256").digest(identitySuffix(video, audio).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return ROW_PREFIX + digest
    }
    fun requireDownloadBinding(kind: String?, media: ResolvedMediaLink?) {
        require(kind == null || isFragmentRow(kind)) { "Saved media transport is unsupported. Resolve the source again." }
        require(kind == downloadKind(media)) { "Saved media transport differs from its complete source receipt. Resolve the source again." }
    }

    fun capture(plan: OriginalFragmentPlan?, url: String?, mime: String?): OriginalFragmentPlan? = plan?.captured()?.also {
        require(url != null && it.sourceUrl == url && MediaTransportMime.capture(mime) == it.mediaMime) {
            "Selected fragment sequence differs from its captured media source."
        }
    }
    fun identitySuffix(video: OriginalFragmentPlan?, audio: OriginalFragmentPlan?): String = buildString {
        video?.let { append("video-fragments=").append(it.version).append(':').append(it.sha256()).append('\n') }
        audio?.let { append("audio-fragments=").append(it.version).append(':').append(it.sha256()).append('\n') }
    }
}
