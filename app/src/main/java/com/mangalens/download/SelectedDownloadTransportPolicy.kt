package com.mangalens.download

/** A single adaptive request cannot stand in for two independently selected original tracks. */
internal object SelectedDownloadTransportPolicy {
    const val PAIRED_ADAPTIVE = "This selection uses separate adaptive video and audio playlists. Native watching is available; saving both original tracks from this selection is not supported yet. No complete download was created."
    const val WORKER_ADAPTIVE = "The refreshed source is an adaptive playlist. Resolve a new download using the supported adaptive transport; the existing partial files were retained."
    const val PENDING_HLS = "The selected video and audio playlists have not been captured as a complete source pair. Resolve the source again; no complete download was created."
    fun requireSupported(media: ResolvedMediaLink?) {
        if (media?.videoHlsSource != null || media?.audioHlsSource != null)
            throw MediaSourceException(MediaSourceFailure(MediaSourceFailureKind.UNSUPPORTED_TRANSPORT, PENDING_HLS), null)
        OriginalFragmentTransport.downloadKind(media) // Validate supported plans before using them as source authority.
        if (media?.audioUrl != null && (media.videoFragments == null && isAdaptiveMediaSource(media.url, media.mimeType) ||
            media.audioFragments == null && isAdaptiveMediaSource(media.audioUrl, media.audioMimeType)))
            throw MediaSourceException(MediaSourceFailure(MediaSourceFailureKind.UNSUPPORTED_TRANSPORT, PAIRED_ADAPTIVE), null)
    }
    /** A running encoded-track worker cannot reinterpret a playlist as encoded media after refresh. */
    fun requireWorkerCompatible(media: ResolvedMediaLink?) {
        requireSupported(media)
        if (media != null && media.videoFragments == null && isAdaptiveMediaSource(media.url, media.mimeType))
            throw MediaSourceException(MediaSourceFailure(MediaSourceFailureKind.UNSUPPORTED_TRANSPORT, WORKER_ADAPTIVE), null)
    }
}
