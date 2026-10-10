package com.mangalens.core.translation

import java.util.Locale

/** Observable differences only. COMPATIBLE is not a semantic-equivalence or fluency score. */
internal enum class TranslationCandidateComparison { COMPATIBLE, NEW_EXPLICIT_NUMBER, CAPTURED_TERM_LOSS, OUTSIDE_BOUND }

internal object TranslationCandidateComparisonPolicy {
    fun assess(source: String, usableDraft: String, refined: String, targetLanguage: String,
        capturedGlossary: Map<String, String> = emptyMap()): TranslationCandidateComparison {
        if (source.length > 8000 || usableDraft.length > 8000 || refined.length > 8000 || capturedGlossary.size > 20 ||
            capturedGlossary.any { it.key.length !in 1..256 || it.value.length !in 1..256 }) return TranslationCandidateComparison.OUTSIDE_BOUND
        val language = targetLanguage.trim().lowercase(Locale.ROOT).replace('_', '-').substringBefore('-')
        if (language == "hi" || HindiRomanization.isTarget(targetLanguage)) {
            val observedSourceNumbers = explicitIntegers(source)
            if (observedSourceNumbers.isNotEmpty()) {
                // The usable draft can spell out an implicit count ("one of the 3"). This
                // comparison rejects new different digit values; it does not infer quantities.
                val allowed = observedSourceNumbers + explicitIntegers(usableDraft) + englishNumbers(source)
                if (explicitIntegers(refined).any { it !in allowed }) return TranslationCandidateComparison.NEW_EXPLICIT_NUMBER
            }
        }
        // Only a source-mentioned, current-target projection supplied by the native caller
        // participates. A draft's observed exact preferred spelling is retained conservatively;
        // its absence from a refiner does not establish that an inflected synonym is wrong.
        if (usableDraft.isNotBlank() && capturedGlossary.any { (sourceTerm, preferred) ->
                containsTerm(source, sourceTerm) && containsTerm(usableDraft, preferred) && !containsTerm(refined, preferred)
            }) return TranslationCandidateComparison.CAPTURED_TERM_LOSS
        return TranslationCandidateComparison.COMPATIBLE
    }

    private fun containsTerm(text: String, term: String): Boolean {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        val key = term.replace(Regex("\\s+"), " ").trim()
        if (key.isEmpty()) return false
        return Regex("(?<![\\p{L}\\p{M}\\p{N}])${Regex.escape(key)}(?![\\p{L}\\p{M}\\p{N}])", RegexOption.IGNORE_CASE)
            .containsMatchIn(normalized)
    }
    private fun explicitIntegers(text: String): Set<String> = integer.findAll(text.map {
        if (it in '\u0966'..'\u096f') '0' + (it - '\u0966') else it
    }.joinToString("")).map { it.value.replace(",", "").trimStart('0').ifEmpty { "0" } }.toSet()
    private fun englishNumbers(source: String): Set<String> = Regex("[A-Za-z]+")
        .findAll(source.lowercase(Locale.ROOT)).mapNotNull { smallEnglishNumbers[it.value] }.toSet()
    // Whole integer spelling only; fractions, decimals and unknown source quantifiers stay outside.
    private val integer = Regex("(?<![\\p{L}\\p{N}.,])[0-9]{1,9}(?:,[0-9]{3})*(?![\\p{L}\\p{N}]|[.,][0-9])")
    private val smallEnglishNumbers = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty")
        .mapIndexed { index, word -> word to index.toString() }.toMap() + mapOf("first" to "1", "second" to "2", "third" to "3", "fourth" to "4", "fifth" to "5")
}
