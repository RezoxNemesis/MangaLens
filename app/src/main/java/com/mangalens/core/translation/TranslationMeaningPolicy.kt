package com.mangalens.core.translation

import java.util.Locale

/**
 * Conservative, observable meaning invariants for Hindi output. Passing these checks
 * is not evidence of fluency or full semantic equivalence; ambiguous dialogue still
 * needs source-aware inspection. Never manufacture a translation to satisfy a guard.
 */
object TranslationMeaningPolicy {
    fun isCompatible(source: String, candidate: String, targetLanguage: String): Boolean {
        if (targetLanguage.trim().lowercase(Locale.ROOT).replace('_', '-').substringBefore('-') != "hi") return true
        if (requiresNegativeMeaning(source) && !hasHindiNegation(candidate)) return false
        if (financialShortage.containsMatchIn(source) && !hasHindiNegation(candidate) &&
            words(candidate).none { it in scarcityWords }) return false
        if (!preservesExplicitAmounts(source, candidate)) return false
        if (!preservesOrdinalLabels(source, candidate)) return false
        return preservesExplicitNames(source, candidate)
    }

    internal fun requiresNegativeMeaning(source: String): Boolean {
        // These constructions have positive/indefinite readings. Remove just their
        // cue, retaining a separate "not/no" elsewhere in the same bubble.
        var text = source.lowercase(Locale.ROOT).replace('’', '\'')
        for (idiom in positiveIdioms) text = text.replace(idiom, " ")
        return englishNegation.containsMatchIn(text)
    }

    private fun hasHindiNegation(candidate: String): Boolean {
        val words = words(candidate)
        return words.any { it in hindiNegation || it.endsWith("रहित") || it.endsWith("विहीन") } ||
            words.any { it in romanNegation || it.endsWith("raheet") || it.endsWith("rahit") || it.endsWith("viheen") }
    }

    private fun words(candidate: String): List<String> = Regex("[\\p{L}\\p{M}]+")
        .findAll(candidate.lowercase(Locale.ROOT)).map { it.value }.toList()

    private fun preservesOrdinalLabels(source: String, candidate: String): Boolean {
        val ordinal = ordinalLabel.find(source)?.groupValues?.get(1)?.lowercase(Locale.ROOT) ?: return true
        val tokens = words(candidate)
        val accepted = ordinalWords.getValue(ordinal)
        if (tokens.none { word -> accepted.any(word::startsWith) } && ordinalNumbers[ordinal] !in amounts(candidate)) return false
        if (Regex("""(?i)\b(?:and|,)\s+(?:final|last)\b""").containsMatchIn(source) &&
            tokens.none { word -> finalWords.any(word::startsWith) }) return false
        return true
    }

    private fun preservesExplicitAmounts(source: String, candidate: String): Boolean {
        val required = amounts(source)
        if (required.isEmpty()) return true
        val present = amounts(candidate).toMutableList()
        val candidateWords = Regex("[\\p{L}\\p{M}]+").findAll(candidate.lowercase(Locale.ROOT)).map { it.value }.toList()
        candidateWords.forEach { word -> smallNumbers[word]?.let(present::add) }
        return required.all { amount -> present.indexOf(amount).takeIf { it >= 0 }?.let { present.removeAt(it); true } ?: false }
    }

    private fun amounts(text: String): List<String> = amountPattern.findAll(text.map { char ->
        if (char in '\u0966'..'\u096f') '0' + (char - '\u0966') else char
    }.joinToString("")).map { match -> match.value.replace(Regex(",(?=\\d{3}(?:,|\\b))"), "").trimStart('0').ifEmpty { "0" } }.toList()

    private fun preservesExplicitNames(source: String, candidate: String): Boolean {
        // All-capitals OCR dialogue is not a list of proper names. Require an explicit
        // vocative or name label, and leave generic forms of address translatable.
        val names = explicitNames(source)
        if (names.isEmpty()) return true
        val rendered = HindiRomanization.render(candidate, source)
        val words = Regex("[A-Za-z]+(?:['’-][A-Za-z]+)*").findAll(rendered).map { nameKey(it.value) }.toSet()
        return names.all { nameKey(it) in words }
    }

    internal fun protectedLatinNameWords(source: String): Set<String> = explicitNames(source).flatMap {
        Regex("[A-Za-z]+").findAll(it).map { word -> word.value.lowercase(Locale.ROOT) }.toList()
    }.toSet()

    private fun explicitNames(source: String): Set<String> = (vocative.findAll(source).map { it.groupValues[1] } +
        namedEntity.findAll(source).map { it.groupValues[1] }).filter {
            it.lowercase(Locale.ROOT) !in genericAddresses && it.length in 3..48
        }.toSet()

    private fun nameKey(text: String): String {
        val normalized = text.lowercase(Locale.ROOT).replace(Regex("['’-]"), "").replace("aa", "a").replace("ee", "i").replace("oo", "u")
            .replace('w', 'v').replace("ph", "f").replace("ck", "k")
        val consonants = normalized.replace(Regex("[aeiou]"), "")
        return if (consonants.length >= 3) consonants else normalized
    }

    private val positiveIdioms = listOf(
        "\\bno wonder\\b", "\\bnot only\\b", "\\bwhy not\\b", "\\bno matter\\b",
        "\\bnone other than\\b", "\\bno (?:problem|worries)\\b", "\\bnot bad\\b", "\\bno (?:one|body) but\\b"
    ).map(::Regex)
    private val englishNegation = Regex("\\b(?:no|not|never|neither|without|cannot|nobody|nothing|nowhere|none)\\b|\\b[a-z]+n't\\b")
    private val hindiNegation = setOf("नहीं", "नही", "ना", "न", "मत", "बिना", "अभाव", "रहित", "विहीन")
    private val romanNegation = setOf("nahi", "nahin", "naheen", "na", "mat", "bina", "binaa", "n", "abhav", "abhaav", "rahit", "raheet", "viheen", "vihin")
    private val amountPattern = Regex("(?<![\\p{L}\\p{N}])\\d+(?:[.,]\\d+)*(?![\\p{L}\\p{N}])")
    private val financialShortage = Regex("""(?i)\b(?:am|is|are|was|were)\s+short on\s+(?:money|cash|funds)\b""")
    private val scarcityWords = setOf("कमी", "तंगी", "अभाव", "कम", "अपर्याप्त", "kami", "kamee", "tangi", "tangee", "abhav", "abhaav", "kam", "aparyapt", "aparyaapt")
    private val ordinalLabel = Regex("""(?i)\bthe\s+(first|second|third|fourth|fifth)\b""")
    private val ordinalNumbers = mapOf("first" to "1", "second" to "2", "third" to "3", "fourth" to "4", "fifth" to "5")
    private val ordinalWords = mapOf("first" to listOf("पहल", "प्रथम", "pahl", "pehl", "pratham"),
        "second" to listOf("दूसर", "द्वितीय", "doosr", "dusr", "doosar", "dviteey"),
        "third" to listOf("तीसर", "तृतीय", "teesr", "tisr", "teesar", "triteey"),
        "fourth" to listOf("चौथ", "चतुर्थ", "chauth", "chaturth"), "fifth" to listOf("पाँचव", "पांचव", "पंचम", "paanchv", "panchv", "pancham"))
    private val finalWords = listOf("आखिरी", "आख़िरी", "अंतिम", "अन्तिम", "aakhri", "aakhiri", "aakhr", "antim", "aakhiree")
    private val vocative = Regex("\\b(?:hey|hello|hi|dear)[, ]+([A-Za-z][A-Za-z'’-]*)(?=[,.!?]|$)", RegexOption.IGNORE_CASE)
    private val namedEntity = Regex("\\b(?:named|called|name\\s*:|world\\s*:|city\\s*:)\\s*['\"]([A-Za-z]+)['\"]", RegexOption.IGNORE_CASE)
    private val genericAddresses = setOf("buddy", "friend", "sir", "madam", "mom", "dad", "mother", "father", "brother", "sister", "everyone", "guys", "there",
        "boss", "captain", "teacher", "student", "master", "doctor", "professor", "hunter", "kid", "girl", "boy", "idiot", "moron", "fool", "man", "dude",
        "wait", "listen", "look", "stop", "help", "please", "go", "run", "come", "relax")
    private val smallNumbers = listOf(
        listOf("शून्य", "zero", "shunya"), listOf("एक", "ek"), listOf("दो", "do"), listOf("तीन", "teen", "tin"),
        listOf("चार", "chaar", "char"), listOf("पाँच", "पांच", "paanch", "panch"), listOf("छह", "छः", "chhah", "chhe"),
        listOf("सात", "saat", "sat"), listOf("आठ", "aath", "ath"), listOf("नौ", "nau"), listOf("दस", "das"),
        listOf("ग्यारह", "gyarah", "gyaarah"), listOf("बारह", "baarah", "barah"), listOf("तेरह", "terah"),
        listOf("चौदह", "chaudah"), listOf("पंद्रह", "पन्द्रह", "pandrah"), listOf("सोलह", "solah"),
        listOf("सत्रह", "satrah"), listOf("अठारह", "atharah", "athaaraah"), listOf("उन्नीस", "unnees"), listOf("बीस", "bees")
    ).flatMapIndexed { number, words -> words.map { it to number.toString() } }.toMap()
}
