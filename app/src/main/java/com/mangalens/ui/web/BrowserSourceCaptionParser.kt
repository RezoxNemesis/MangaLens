package com.mangalens.ui.web

import com.mangalens.download.ProviderCaptionDiscovery
import com.mangalens.download.ProviderCaptionFormat
import com.mangalens.download.ProviderCaptionInventory
import com.mangalens.download.ProviderCaptionKind
import com.mangalens.download.ProviderCaptionTrack
import com.mangalens.download.ProviderCaptionUrlPolicy
import com.mangalens.download.normalizeCaptionLanguage
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Typed admission of the current evaluateJavascript callback only. Callers must capture
 * and recheck the actual live WebView/tab/navigation owner; this parser never restores
 * authority from disk and does not establish a caption document or completion receipt.
 */
internal object BrowserSourceCaptionParser {
    private const val MAX_CALLBACK = 512 * 1024
    private const val MAX_URL = 16_384
    private const val MAX_TRACKS = 128
    private const val MAX_TRACK_URL_CHARS = 131_072
    private const val MAX_DURATION_MS = 21_600_000L
    private val opaqueIdentity = Regex("[a-f0-9]{32}")
    private val encodedYouTubeAudioKey = Regex("""([a-z]{2,3}(?:-[a-z0-9]{2,8}){0,3})\.[0-9]+""", RegexOption.IGNORE_CASE)

    fun capture(raw: String?, page: BrowserCaptionPageOwner): BrowserSourceCaptionScope? = attempt {
        require(page.tabId.length in 1..128 && page.tabId.none(::control))
        require(page.navigationEpoch > 0 && opaqueIdentity.matches(page.webViewToken))
        val pageUri = requireNotNull(httpsUri(page.pageUrl))
        val payload = requireNotNull(decode(raw))
        val sample = requireNotNull(sample(payload))
        require(sample.pageUrl == page.pageUrl)
        val binding = string(payload, "audioBinding", 32)
        val originalLanguage = optionalLanguage(payload, "originalLanguage")
        if (binding == "element-language") require(originalLanguage == sample.audioLanguage)

        val youtube = youtube(pageUri)
        val videoIdValue = payload.opt("videoId")
        require(videoIdValue == null || videoIdValue === JSONObject.NULL || videoIdValue is String)
        val videoId = (videoIdValue as? String)?.also { require(it.matches(Regex("[A-Za-z0-9_-]{11}"))) }
        if (youtube) require(videoId != null) else require(videoId == null)
        val rows = payload.opt("tracks") as? JSONArray ?: return@attempt null
        require(rows.length() in 1..MAX_TRACKS)
        var urlCharacters = 0
        val tracks = ArrayList<ProviderCaptionTrack>()
        for (index in 0 until rows.length()) {
            val row = rows.opt(index) as? JSONObject ?: return@attempt null
            val url = string(row, "url", MAX_URL)
            urlCharacters += url.length
            require(urlCharacters <= MAX_TRACK_URL_CHARS)
            val language = requireNotNull(normalizeCaptionLanguage(string(row, "language", 64)))
            val kind = when (string(row, "kind", 16)) {
                "MANUAL" -> ProviderCaptionKind.MANUAL
                "AUTOMATIC" -> ProviderCaptionKind.AUTOMATIC
                else -> return@attempt null
            }
            val format = when (string(row, "format", 16)) {
                "VTT" -> ProviderCaptionFormat.VTT
                "JSON3" -> ProviderCaptionFormat.JSON3
                else -> return@attempt null
            }
            val originalAutomatic = boolean(row, "originalAutomatic")
            require(originalAutomatic == (kind == ProviderCaptionKind.AUTOMATIC))
            if (!youtube) require(kind == ProviderCaptionKind.MANUAL && format == ProviderCaptionFormat.VTT)
            if (!noTranslatedLanguage(url) || !ProviderCaptionUrlPolicy.accepts(page.pageUrl, videoId, url, language)) continue
            val track = ProviderCaptionTrack(url, language, kind, format, originalAutomatic)
            if (track !in tracks) tracks += track
        }
        require(tracks.isNotEmpty())
        val selectedAudio = sample.audioLanguage.takeIf { binding == "observed-track" }
        val inventory = ProviderCaptionInventory(page.pageUrl, videoId, originalLanguage, selectedAudio,
            sample.durationMs, tracks).captureSnapshot()
        require(ProviderCaptionDiscovery.select(inventory, "auto") != null)
        BrowserSourceCaptionScope(page, sample, inventory)
    }

    fun clock(raw: String?): BrowserCaptionClockSample? = attempt {
        requireNotNull(sample(requireNotNull(decode(raw))))
    }

    private fun sample(payload: JSONObject): BrowserCaptionClockSample? = attempt {
        require(integer(payload, "schemaVersion") == 1L && string(payload, "status", 16) == "ready")
        require(!payload.has("reason") || payload.opt("reason") === JSONObject.NULL)
        val page = string(payload, "pageUrl", MAX_URL)
        val pageUri = requireNotNull(httpsUri(page))
        val documentNonce = string(payload, "documentNonce", 32)
        val elementId = string(payload, "elementId", 32)
        val sourceVersion = string(payload, "sourceVersion", 32)
        require(listOf(documentNonce, elementId, sourceVersion).all(opaqueIdentity::matches))
        // This finite-duration mode requires an actual populated currentSrc. A live
        // srcObject may truthfully have an empty currentSrc; that mode is not admitted.
        val currentSrc = string(payload, "currentSrc", MAX_URL)
        val audioKey = string(payload, "audioTrackKey", 256)
        val audioLanguage = requireNotNull(normalizeCaptionLanguage(string(payload, "audioLanguage", 64)))
        val binding = string(payload, "audioBinding", 32)
        require(binding == "observed-track" || binding == "element-language")
        if (binding == "element-language") require(audioKey == "element-lang:$audioLanguage")
        if (youtube(pageUri)) {
            require(youtubePageId(pageUri) != null)
            if (binding == "observed-track") {
                val encoded = encodedYouTubeAudioKey.matchEntire(audioKey)?.groupValues?.get(1)
                    ?.let(::normalizeCaptionLanguage)
                require(encoded == null || encoded == audioLanguage)
            }
        }
        val position = integer(payload, "currentTimeMs")
        val duration = integer(payload, "durationMs")
        val ready = integer(payload, "readyState")
        val rateValue = payload.opt("playbackRate") as? Number ?: return@attempt null
        val rate = rateValue.toDouble()
        require(duration in 1..MAX_DURATION_MS && position in 0..duration && ready in 1..4 &&
            rate.isFinite() && rate in .1..16.0)
        BrowserCaptionClockSample(page, documentNonce, elementId, sourceVersion, currentSrc, audioKey,
            audioLanguage, position, duration, rate, boolean(payload, "paused"), boolean(payload, "seeking"), ready.toInt())
    }

    private fun decode(raw: String?): JSONObject? = attempt {
        require(raw != null && raw.length in 1..MAX_CALLBACK && strictJson(raw))
        // evaluateJavascript serializes the returned JSON string once more. Direct objects
        // and a persisted JSON object are deliberately not another supported input mode.
        val outer = JSONTokener(raw).nextValue() as? String ?: return@attempt null
        require(outer.length in 1..MAX_CALLBACK && strictJson(outer))
        require(outer.trimStart().startsWith('{'))
        JSONObject(outer)
    }

    private fun string(objectValue: JSONObject, key: String, maximum: Int): String {
        val value = objectValue.opt(key) as? String ?: throw IllegalArgumentException()
        require(value.length in 1..maximum && value.none(::control))
        return value
    }

    private fun optionalLanguage(objectValue: JSONObject, key: String): String? {
        val value = objectValue.opt(key)
        if (value == null || value === JSONObject.NULL) return null
        return requireNotNull(normalizeCaptionLanguage(string(objectValue, key, 64)))
    }

    private fun boolean(objectValue: JSONObject, key: String): Boolean =
        objectValue.opt(key) as? Boolean ?: throw IllegalArgumentException()

    private fun integer(objectValue: JSONObject, key: String): Long = when (val value = objectValue.opt(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> throw IllegalArgumentException()
    }

    private fun httpsUri(value: String): URI? = attempt {
        require(value.length in 1..MAX_URL && value.none(::control))
        URI(value).also { require(it.scheme == "https" && !it.host.isNullOrBlank() && it.rawUserInfo == null && it.rawFragment == null) }
    }

    private fun youtube(uri: URI): Boolean {
        val host = uri.host.lowercase(Locale.ROOT)
        return host == "youtu.be" || host == "youtube.com" || host.endsWith(".youtube.com")
    }

    private fun youtubePageId(page: URI): String? = attempt {
        val path = page.path.orEmpty().trimEnd('/')
        val id = if (page.host.equals("youtu.be", true)) path.removePrefix("/").takeIf { '/' !in it }
        else if (path == "/watch") {
            val query = LinkedHashMap<String, String>()
            for (part in page.rawQuery.orEmpty().split('&').filter(String::isNotEmpty)) {
                val key = URLDecoder.decode(part.substringBefore('='), "UTF-8")
                require(key !in query)
                query[key] = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            }
            query["v"]
        } else path.split('/').takeIf { it.size == 3 && it[1] in setOf("embed", "shorts", "live") }?.get(2)
        id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    }

    private fun noTranslatedLanguage(value: String): Boolean = attempt {
        val uri = URI(value)
        uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).none {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") == "tlang"
        }
    } == true

    private fun control(char: Char): Boolean = char.code < 32 || char.code == 127

    private inline fun <T> attempt(block: () -> T?): T? = try { block() } catch (_: Exception) { null }

    /** org.json accepts single quotes/coercible tokens; first validate actual JSON grammar. */
    private fun strictJson(value: String): Boolean = attempt {
        StrictJson(value).validate()
        true
    } == true

    private class StrictJson(private val source: String) {
        private var at = 0
        fun validate() {
            whitespace()
            value(0)
            whitespace()
            require(at == source.length)
        }
        private fun value(depth: Int) {
            require(depth <= 8 && at < source.length)
            when (source[at]) {
                '{' -> objectValue(depth + 1)
                '[' -> arrayValue(depth + 1)
                '"' -> stringValue()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                '-', in '0'..'9' -> numberValue()
                else -> throw IllegalArgumentException()
            }
        }
        private fun objectValue(depth: Int) {
            consume('{'); whitespace()
            if (next('}')) return
            val keys = HashSet<String>()
            while (true) {
                require(at < source.length && source[at] == '"')
                val key = stringValue()
                require(key.length in 1..128 && keys.size < 32 && keys.add(key))
                whitespace(); consume(':'); whitespace(); value(depth); whitespace()
                if (next('}')) return
                consume(','); whitespace()
            }
        }
        private fun arrayValue(depth: Int) {
            consume('['); whitespace()
            if (next(']')) return
            var count = 0
            while (true) {
                require(++count <= MAX_TRACKS)
                value(depth); whitespace()
                if (next(']')) return
                consume(','); whitespace()
            }
        }
        private fun stringValue(): String {
            consume('"')
            val decoded = StringBuilder()
            while (at < source.length) {
                val char = source[at++]
                if (char == '"') {
                    val result = decoded.toString()
                    var index = 0
                    while (index < result.length) {
                        val item = result[index++]
                        if (item.isHighSurrogate()) require(index < result.length && result[index++].isLowSurrogate())
                        else require(!item.isLowSurrogate())
                    }
                    return result
                }
                require(char.code >= 32)
                if (char != '\\') decoded.append(char)
                else {
                    require(at < source.length)
                    when (val escape = source[at++]) {
                        '"', '\\', '/' -> decoded.append(escape)
                        'b' -> decoded.append('\b')
                        'f' -> decoded.append('\u000c')
                        'n' -> decoded.append('\n')
                        'r' -> decoded.append('\r')
                        't' -> decoded.append('\t')
                        'u' -> {
                            require(at + 4 <= source.length)
                            var code = 0
                            repeat(4) { code = (code shl 4) + requireNotNull(source[at++].digitToIntOrNull(16)) }
                            decoded.append(code.toChar())
                        }
                        else -> throw IllegalArgumentException()
                    }
                }
            }
            throw IllegalArgumentException()
        }
        private fun numberValue() {
            next('-')
            require(at < source.length)
            if (!next('0')) {
                require(source[at] in '1'..'9')
                at++
                while (at < source.length && source[at] in '0'..'9') at++
            }
            if (next('.')) digits()
            if (at < source.length && source[at] in "eE") {
                at++
                if (at < source.length && source[at] in "+-") at++
                digits()
            }
        }
        private fun digits() {
            val first = at
            while (at < source.length && source[at] in '0'..'9') at++
            require(at > first)
        }
        private fun literal(value: String) {
            require(source.regionMatches(at, value, 0, value.length))
            at += value.length
        }
        private fun consume(char: Char) { require(next(char)) }
        private fun next(char: Char): Boolean {
            if (at < source.length && source[at] == char) { at++; return true }
            return false
        }
        private fun whitespace() { while (at < source.length && source[at] in " \n\r\t") at++ }
    }
}
