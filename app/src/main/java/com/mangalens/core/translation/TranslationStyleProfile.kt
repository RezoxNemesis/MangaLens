package com.mangalens.core.translation

import java.security.MessageDigest

data class TranslationStyleProfile(
    val id: String,
    val name: String,
    val instruction: String,
    val preserveHonorifics: Boolean = true,
    val preserveNames: Boolean = true,
    val naturalDialogue: Boolean = true
) {
    val memoryKey: String
        get() = if (id != "custom") id else "custom:" + stableHash(instruction).take(20)

    companion object {
        const val MAX_CUSTOM_INSTRUCTION_CHARS = 1200

        val NATURAL = TranslationStyleProfile(
            "natural", "Natural",
            "Use fluent, natural dialogue that sounds professionally localized while preserving intent, relationship and emotional register. Do not add respect, politeness or honorificity that the source does not express."
        )
        val FAITHFUL = TranslationStyleProfile(
            "faithful", "Faithful",
            "Stay very close to the source meaning, sentence structure and emotional nuance without becoming unnatural."
        )
        val CASUAL = TranslationStyleProfile(
            "casual", "Casual",
            "Use relaxed, modern conversational language suitable for everyday character dialogue."
        )
        val FORMAL = TranslationStyleProfile(
            "formal", "Formal",
            "Use polished, respectful language while preserving character hierarchy and social nuance."
        )
        val MANGA = TranslationStyleProfile(
            "manga", "Manga",
            "Preserve source character voice, emotion, genre terminology and established names or honorifics. Use expressive manga dialogue and concise emphasis without inventing politeness, explanations or plot facts."
        )
        val LITERAL = TranslationStyleProfile(
            "literal", "Literal",
            "Favor a direct rendering of the source wording, clause order and explicit meaning over creative localization. Preserve names, honorifics and emphasis; use readable target-language grammar without adding explanations or implied facts.",
            naturalDialogue = false
        )
        val WEBTOON = TranslationStyleProfile(
            "webtoon", "Webtoon",
            "Write concise, expressive webtoon dialogue with strong emotional rhythm and readable line length."
        )

        fun custom(instruction: String): TranslationStyleProfile = TranslationStyleProfile(
            id = "custom",
            name = "Custom",
            instruction = instruction.trim().take(MAX_CUSTOM_INSTRUCTION_CHARS).ifBlank { NATURAL.instruction }
        )

        fun fromId(id: String): TranslationStyleProfile = when (id.lowercase()) {
            FAITHFUL.id -> FAITHFUL
            CASUAL.id -> CASUAL
            FORMAL.id -> FORMAL
            MANGA.id -> MANGA
            WEBTOON.id -> WEBTOON
            LITERAL.id -> LITERAL
            else -> NATURAL
        }

        private fun stableHash(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
