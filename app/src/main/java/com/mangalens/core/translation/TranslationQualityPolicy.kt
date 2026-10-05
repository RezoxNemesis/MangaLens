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
        val registerAware = harmonizeRegister(source, selected, targetLanguage)
        return restoreTerminalPunctuation(source, registerAware)
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
        if (letters.length < 3) return true
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
        return matching.toFloat() / letters.length >= .42f
    }

    /**
     * ML Kit often makes English→Hindi dialogue artificially respectful ("आप/कीजिए")
     * even when the source uses neutral or hostile "you". Manga dialogue should not invent
     * hierarchy that is absent from the source. Keep explicit honorific/formal cues untouched,
     * otherwise normalize only a small, high-confidence set of second-person forms.
     */
    private fun harmonizeRegister(source: String, translation: String, targetLanguage: String): String {
        if (targetLanguage.lowercase().substringBefore('-') != "hi") return translation
        val sourceLower = source.lowercase()
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
