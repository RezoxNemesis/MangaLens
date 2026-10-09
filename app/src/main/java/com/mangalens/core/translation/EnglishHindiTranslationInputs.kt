package com.mangalens.core.translation

internal fun normalizeEnglishDialogueForHindi(value: String): String {
    var text = value.replace(Regex("\\s+"), " ").trim()
    val replacements = listOf(
        Regex("""(?i)\bpissed me off even more\b""") to "made me even angrier",
        Regex("""(?i)\bpissed me off\b""") to "made me angry",
        Regex("""(?i)\bused to get beaten up\b""") to "used to be beaten badly",
        Regex("""(?i)\bget beaten up\b""") to "be beaten badly",
        Regex("""(?i)\bbeaten up\b""") to "badly beaten",
        Regex("""(?i)\bhurt a bit for me too\b""") to "hurt me a little too"
    )
    replacements.forEach { (pattern, replacement) -> text = text.replace(pattern, replacement) }
    // Financial shortage and negative possession are meaning-preserving English
    // paraphrases. They are model inputs, never hand-authored target dialogue.
    text = text.replace(Regex("""(?i)\bwith no\s+"""), "without any ")
    text = text.replace(Regex("""(?i)\b(am|is|are|was|were)\s+short on\s+(money|cash|funds)\b""")) {
        val auxiliary = when (it.groupValues[1].lowercase()) { "is" -> "does"; "was", "were" -> "did"; else -> "do" }
        "$auxiliary not have enough ${it.groupValues[2]}"
    }
    return text
}

/** At most one distinct retry. A failed quality check never causes an unbounded loop. */
internal object EnglishHindiTranslationInputs {
    internal suspend fun translateDraft(source: String, targetLanguage: String, translate: suspend (String) -> String): TranslationDraft {
        var last = TranslationDraft("")
        for (input in candidates(source)) {
            val translated = translate(input)
            last = TranslationDraft(translated)
            if (!TranslationQualityPolicy.isUsable(source, translated, "hi")) continue
            return if (HindiRomanization.isTarget(targetLanguage)) HinglishTranslationOutput.fromHindiDraft(source, translated)
            else last
        }
        // A caller with explicit local refinement may repair a rejected Hindi draft.
        // Plain translate() and final selection still apply the independent gate.
        if (HindiRomanization.isTarget(targetLanguage)) throw TranslationQualityException()
        return last
    }

    fun candidates(source: String): List<String> {
        val primary = normalizeEnglishDialogueForHindi(source)
        val alternative = if (Regex("""(?i)\bwith no\s+""").containsMatchIn(source))
            normalizeEnglishDialogueForHindi(source.replace(Regex("""(?i)\bwith no\s+"""), "that does not have any "))
        else inabilityAlternative(primary) ?: source.replace(Regex("\\s+"), " ").trim()
        return listOf(primary, alternative).filter(String::isNotBlank).distinct().take(2)
    }

    // A different model input preserves explicit subject/negative past ability and
    // every following verb/object/name/amount. Never rewrite positive "couldn't
    // agree more/help/be happier" idioms or the different perfect "couldn't have".
    private fun inabilityAlternative(source: String): String? {
        val alternative = explicitInability.replace(source) { match ->
            val subject = match.groupValues[1]
            val auxiliary = if (subject.lowercase(java.util.Locale.ROOT) in setOf("you", "we", "they")) "were" else "was"
            "$subject $auxiliary not able to ${match.groupValues[2]}"
        }
        return alternative.takeIf { it != source }
    }

    private val explicitInability = Regex(
        "\\b(I|you|he|she|we|they|it)\\s+could(?:n['’]t|\\s+not)\\s+(?!(?:have|be|help|agree)\\b)([A-Za-z]+)",
        RegexOption.IGNORE_CASE
    )
}
