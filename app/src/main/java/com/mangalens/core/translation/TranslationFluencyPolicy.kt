package com.mangalens.core.translation

import java.util.Locale

/**
 * A narrow grammar uncertainty gate, not a fluency score. It never writes target text.
 * Hindi dative + an explicitly numbered plural object + singular "मिल सका/मिला"
 * disagrees with that object. Agentive "मैं ... खोज सका", unmarked/mass nouns,
 * case-marked phrases and unknown speaker gender need different evidence and stay untouched.
 */
internal object TranslationFluencyPolicy {
    fun isPlausible(source: String, candidate: String, targetLanguage: String): Boolean {
        if (targetLanguage.trim().lowercase(Locale.ROOT).replace('_', '-').substringBefore('-') != "hi") return true
        val quantities = quantity.findAll(asciiDigits(source)).mapNotNull {
            it.value.replace(",", "").toLongOrNull()?.takeIf { count -> count > 1 }
        }.toSet()
        if (quantities.isEmpty()) return true
        val value = asciiDigits(candidate)
        for (match in hindiDisagreement.findAll(value)) {
            if (match.groupValues[1].toLongOrNull() !in quantities) continue
            val phrase = match.groupValues[2].trim().split(Regex("\\s+"))
            val head = phrase.last()
            if (phrase.any { it in hindiCaseMarkers }) continue
            if (head.endsWith("े") || head.endsWith("ें") || head.endsWith("याँ") || head.endsWith("यां")) return false
        }
        if (HindiRomanization.isTarget(targetLanguage)) for (match in romanDisagreement.findAll(value)) {
            if (match.groupValues[1].toLongOrNull() !in quantities) continue
            val phrase = match.groupValues[2].lowercase(Locale.ROOT).trim().split(Regex("\\s+"))
            val head = phrase.last()
            if (phrase.any { it in romanCaseMarkers } || head in unmarkedMeasurementUnits) continue
            // Unproven Roman model output is ambiguous; a plural-looking dative noun
            // with singular agreement should be retried through validated Hindi.
            if (head.endsWith("e") || head.endsWith("en") || head.endsWith("ein")) return false
        }
        return true
    }

    private fun asciiDigits(text: String) = text.map { if (it in '\u0966'..'\u096f') '0' + (it - '\u0966') else it }.joinToString("")

    private val quantity = Regex("(?<![\\p{L}\\p{N}.,])[0-9]{1,6}(?:,[0-9]{3})*(?![\\p{L}\\p{N}.,])")
    private val hindiDisagreement = Regex(
        "(?<![\\p{L}\\p{M}])(?:मुझे|मुझको|उसे|उसको|हमें|हमको|तुम्हें|तुमको|आपको|उन्हें|उनको|इसे|तुझे)\\s+" +
            "([0-9]{1,6})\\s+((?:[\\p{L}\\p{M}]{1,48}\\s+){0,3}[\\p{L}\\p{M}]{1,48})\\s+" +
            "(?:नहीं|नही|ना|न)\\s+(?:मिल\\s+सक[ाी]|मिल[ाी])(?=$|[\\s।.!?])"
    )
    private val romanDisagreement = Regex(
        "\\b(?:mujhe|mujhko|use|usko|hamein|hamen|humein|hamko|humko|tumhein|tumhe|tumko|aapko|unhein|unhe|unko|ise|tujhe)\\s+" +
            "([0-9]{1,6})\\s+((?:[A-Za-z]{1,48}\\s+){0,3}[A-Za-z]{1,48})\\s+" +
            "(?:nahi|nahin|naheen|na)\\s+(?:mil\\s+(?:sakaa?|sakee|saki)|milaa?|milee|mili)(?=$|[\\s.!?])",
        RegexOption.IGNORE_CASE
    )
    private val hindiCaseMarkers = setOf("के", "की", "को", "में", "से", "पर", "पे", "तक", "लिए", "पास")
    private val romanCaseMarkers = setOf("ke", "ki", "ko", "mein", "men", "se", "par", "pe", "tak", "liye", "paas")
    private val unmarkedMeasurementUnits = setOf("crore", "lakh", "kilometre", "kilometer", "metre", "meter", "litre", "liter", "mile", "kg", "km", "ml", "gram", "gramme")
}
