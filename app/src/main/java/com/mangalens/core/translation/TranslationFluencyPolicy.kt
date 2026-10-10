package com.mangalens.core.translation

import java.util.Locale

/**
 * A narrow grammar uncertainty gate, not a fluency score. It never writes target text.
 * Hindi dative + an explicitly numbered plural object + singular "मिल सका/मिला"
 * disagrees with that object. Agentive "मैं ... खोज सका", unmarked/mass nouns,
 * case-marked phrases and unknown speaker gender need different evidence and stay untouched.
 * A separate source-explicit single-clause first-person ability check rejects only observed
 * मैं/main modal agreement with a third-person auxiliary; it does not rewrite the dialogue.
 */
internal object TranslationFluencyPolicy {
    fun isPlausible(source: String, candidate: String, targetLanguage: String): Boolean {
        if (targetLanguage.trim().lowercase(Locale.ROOT).replace('_', '-').substringBefore('-') != "hi") return true
        if (hasExplicitFirstPersonDisagreement(source, candidate, targetLanguage)) return false
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

    private fun hasExplicitFirstPersonDisagreement(source: String, candidate: String, targetLanguage: String): Boolean {
        val original = source.replace(Regex("\\s+"), " ").trim()
        if (!explicitFirstPersonAbility.matches(original) || ambiguousEnglishClause.containsMatchIn(original)) return false
        val value = candidate.replace(Regex("\\s+"), " ").trim()
        if (HindiRomanization.isTarget(targetLanguage)) {
            val observed = romanFirstPersonDisagreement.matchEntire(value) ?: return false
            val phrase = observed.groupValues[1].lowercase(Locale.ROOT).split(Regex("\\s+"))
            return phrase.none { it in romanClauseBoundaries }
        }
        val observed = hindiFirstPersonDisagreement.matchEntire(value) ?: return false
        val phrase = observed.groupValues[1].split(Regex("\\s+"))
        return phrase.none { it in hindiClauseBoundaries }
    }

    // A source-explicit single English first-person ability clause plus an observed
    // मैं/main main predicate licenses this narrow agreement rejection, not a new
    // person, tense, gender or target sentence. Quotes and subordinate clauses defer.
    private val explicitFirstPersonAbility = Regex(
        "I\\s+(?:can(?:not|['’]t|\\s+not)?|am\\s+able\\s+to)\\s+[\\p{L}\\p{N}][^.!?;:\"“”]{0,255}[.!?]?",
        RegexOption.IGNORE_CASE
    )
    private val ambiguousEnglishClause = Regex(
        "\\b(?:and|or|but|if|when|unless|although|because|while|that|who|which|whose|whether|before|after|hardly|barely|scarcely|only)\\b",
        RegexOption.IGNORE_CASE
    )
    private val hindiFirstPersonDisagreement = Regex(
        "मैं\\s+((?:[\\p{L}\\p{M}\\p{N}]+\\s+){0,32})(?:सकता|सकती|सकते)\\s+(?:है|हैं)\\s*[।.!?]?"
    )
    private val romanFirstPersonDisagreement = Regex(
        "(?:main|mai|mein)\\s+((?:[A-Za-z0-9]+\\s+){0,32})(?:sakta|saktaa|sakti|saktee|sakee|sakte|saktay)\\s+(?:hai|hain|hein|hen)\\s*[.!?]?",
        RegexOption.IGNORE_CASE
    )
    private val hindiClauseBoundaries = setOf("कि", "जो", "जिसे", "जिसने", "जिन्हें", "अगर", "यदि", "जब", "तो", "और", "या", "लेकिन", "क्योंकि",
        "हूँ", "हूं", "मैं", "हम", "तुम", "आप", "वह", "वे", "ये", "यह")
    private val romanClauseBoundaries = setOf("ki", "jo", "jise", "jisne", "jinhein", "agar", "yadi", "jab", "to", "aur", "ya", "lekin", "kyonki", "kyunki",
        "hoon", "hun", "main", "mai", "mein", "hum", "tum", "aap", "vah", "vo", "ve", "ye", "yah")

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
