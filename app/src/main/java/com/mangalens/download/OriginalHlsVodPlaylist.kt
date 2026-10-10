package com.mangalens.download

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Strict captured fMP4 VOD only; unsupported playlist semantics are never guessed. */
internal object OriginalHlsVodPlaylist {
    fun capture(source: CapturedHlsTrackSource, finalUrl: String, contentType: String, bytes: ByteArray,
        expectedDurationUs: Long, checkpoint: () -> Unit = {}): OriginalFragmentPlan {
        checkpoint(); source.validate()
        OriginalHlsPublicTransport.requireUrl(finalUrl)
        require(contentType in OriginalHlsVodPolicy.CONTENT_TYPES)
        require(bytes.size in 1..OriginalHlsVodPolicy.MAX_PLAYLIST_BYTES && expectedDurationUs in 1..OriginalFragmentPlan.MAX_DURATION_US)
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        require(text.count { it == '\n' } < OriginalHlsVodPolicy.MAX_PLAYLIST_LINES)
        val lines = text.split('\n')
        require(lines.size <= OriginalHlsVodPolicy.MAX_PLAYLIST_LINES && lines.first().removeSuffix("\r") == "#EXTM3U")
        // Reserve actual JSON UTF-8 as URI resolution expands each row; a long final base
        // must not allocate thousands of 8-KiB URLs before discovering the final plan cap.
        val metadata = JSONObject().put("version", OriginalFragmentPlan.HLS_VERSION).put("sourceUrl", source.sourceUrl)
            .put("formatId", source.formatId).put("mediaMime", source.mediaMime).put("durationUs", expectedDurationUs)
            .put("fragments", JSONArray()).put("hls", JSONObject().put("protocol", source.protocol)
                .put("playlistFinalUrl", finalUrl).put("playlistSha256", "0".repeat(64)).put("playlistContentType", contentType)
                .put("mediaSequence", Long.MAX_VALUE).put("observedDurationUs", Long.MAX_VALUE).put("initialization", JSONObject()))
        var reservedBytes = metadata.toString().toByteArray(Charsets.UTF_8).size.toLong()
        require(reservedBytes <= OriginalFragmentPlan.MAX_JSON_BYTES)
        val fragments = ArrayList<OriginalMediaFragment>()
        fun append(fragment: OriginalMediaFragment, initialization: Boolean = false) {
            val rowBytes = fragment.toJson().toString().toByteArray(Charsets.UTF_8).size.toLong()
            // The init is also stored in the receipt; its full row is a conservative upper bound.
            reservedBytes = Math.addExact(reservedBytes, Math.multiplyExact(rowBytes + 1L, if (initialization) 2L else 1L))
            require(reservedBytes <= OriginalFragmentPlan.MAX_JSON_BYTES) { "Resolved HLS plan exceeds its private storage limit." }
            fragments += fragment
        }
        var map: OriginalMediaFragment? = null
        var pendingDuration: BigDecimal? = null
        var pendingRange: String? = null
        var previousRangeUrl: String? = null
        var previousRangeEnd: Long? = null
        var mediaSequence = 0L
        var version: Int? = null
        var totalDuration = BigDecimal.ZERO
        var targetDuration: Long? = null
        var maximumRoundedDuration = 0L
        var end = false
        val seen = HashSet<String>()
        fun once(tag: String) { require(seen.add(tag)) { "Duplicate VOD playlist metadata is unsupported." } }
        fun resolve(value: String): String {
            require(value.isNotBlank() && value.length <= 8192 && value.none(Char::isISOControl))
            val resolved = URI(finalUrl).resolve(value).toString()
            require(OriginalHlsVodPolicy.safeUrl(resolved) && !(finalUrl.startsWith("https://") && resolved.startsWith("http://")))
            OriginalHlsPublicTransport.requireUrl(resolved)
            return resolved
        }
        for (raw in lines.drop(1)) {
            checkpoint()
            val line = raw.removeSuffix("\r")
            require(line.length <= OriginalHlsVodPolicy.MAX_LINE_CHARS && line.none { it == '\u0000' || it < ' ' && it != '\t' })
            if (line.isBlank()) continue
            if (end) { require(line.startsWith('#') && !line.startsWith("#EXT")); continue }
            when {
                line.startsWith("#EXTINF:") -> {
                    require(pendingDuration == null)
                    val value = line.removePrefix("#EXTINF:").substringBefore(',')
                    require(value.matches(Regex("[0-9]{1,5}(?:\\.[0-9]{1,9})?")))
                    val duration = BigDecimal(value)
                    require(duration.signum() > 0 && duration <= BigDecimal("21600"))
                    pendingDuration = duration
                }
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    require(pendingRange == null)
                    pendingRange = line.removePrefix("#EXT-X-BYTERANGE:")
                    range(pendingRange!!, null) // Validate digits/length; omitted offsets are resolved at the URI.
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    require(map == null && fragments.isEmpty() && pendingDuration == null && pendingRange == null)
                    val values = attributes(line.removePrefix("#EXT-X-MAP:"))
                    require(values.keys.all { it in setOf("URI", "BYTERANGE") } && "URI" in values)
                    val url = resolve(values.getValue("URI"))
                    val bounds = values["BYTERANGE"]?.let { range(it, null).also { parsed -> require(parsed.first != null) } }
                    map = OriginalMediaFragment(url, bounds?.first, bounds?.second, bounds?.let { it.second!! - it.first!! })
                    append(requireNotNull(map), initialization = true)
                }
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> {
                    once("sequence"); mediaSequence = unsigned(line.removePrefix("#EXT-X-MEDIA-SEQUENCE:"))
                }
                line.startsWith("#EXT-X-VERSION:") -> {
                    once("version"); version = unsigned(line.removePrefix("#EXT-X-VERSION:")).also { require(it in 6L..12L) }.toInt()
                }
                line.startsWith("#EXT-X-TARGETDURATION:") -> {
                    once("target"); targetDuration = unsigned(line.removePrefix("#EXT-X-TARGETDURATION:")).also { require(it in 1L..21600L) }
                }
                line.startsWith("#EXT-X-PLAYLIST-TYPE:") -> { once("type"); require(line == "#EXT-X-PLAYLIST-TYPE:VOD") }
                line == "#EXT-X-INDEPENDENT-SEGMENTS" -> once("independent")
                line.startsWith("#EXT-X-PROGRAM-DATE-TIME:") -> require(line.removePrefix("#EXT-X-PROGRAM-DATE-TIME:").isNotBlank())
                line == "#EXT-X-ENDLIST" -> { require(pendingDuration == null && pendingRange == null); end = true }
                line.startsWith("#EXT-X-") || line.startsWith("#EXT") -> error("Unsupported VOD playlist semantics.")
                line.startsWith('#') -> Unit
                else -> {
                    require(map != null && version != null && fragments.size < OriginalFragmentPlan.MAX_FRAGMENTS)
                    val duration = requireNotNull(pendingDuration)
                    val url = resolve(line)
                    val bounds = pendingRange?.let { encoded ->
                        val implicit = previousRangeEnd?.takeIf { previousRangeUrl == url }
                        range(encoded, implicit).also { require(it.first != null) { "Implicit media byte range has no same-URI predecessor." } }
                    }
                    append(OriginalMediaFragment(url, bounds?.first, bounds?.second, bounds?.let { it.second!! - it.first!! }, micros(duration)))
                    maximumRoundedDuration = maxOf(maximumRoundedDuration, duration.setScale(0, RoundingMode.HALF_UP).longValueExact())
                    totalDuration = totalDuration.add(duration)
                    require(totalDuration <= BigDecimal("21600"))
                    previousRangeUrl = if (bounds == null) null else url
                    previousRangeEnd = bounds?.second
                    pendingDuration = null; pendingRange = null
                }
            }
        }
        checkpoint()
        require(end && map != null && fragments.size >= 2 && pendingDuration == null && pendingRange == null && "target" in seen)
        require(maximumRoundedDuration <= requireNotNull(targetDuration))
        val receipt = OriginalHlsVodReceipt(source.protocol, finalUrl,
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, contentType, mediaSequence,
            micros(totalDuration), requireNotNull(map))
        return OriginalFragmentPlan(source.sourceUrl, source.formatId, source.mediaMime, expectedDurationUs,
            fragments, OriginalFragmentPlan.HLS_VERSION, receipt).captured().also { checkpoint() }
    }

    private fun micros(value: BigDecimal): Long = value.movePointRight(6).setScale(0, RoundingMode.HALF_UP).longValueExact()
        .also { require(it in 1..OriginalFragmentPlan.MAX_DURATION_US) }
    private fun unsigned(value: String): Long {
        require(value.matches(Regex("[0-9]{1,19}")))
        return value.toLongOrNull() ?: error("Playlist integer overflow.")
    }
    /** An init range never grants an implicit media offset. */
    private fun range(value: String, implicit: Long?): Pair<Long?, Long?> {
        require(value.matches(Regex("[0-9]{1,19}(?:@[0-9]{1,19})?")))
        val length = unsigned(value.substringBefore('@'))
        require(length in 1..OriginalFragmentPlan.MAX_FRAGMENT_BYTES)
        val start = if ('@' in value) unsigned(value.substringAfter('@')) else implicit
        val end = start?.let { Math.addExact(it, length) }
        return start to end
    }
    private fun attributes(value: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        var at = 0
        while (at < value.length) {
            val equal = value.indexOf('=', at); require(equal > at)
            val key = value.substring(at, equal); require(key.matches(Regex("[A-Z0-9-]+")) && key !in values)
            at = equal + 1; require(at < value.length)
            val item: String
            if (value[at] == '"') {
                val close = value.indexOf('"', at + 1); require(close > at + 1)
                item = value.substring(at + 1, close); at = close + 1
            } else {
                require(key !in setOf("URI", "BYTERANGE")) { "HLS map attributes require quoted strings." }
                val comma = value.indexOf(',', at).takeIf { it >= 0 } ?: value.length
                item = value.substring(at, comma); require(item.isNotEmpty()); at = comma
            }
            require(item.none(Char::isISOControl)); values[key] = item
            if (at < value.length) { require(value[at] == ','); at++; require(at < value.length) }
        }
        require(values.isNotEmpty()); return values
    }
}
