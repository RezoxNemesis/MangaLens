package com.mangalens.core.translation

import java.util.Locale

class TranslationQualityException : IllegalArgumentException(
    "Translation failed language/quality checks. Original text was preserved; retry this bubble or page."
)

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
        val draftUsable = isAcceptable(source, "", cleanDraft, targetLanguage)
        val selected = when {
            isAcceptable(source, if (draftUsable) cleanDraft else "", cleanRefined, targetLanguage) -> cleanRefined
            draftUsable -> cleanDraft
            else -> throw TranslationQualityException()
        }
        val registerAware = harmonizeRegister(source, selected, targetLanguage)
        return restoreTerminalPunctuation(source, registerAware)
    }

    /** Also check persisted drafts; an older bad result must not bypass today's gate. */
    fun isUsable(source: String, candidate: String, targetLanguage: String): Boolean =
        isAcceptable(source, "", clean(candidate), targetLanguage)

    private fun languageTag(value: String): String =
        value.trim().lowercase(Locale.ROOT).replace('_', '-').substringBefore('-')

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

        if (!targetScriptLooksPlausible(candidate, targetLanguage)) return false
        val target = languageTag(targetLanguage)
        if (target in setOf("hi", "mr", "ne")) {
            // A Hindi paragraph can meet the script ratio yet retain a whole English
            // clause such as "BEATEN UP". Reject copied multiword spans, while permitting
            // a single name/honorific. Explicit glossary support belongs in the caller.
            val sourceWords = latinWords(source)
            val copiedPhrases = sourceWords.zipWithNext().toSet()
            val candidateRuns = Regex("[A-Za-z]{2,}(?:[\\s'-]+[A-Za-z]{2,})+").findAll(candidate)
            if (candidateRuns.any { run -> latinWords(run.value).zipWithNext().any { it in copiedPhrases } }) return false
            val sourceLetters = source.filter(Char::isLetter)
            val sourceLatin = sourceLetters.count { it.code in 0x0041..0x024F }
            if (sourceLetters.isNotEmpty() && sourceLatin.toFloat() / sourceLetters.length >= .65f) {
                val candidateLetters = candidate.filter(Char::isLetter)
                if (candidateLetters.length >= 4) {
                    val devanagari = candidateLetters.count { it in '\u0900'..'\u097f' }
                    val latin = candidateLetters.count { it.code in 0x0041..0x024F }
                    if (devanagari.toFloat() / candidateLetters.length < .55f || latin.toFloat() / candidateLetters.length > .40f) {
                        return false
                    }
                }
            }
        }
        return true
    }

    private fun latinWords(value: String): List<String> =
        Regex("[A-Za-z]{2,}").findAll(value).map { it.value.lowercase(Locale.ROOT) }.toList()

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun targetScriptLooksPlausible(text: String, targetLanguage: String): Boolean {
        val letters = text.filter(Char::isLetter)
        if (letters.isEmpty()) return text.any { !it.isWhitespace() }
        val language = languageTag(targetLanguage)
        val matching = when (language) {
            "hi", "mr", "ne" -> letters.count { it in '\u0900'..'\u097f' }
            "ja" -> letters.count { it in '\u3040'..'\u30ff' || it in '\u4e00'..'\u9fff' }
            "ko" -> letters.count { it in '\uac00'..'\ud7af' }
            "zh" -> letters.count { it in '\u4e00'..'\u9fff' }
            "en", "es", "fr", "de", "it", "pt", "id", "vi", "tr" ->
                letters.count { it.code in 0x0041..0x024F }
            else -> return true
        }
        return matching.toFloat() / letters.length >= .42f
    }

    /**
     * ML Kit often makes English→Hindi dialogue artificially respectful ("आप/कीजिए")
     * even when the source uses neutral or hostile "you". Manga dialogue should not invent
     * hierarchy that is absent from the source. Keep explicit honorific/formal cues untouched,
     * otherwise normalize only a small, high-confidence set of second-person forms.
     */
    private fun harmonizeRegister(source: String, translation: String, targetLanguage: String): String {
        if (languageTag(targetLanguage) != "hi") return translation
        val sourceLower = source.lowercase(Locale.ROOT)
        val addressesSomeone = Regex("""\b(you|your|you're|you've|you'll|don't|do not|can you|will you)\b""").containsMatchIn(sourceLower)
        if (!addressesSomeone) return translation

        val explicitFormal = Regex(
            """\b(sir|madam|ma'am|lord|lady|master|mistress|your highness|your majesty|professor|doctor|sensei|senpai)\b"""
        ).containsMatchIn(sourceLower)
        if (explicitFormal) return translation

        val hostile = Regex(
            """\b(son of a|bastard|idiot|moron|fool|damn|hell|shut up|pissed|asshole|jerk|trash|scum|beat(?:en)? up)\b"""
        ).containsMatchIn(sourceLower)

        var out = translation
        if (hostile) {
            out = out
                .replace("आपको", "तुझे")
                .replace("आपका", "तेरा")
                .replace("आपकी", "तेरी")
                .replace("आपके", "तेरे")
                .replace(Regex("""\bआप\b"""), "तू")
                .replace("तुम्हें", "तुझे")
                .replace("तुम्हारा", "तेरा")
                .replace("तुम्हारी", "तेरी")
                .replace("तुम्हारे", "तेरे")
        } else {
            out = out
                .replace("आपको", "तुम्हें")
                .replace("आपका", "तुम्हारा")
                .replace("आपकी", "तुम्हारी")
                .replace("आपके", "तुम्हारे")
                .replace(Regex("""\bआप\b"""), "तुम")
        }

        // High-confidence polite imperatives that otherwise make casual dialogue sound deferential.
        out = out
            .replace("चिंता मत कीजिए", if (hostile) "चिंता मत कर" else "चिंता मत करो")
            .replace("बताइए", if (hostile) "बता" else "बताओ")
            .replace("जाइए", if (hostile) "जा" else "जाओ")
            .replace("आइए", if (hostile) "आ" else "आओ")
            .replace("रहिए", if (hostile) "रह" else "रहो")
            .replace("लीजिए", if (hostile) "ले" else "लो")
            .replace("दीजिए", if (hostile) "दे" else "दो")
            .replace("कीजिए", if (hostile) "कर" else "करो")
            .replace("करिए", if (hostile) "कर" else "करो")
        return out
    }

    private fun restoreTerminalPunctuation(source: String, translation: String): String {
        // Only the terminal mark is evidence. A question inside a sentence must not
        // turn the final translated statement into a question.
        val sourceMark = source.trim().lastOrNull()?.takeIf { it in "?!…！？。" } ?: return translation
        if (translation.isBlank() || translation.last() in "?!…！？。") return translation
        val mark = when (sourceMark) {
            '？' -> '?'
            '！' -> '!'
            '。' -> '.'
            else -> sourceMark
        }
        return translation.trimEnd('.', '।') + mark
    }
}

