package com.mangalens.ui.reader

import com.mangalens.core.translation.PersonalMangaLettering

/** A matching immutable native lettering record is required even after the IO freshness gate. */
internal fun applyPersonalReaderOverlays(native: List<TranslationOverlay>, personal: Map<Int, PersonalMangaLettering>): List<TranslationOverlay> =
    native.mapIndexed { index, overlay ->
        val correction = personal[index]
        if (correction == null || overlay.lettering != correction.original) overlay
        else overlay.copy(translatedText = correction.personal.translated, lettering = correction.personal,
            personalRegion = correction.pageIndex?.takeIf { correction.regionPresentation != null }?.let {
                com.mangalens.core.translation.PersonalReaderRegion(it, index, correction)
            })
    }
