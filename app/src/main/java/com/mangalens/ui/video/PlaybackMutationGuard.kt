package com.mangalens.ui.video

/** Media3 listener callbacks can synchronously replace the source between player mutations. */
internal fun mutatePlaybackWhileOwned(current: () -> Boolean, mutations: List<() -> Unit>): Boolean {
    for (mutation in mutations) {
        if (!current()) return false
        mutation()
    }
    return current()
}

/** Captured on the player thread with the replacement position, before mutations. */
internal fun preparePlaybackReplacementWhileOwned(
    current: () -> Boolean,
    refreshFromRevision: Long?,
    capturedPlayWhenReady: Boolean,
    replaceSource: () -> Unit,
    prepare: () -> Unit,
    setPlayWhenReady: (Boolean) -> Unit
): Boolean = mutatePlaybackWhileOwned(current, listOf(
    replaceSource,
    prepare,
    { setPlayWhenReady(refreshFromRevision == null || capturedPlayWhenReady) }
))
