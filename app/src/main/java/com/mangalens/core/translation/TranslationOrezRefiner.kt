package com.mangalens.core.translation

import android.content.Context
import com.mangalens.orez.OrezLocalModelService
import com.mangalens.orez.OrezModelManager

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
        - Preserve established honorifics when appropriate.
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

class TranslationOrezRefiner(context: Context) {
    private val model = OrezLocalModelService(OrezModelManager(context))

    suspend fun refine(
        source: String,
        translated: String,
        targetLanguage: String,
        style: TranslationStyleProfile = TranslationStyleProfile.NATURAL,
        chapterContext: String = "",
        glossary: Map<String, String> = emptyMap()
    ): String {
        if (source.isBlank() || translated.isBlank()) return translated

        val prompt = buildTranslationRefinementPrompt(
            source = source,
            translated = translated,
            targetLanguage = targetLanguage,
            style = style,
            chapterContext = chapterContext,
            glossary = glossary
        )

        return model.answer(prompt, emptyList())
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: translated
    }

    fun close() = model.close()
}
