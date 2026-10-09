package com.mangalens.core.translation

/** Bounded, source-bound Hindi evidence in the existing translation-memory text column. */
object TranslationMemoryCodec {
    private const val PREFIX = "\u001eML-HI-LATN:1:"
    private const val MAX_FIELD_CHARS = 8000
    private const val MAX_ENCODED_CHARS = MAX_FIELD_CHARS * 3 + 100

    fun encode(source: String, draft: TranslationDraft, targetLanguage: String): String {
        require(TranslationQualityPolicy.isUsable(source, draft.text, targetLanguage, draft.hindiDraft)) {
            "Translation memory failed quality checks."
        }
        val proof = draft.hindiDraft ?: return draft.text.trim()
        val capturedSource = source.trim()
        require(HindiRomanization.isTarget(targetLanguage))
        require(listOf(capturedSource, proof, draft.text).all { it.length in 1..MAX_FIELD_CHARS })
        return buildString {
            append(PREFIX)
            for (field in listOf(capturedSource, "hi-latn", proof, draft.text)) {
                append(field.length).append(':').append(field)
            }
        }
    }

    fun decode(source: String, cached: String, targetLanguage: String): TranslationDraft? {
        if (!cached.startsWith(PREFIX)) {
            if (cached.startsWith("\u001eML-HI-LATN:") || cached.length !in 1..MAX_FIELD_CHARS) return null
            return TranslationDraft(cached).takeIf { TranslationQualityPolicy.isUsable(source, it.text, targetLanguage) }
        }
        if (!HindiRomanization.isTarget(targetLanguage) || cached.length > MAX_ENCODED_CHARS) return null
        var offset = PREFIX.length
        fun field(): String? {
            val end = cached.indexOf(':', offset)
            if (end !in offset + 1..offset + 5) return null
            val lengthText = cached.substring(offset, end)
            if (lengthText.any { !it.isDigit() }) return null
            val length = lengthText.toIntOrNull()?.takeIf { it in 1..MAX_FIELD_CHARS } ?: return null
            val start = end + 1
            if (length > cached.length - start) return null
            return cached.substring(start, start + length).also { offset = start + length }
        }
        val capturedSource = field() ?: return null
        val capturedTarget = field() ?: return null
        val proof = field() ?: return null
        val text = field() ?: return null
        if (offset != cached.length || capturedSource != source.trim() || capturedTarget != "hi-latn") return null
        return TranslationDraft(text, proof).takeIf {
            TranslationQualityPolicy.isUsable(source, it.text, targetLanguage, it.hindiDraft)
        }
    }
}
