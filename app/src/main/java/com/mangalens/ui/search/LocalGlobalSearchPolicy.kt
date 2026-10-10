package com.mangalens.ui.search

import java.text.Normalizer
import java.util.Locale
import java.net.URI
import java.text.BreakIterator

/** Local user queries are data. They never become Orez instructions or SQL syntax. */
internal object LocalGlobalSearchPolicy {
    const val QUERY_CHARS = 160
    const val RESULTS_PER_SOURCE = 16
    const val LIBRARY_SCAN_LIMIT = 5_000
    const val SNIPPET_CHARS = 320

    fun query(value: String): String {
        val trimmed = value.trim()
        require(trimmed.length in 1..QUERY_CHARS && trimmed.none { it.code < 32 || it.code == 127 }) {
            "Enter a search of 1–160 characters."
        }
        return trimmed
    }

    fun key(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    fun matches(value: String, query: String): Boolean = key(value).contains(key(query))

    /** The caller binds this as a parameter to LIKE ... ESCAPE '\\'. */
    fun likePattern(query: String): String = "%" + query.flatMap { character ->
        if (character == '%' || character == '_' || character == '\\') listOf('\\', character) else listOf(character)
    }.joinToString("") + "%"

    private val addresses = Regex("""https?://[^\s<>()]+""", RegexOption.IGNORE_CASE)
    fun preview(value: String): String = addresses.replace(value) { match ->
        try {
            val uri = URI(match.value)
            if (uri.host.isNullOrBlank()) "[saved web address]"
            else URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toASCIIString()
        } catch (_: Exception) { "[saved web address]" }
    }

    fun snippet(value: String, query: String): String {
        val text = preview(value)
        val target = key(text).indexOf(key(query))
        // Map the normalized match back through source character clusters. Compatibility
        // expansions/combining marks can change lengths; offsets remain original UTF-16.
        var position = 0
        if (target >= 0) {
            val clusters = BreakIterator.getCharacterInstance(Locale.ROOT); clusters.setText(text)
            var start = clusters.first(); var end = clusters.next(); var normalized = 0
            while (end != BreakIterator.DONE) {
                val width = key(text.substring(start, end)).length
                if (normalized + width > target) { position = start; break }
                normalized += width; start = end; end = clusters.next()
            }
        }
        var start = (position - 60).coerceAtLeast(0)
        if (start > 0 && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start--
        var end = (start + SNIPPET_CHARS).coerceAtMost(text.length)
        if (end < text.length && end > start && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        return (if (start > 0) "…" else "") + text.substring(start, end) + (if (end < text.length) "…" else "")
    }
}
