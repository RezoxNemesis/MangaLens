package com.mangalens.core.translation

data class TranslationStyleProfile(
    val id: String,
    val name: String,
    val instruction: String,
    val preserveHonorifics: Boolean = true,
    val preserveNames: Boolean = true,
    val naturalDialogue: Boolean = true
) {
    companion object {
        val NATURAL = TranslationStyleProfile(
            "natural", "Natural",
            "Use fluent, natural dialogue that sounds like a professionally localized manga/manhwa while preserving the original intent."
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
        val WEBTOON = TranslationStyleProfile(
            "webtoon", "Webtoon",
            "Write concise, expressive webtoon dialogue with strong emotional rhythm and readable line length."
        )

        fun custom(instruction: String): TranslationStyleProfile = TranslationStyleProfile(
            id = "custom",
            name = "Custom",
            instruction = instruction.trim().ifBlank { NATURAL.instruction }
        )

        fun fromId(id: String): TranslationStyleProfile = when (id.lowercase()) {
            FAITHFUL.id -> FAITHFUL
            CASUAL.id -> CASUAL
            FORMAL.id -> FORMAL
            WEBTOON.id -> WEBTOON
            else -> NATURAL
        }
    }
}
