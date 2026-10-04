package com.mangalens.core.translation

/**
 * Deterministic guardrails for dialogue translation.
 *
 * The on-device translator/refiner can occasionally return source-language leakage,
 * runaway explanatory prose or empty output. Keep a strong fast draft available and
 * only accept refinement when it remains plausible for the requested target language.
 */
object TranslationQualityPolicy {
    fun choose(
        source: String,
        draft: String,
        refined: String,
        targetLanguage: String
    ): String {
        val cleanDraft = clean(draft)
        val cleanRefined = clean(refined)
        val selected = if (isAcceptable(source, cleanDraft, cleanRefined, targetLanguage)) {
            cleanRefined
        } else {
            cleanDraft
        }.ifBlank { clean(source) }
        return restoreTerminalPunctuation(source, selected)
    }

    private fun clean(value: String): String =
        value.replace(Regex("[\\t\\r ]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

    private fun isAcceptable(
        source: String,
        draft: String,
        candidate: String,
        targetLanguage: String
    ): Boolean {
        if (candidate.isBlank()) return false

        val normalizedSource = normalize(source)
        val normalizedDraft = normalize(draft)
        val normalizedCandidate = normalize(candidate)
        if (
            normalizedCandidate == normalizedSource &&
            normalizedDraft.isNotBlank() &&
            normalizedDraft != normalizedSource
        ) return false

        val baseline = maxOf(source.length, draft.length, 6)
        if (candidate.length > baseline * 4 + 48) return false

        val words = normalizedCandidate.split(' ').filter { it.isNotBlank() }
        if (words.size >= 6) {
            val mostFrequent = words.groupingBy { it }.eachCount().maxOfOrNull { it.value } ?: 0
            if (mostFrequent >= 5 && mostFrequent * 2 >= words.size) return false
        }

        return targetScriptLooksPlausible(candidate, targetLanguage)
    }

    private fun normalize(value: String): String =
        value.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun targetScriptLooksPlausible(text: String, targetLanguage: String): Boolean {
        val letters = text.filter(Char::isLetter)
        if (letters.size < 3) return true
        val language = targetLanguage.lowercase().substringBefore('-')
        val matching = when (language) {
            "hi", "mr", "ne" -> letters.count { it in '\u0900'..'\u097f' }
            "ja" -> letters.count { it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff' }
            "ko" -> letters.count { it in '\uac00'..'\ud7af' }
            "zh" -> letters.count { it in '\u4e00'..'\u9fff' }
            "en", "es", "fr", "de", "it", "pt", "id", "vi", "tr" ->
                letters.count { it.code in 0x0041..0x024F }
            else -> return true
        }
        return matching.toFloat() / letters.size >= .42f
    }

    private fun restoreTerminalPunctuation(source: String, translation: String): String {
        val sourceMark = source.trim().lastOrNull { it in "?!…！？。" } ?: return translation
        if (translation.isBlank() || translation.last() in "?!…！？。") return translation
        val mark = when (sourceMark) {
            '？' -> '?'
            '！' -> '!'
            '。' -> '.'
            else -> sourceMark
        }
        return translation + mark
    }
}
