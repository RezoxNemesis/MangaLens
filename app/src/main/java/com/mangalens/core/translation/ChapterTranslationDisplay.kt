package com.mangalens.core.translation

import java.util.Locale

/** Select reader-owned results independently of unrelated background task updates. */
object ChapterTranslationDisplay {
    fun select(tasks: List<ChapterTranslationTask>, chapterId: String, language: String,
               styleId: String, customStyle: String,
               configuration: ChapterTranslationConfig? = null): ChapterTranslationTask? {
        if (chapterId.isBlank()) return null
        val captured = configuration?.let { runCatching { it.normalized() }.getOrNull() ?: return null }
        val target = language.trim().lowercase(Locale.ROOT)
        val style = if (styleId.trim().equals("custom", ignoreCase = true)) "custom"
            else TranslationStyleProfile.fromId(styleId.trim().lowercase(Locale.ROOT)).id
        val instruction = customStyle.trim().take(TranslationStyleProfile.MAX_CUSTOM_INSTRUCTION_CHARS)
        return tasks.asSequence().filter { task ->
            task.chapterId == chapterId &&
                task.config.targetLanguage.trim().lowercase(Locale.ROOT) == target &&
                task.config.style().id == style &&
                (style != "custom" || task.config.customStyle.trim() == instruction) &&
                (captured == null || task.config == captured)
        }.maxByOrNull { it.updatedAt }
    }
}
