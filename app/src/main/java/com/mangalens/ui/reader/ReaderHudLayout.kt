package com.mangalens.ui.reader

/** All dimensions use the same viewport units; reserve the actual header/inset before scrolling tools. */
internal fun readerHudToolsHeight(viewport: Float, header: Float, bottomInset: Float): Float {
    if (!viewport.isFinite() || viewport <= 0f) return 0f
    val headerSpace = header.takeIf { it.isFinite() && it > 0f } ?: 0f
    val navigationSpace = bottomInset.takeIf { it.isFinite() && it > 0f } ?: 0f
    // Existing outer padding12dp on both edges plus12dp separation below the header.
    return (viewport - headerSpace - navigationSpace - 36f).coerceAtLeast(0f)
}
