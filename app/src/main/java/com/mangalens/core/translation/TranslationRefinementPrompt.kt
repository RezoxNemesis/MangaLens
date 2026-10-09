package com.mangalens.core.translation

internal fun buildTranslationRefinementPrompt(
    source: String,
    translated: String,
    targetLanguage: String,
    style: TranslationStyleProfile,
    chapterContext: String,
    glossary: Map<String, String>
): String {
    val glossaryText = glossary.entries
        .take(20)
        .joinToString("\n") { "${it.key} => ${it.value}" }
        .ifBlank { "(none)" }

    val contextText = chapterContext
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(4500)
        .ifBlank { "(no earlier dialogue context)" }

    val languageRules = if (HindiRomanization.isTarget(targetLanguage)) {
        """
        - For hi-latn, write Hinglish (Roman Hindi): Hindi dialogue in readable Latin letters.
        - Use familiar spoken spellings such as main, hoon, nahi, tum and kya; avoid scholarly diacritics.
        - Keep source name spellings and established English terms such as level, skill, mana when they fit the dialogue.
        - No Devanagari, English-only paraphrases or explanations. Keep the Hindi meaning and sentence structure natural.
        - Neutral informal dialogue normally uses tum-register; use aap only with explicit respect, and tu only when the source/context warrants hostile or intimate address.
        """.trimIndent()
    } else {
        "- For English→Hindi, neutral informal dialogue normally maps to natural तुम-register; use आप only when the source/context is explicitly respectful, and hostile dialogue may use तू-register when warranted."
    }

    return """
        You are the final manga/manhwa localization editor inside MangaLens.
        Produce ONLY the final translation. Never explain your work.

        TARGET LANGUAGE: $targetLanguage
        STYLE: ${style.name}
        STYLE RULE: ${style.instruction}
        PRESERVE NAMES: ${style.preserveNames}
        PRESERVE HONORIFICS: ${style.preserveHonorifics}
        NATURAL DIALOGUE: ${style.naturalDialogue}

        Rules:
        - Preserve meaning, intent, emotion, relationship and scene implications.
        - Keep character voice consistent with the chapter context.
        - Never invent plot facts, names or actions.
        - Do not translate a proper name unless the glossary explicitly maps it.
        - Preserve established honorifics when appropriate. Never invent politeness, respect, titles, honorifics or social distance that is absent from the source.
        ${languageRules.replace("\n", "\n        ")}
        - Fix literal or robotic machine-translation phrasing.
        - Keep short dialogue short; do not add explanations.
        - Preserve emphasis, laughter, hesitation, shouting and rhetorical tone.
        - If the source contains an obvious OCR error, infer the most plausible reading from context without inventing new content.
        - Return only the localized dialogue.

        CHAPTER CONTEXT:
        $contextText

        GLOSSARY:
        $glossaryText

        SOURCE:
        $source

        DRAFT:
        $translated
    """.trimIndent()
}
