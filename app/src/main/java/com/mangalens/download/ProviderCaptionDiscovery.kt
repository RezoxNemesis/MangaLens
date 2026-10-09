package com.mangalens.download

import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import org.json.JSONObject

/** Discovery is optional: malformed caption metadata must not invalidate playable media. */
internal object ProviderCaptionDiscovery {
    private const val MAX_LANGUAGES = 128
    private const val MAX_FORMATS = 16
    private const val MAX_TRACKS = 128

    fun fromMetadata(info: JSONObject, sourcePage: String, selectedAudioLanguage: String? = null): ProviderCaptionInventory? {
        val original = normalizeCaptionLanguage(info.optString("original_language"))
            ?: normalizeCaptionLanguage(info.optString("language"))
        val videoId = info.optString("id").takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,100}")) }
        val duration = info.optDouble("duration", Double.NaN).takeIf { it.isFinite() && it > 0 && it <= 21_600 }
            ?.let { (it * 1000).toLong() }
        val found = ArrayList<ProviderCaptionTrack>()
        for ((key, kind) in listOf("subtitles" to ProviderCaptionKind.MANUAL, "automatic_captions" to ProviderCaptionKind.AUTOMATIC)) {
            val languages = info.optJSONObject(key) ?: continue
            if (languages.length() > MAX_LANGUAGES) return null
            for (rawLanguage in languages.keys().asSequence().toList().sorted()) {
                val formats = languages.optJSONArray(rawLanguage) ?: continue
                if (formats.length() > MAX_FORMATS) return null
                val explicitlyOriginal = kind == ProviderCaptionKind.AUTOMATIC && rawLanguage.endsWith("-orig", true)
                val language = normalizeCaptionLanguage(if (explicitlyOriginal) rawLanguage.dropLast(5) else rawLanguage) ?: continue
                for (index in 0 until formats.length()) {
                    val row = formats.optJSONObject(index) ?: continue
                    val format = when (row.optString("ext").lowercase(Locale.ROOT)) {
                        "vtt" -> ProviderCaptionFormat.VTT
                        "srt" -> ProviderCaptionFormat.SRT
                        "json3" -> ProviderCaptionFormat.JSON3
                        else -> continue
                    }
                    val url = row.optString("url")
                    if (!ProviderCaptionUrlPolicy.accepts(sourcePage, videoId, url, language)) continue
                    val track = ProviderCaptionTrack(url, language, kind, format, explicitlyOriginal)
                    if (track !in found) found += track
                    if (found.size > MAX_TRACKS) return null
                }
            }
        }
        if (found.isEmpty()) return null
        return ProviderCaptionInventory(sourcePage, videoId, original, normalizeCaptionLanguage(selectedAudioLanguage), duration, found).captureSnapshot()
    }

    fun select(inventory: ProviderCaptionInventory, requestedSourceLanguage: String): ProviderCaptionTrack? =
        candidates(inventory, requestedSourceLanguage).firstOrNull()

    fun candidates(inventory: ProviderCaptionInventory, requestedSourceLanguage: String): List<ProviderCaptionTrack> {
        val language = inventory.selectedAudioLanguage ?: inventory.originalLanguage
            ?: inventory.tracks.filter { it.kind == ProviderCaptionKind.AUTOMATIC && it.originalAutomatic }
                .map { it.language }.distinct().singleOrNull()
            ?: normalizeCaptionLanguage(requestedSourceLanguage.takeUnless { it.equals("auto", true) }) ?: return emptyList()
        val primary = language.substringBefore('-')
        return inventory.tracks.filter {
            it.language.substringBefore('-') == primary &&
                ProviderCaptionUrlPolicy.accepts(inventory.sourcePageUrl, inventory.videoId, it.url, it.language)
        }.sortedWith(compareBy<ProviderCaptionTrack>(
            { if (it.kind == ProviderCaptionKind.MANUAL) 0 else 1 },
            { if (it.language == language) 0 else 1 },
            { if (it.originalAutomatic) 0 else 1 },
            { it.format.ordinal }, { it.url }
        ))
    }
}

/** Caption authority is the captured source, not a later URL or an arbitrary extractor header. */
internal object ProviderCaptionUrlPolicy {
    fun accepts(sourcePage: String, videoId: String?, captionUrl: String, language: String? = null): Boolean {
        val page = webUri(sourcePage) ?: return false
        val caption = webUri(captionUrl) ?: return false
        if (caption.scheme != "https") return false
        val pageHost = page.host.lowercase(Locale.ROOT)
        val captionHost = caption.host.lowercase(Locale.ROOT)
        val youtube = pageHost == "youtu.be" || pageHost == "youtube.com" || pageHost.endsWith(".youtube.com")
        if (youtube) {
            if (youtubeVideoId(page) != videoId) return false
            if (captionHost != "youtube.com" && !captionHost.endsWith(".youtube.com")) return false
            if (caption.path != "/api/timedtext" || videoId.isNullOrBlank()) return false
            val query = query(caption) ?: return false
            if (query["v"] != videoId || !query["tlang"].isNullOrEmpty()) return false
            val urlLanguage = query["lang"]?.let(::normalizeCaptionLanguage)
            if (language != null && urlLanguage != null && urlLanguage.substringBefore('-') != language.substringBefore('-')) return false
        } else {
            // Additional CDN authorities require an explicit policy rather than trusting arbitrary metadata.
            if (pageHost != captionHost || page.port != caption.port || page.scheme != caption.scheme) return false
            val query = query(caption) ?: return false
            if (!query["tlang"].isNullOrEmpty()) return false
        }
        return true
    }

    private fun youtubeVideoId(page: URI): String? {
        val host = page.host.lowercase(Locale.ROOT)
        val path = page.path.orEmpty().trimEnd('/')
        val id = if (host == "youtu.be") path.removePrefix("/").takeIf { '/' !in it }
            else if (path == "/watch") query(page)?.get("v")
            else path.split('/').takeIf { it.size == 3 && it[1] in setOf("embed", "shorts", "live") }?.get(2)
        return id?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }
    }

    private fun webUri(value: String): URI? = runCatching {
        require(value.length in 1..16_384 && value.none { it.code < 32 || it.code == 127 })
        URI(value).takeIf { it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() &&
            it.rawUserInfo == null && it.rawFragment == null }
    }.getOrNull()

    private fun query(uri: URI): Map<String, String>? = runCatching {
        val values = LinkedHashMap<String, String>()
        for (part in uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }) {
            val key = URLDecoder.decode(part.substringBefore('='), "UTF-8")
            val value = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
            require(key !in values)
            values[key] = value
        }
        values
    }.getOrNull()
}
