package com.mangalens.ui.components

/** Measured widths decide placement; no screen-size or font-scale assumptions. */
internal fun brandHeaderStacksActions(availableWidth: Int, brandWidth: Int, actionWidth: Int, gap: Int): Boolean =
    actionWidth > 0 && brandWidth.toLong() + actionWidth + gap > availableWidth
