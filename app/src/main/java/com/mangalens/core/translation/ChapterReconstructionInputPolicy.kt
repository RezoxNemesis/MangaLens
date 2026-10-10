package com.mangalens.core.translation

/** Only an explicit new user-generation capture enters this function; cold decode/resume preserves its version. */
internal object ChapterReconstructionInputPolicy {
    const val CURRENT = 2
    fun newGeneration(configuration: ChapterTranslationConfig): ChapterTranslationConfig = configuration.copy(reconstructionVersion = CURRENT)
}
