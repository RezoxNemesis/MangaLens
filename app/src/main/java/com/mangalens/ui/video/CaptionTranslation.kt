package com.mangalens.ui.video

import com.mangalens.core.translation.TranslationDraft
import com.mangalens.core.translation.TranslationQualityPolicy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Cue text changes only after all original timing/count and translation checks succeed. */
internal object CaptionTranslation {
    suspend fun translate(
        source: ImportedCaptionFile,
        targetLanguage: String,
        dual: Boolean,
        translate: suspend (String) -> TranslationDraft,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): ImportedCaptionFile {
        val translated = ArrayList<ImportedCaptionCue>(source.cues.size)
        for ((index, cue) in source.cues.withIndex()) {
            currentCoroutineContext().ensureActive()
            val original = ImportedCaptionFile.visibleText(cue.text).trim()
            require(original.isNotBlank()) { "Subtitle cue ${index + 1} has no visible dialogue." }
            val draft = translate(original)
            currentCoroutineContext().ensureActive()
            val result = TranslationQualityPolicy.chooseDraft(original, draft, "", targetLanguage).text.trim()
            val text = if (dual && original != result) "$original\n$result" else result
            require(text.isNotBlank() && text.length <= 16_384 && "-->" !in text) { "Translated cue ${index + 1} is invalid." }
            translated += cue.copy(text = text)
            onProgress(index + 1, source.cues.size)
        }
        currentCoroutineContext().ensureActive()
        // Parsing the canonical output checks every returned cue before publication.
        return ImportedCaptionFile.parse(ImportedCaptionFile(translated).srt)
    }
}
