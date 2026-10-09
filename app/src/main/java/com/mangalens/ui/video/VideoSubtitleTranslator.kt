package com.mangalens.ui.video

import android.content.Context
import android.net.Uri
import com.mangalens.core.translation.TranslationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class VideoSubtitleTranslator(context: Context) {
    private val store = ImportedCaptionStore(context)

    /** Compatibility entry point: return only a complete, verified private SRT. */
    suspend fun translate(uri: Uri, targetLanguage: String): Uri {
        val translated = translateFile(store.read(uri), targetLanguage)
        return store.publish(translated, targetLanguage, "Translated subtitles").uri
    }

    internal suspend fun translateFile(
        source: ImportedCaptionFile,
        targetLanguage: String,
        sourceLanguage: String? = null,
        dual: Boolean = false,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): ImportedCaptionFile = withContext(Dispatchers.IO) {
        val service = TranslationService()
        try {
            CaptionTranslation.translate(source, targetLanguage, dual,
                translate = { text -> service.translateDraft(text, targetLanguage, sourceLanguage) }, onProgress = onProgress)
        } finally {
            service.close()
        }
    }
}
