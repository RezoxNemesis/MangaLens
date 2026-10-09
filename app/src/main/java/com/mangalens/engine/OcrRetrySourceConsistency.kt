package com.mangalens.engine

import java.util.Locale

/** Stable observed spellings are evidence; a native confidence increase cannot replace them. */
internal fun retainsStableOcrSource(original: String, retry: String): Boolean {
    val tokens = Regex("[A-Za-z]+(?:[-'’][A-Za-z]+)*[,!?]?")
    fun letters(value: String) = value.filter(Char::isLetter).uppercase(Locale.ROOT)
    val fresh = tokens.findAll(retry).map { letters(it.value) }.toSet()
    return tokens.findAll(original).all { token ->
        val value = token.value.trimEnd(',', '!', '?')
        val vocative = token.value.endsWith(',') && value.length >= 3
        val regularCase = value.all { it.isUpperCase() || it == '-' || it == '\'' || it == '’' } ||
            Regex("[A-Z][a-z]+(?:[A-Z][a-z]+)*").matches(value)
        val contraction = Regex("['’](?:t|s|ll|re|ve|m|d)$", RegexOption.IGNORE_CASE).containsMatchIn(value)
        val knownUncertainty = OcrSourceQuality.isUncertainComparativeWord(original, value)
        val stable = !contraction && !knownUncertainty && (vocative ||
            (letters(value).length >= 4 && regularCase && OcrSourceQuality.clauseEvidence(value) == 0))
        !stable || letters(value) in fresh
    }
}
