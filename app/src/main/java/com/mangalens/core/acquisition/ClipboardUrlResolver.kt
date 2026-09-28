package com.mangalens.core.acquisition

import java.net.URI
import java.util.Locale

enum class MediaMode { MANGA, VIDEO, WEB }

data class UrlResolution(
    val input: String,
    val normalized: String?,
    val mode: MediaMode,
    val wasRelative: Boolean,
    val trustedBaseApplied: Boolean,
    val reason: String? = null
)

class ClipboardUrlResolver(
    private val trustedBases: Map<MediaMode, List<String>> = emptyMap()
) {
    fun resolve(raw: String, mode: MediaMode, activePageUrl: String? = null): UrlResolution {
        val input = raw.trim().replace(Regex("\\s+"), "")
        if (input.isBlank()) return UrlResolution(raw, null, mode, false, false, "Empty URL")

        normalizeAbsolute(input)?.let {
            return UrlResolution(raw, it, mode, false, false)
        }

        val base = activePageUrl?.let(::normalizeAbsolute)
            ?: trustedBases[mode].orEmpty().firstOrNull()
        if (base == null) {
            return UrlResolution(raw, null, mode, true, false, "A base URL is required")
        }

        val relative = input.removePrefix("./")
        val resolvedInput = if (relative.startsWith("/") || Regex("(?i)(^|/)chapter[-_/ ]?\\d+").containsMatchIn(relative)) "/" + relative.removePrefix("/") else relative
        val joined = runCatching { URI(base).resolve(resolvedInput)?.toString() }.getOrNull()
        val normalized = joined?.let(::normalizeAbsolute)
        return if (normalized != null) {
            UrlResolution(raw, normalized, mode, true, true)
        } else {
            UrlResolution(raw, null, mode, true, true, "Relative URL could not be resolved against the active page")
        }
    }

    fun resolveChapter(raw: String, mode: MediaMode = MediaMode.MANGA, activePageUrl: String? = null): String? =
        resolve(raw, mode, activePageUrl).normalized

    private fun normalizeAbsolute(value: String): String? {
        if (!value.startsWith("http://", true) && !value.startsWith("https://", true)) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (scheme !in setOf("http", "https") || host.isBlank()) return null
        val normalizedPath = uri.path?.replace(Regex("/+"), "/")?.let { if (it == "/") "" else it.trimEnd('/') }.orEmpty()
        return runCatching {
            URI(scheme, uri.userInfo, host, uri.port, normalizedPath, uri.query, null).toString().trimEnd('/')
        }.getOrNull()
    }
}
