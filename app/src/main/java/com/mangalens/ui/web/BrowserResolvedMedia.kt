package com.mangalens.ui.web

import com.mangalens.download.ResolvedMediaLink
import com.mangalens.ui.video.SniffedMedia
import com.mangalens.ui.video.VideoPlaybackPublication
import com.mangalens.ui.video.VideoPlaybackSelection

/** Captures the one resolved media tuple after the browser's current-page gate. */
internal fun captureResolvedBrowserMedia(
    resolved: ResolvedMediaLink,
    videoHeaders: Map<String, String>,
    audioHeaders: Map<String, String>,
    titleFallback: String?
): SniffedMedia = SniffedMedia(
    url = resolved.url,
    headers = videoHeaders.toMap(),
    kind = when (resolved.mimeType) {
        "application/x-mpegURL" -> "HLS"
        "application/dash+xml" -> "DASH"
        else -> "RESOLVED"
    },
    audioUrl = resolved.audioUrl,
    audioHeaders = audioHeaders.toMap(),
    title = resolved.title ?: titleFallback,
    provider = resolved.provider,
    providerCaptions = resolved.providerCaptions?.captureSnapshot()
)

/** The browser hands the complete tuple to the same accepted-video publication as native ingest. */
internal fun captureBrowserVideoSelection(media: SniffedMedia, sourcePage: String): VideoPlaybackSelection =
    VideoPlaybackPublication.capture(media.url, media.headers, sourcePage,
        media.audioUrl, media.audioHeaders, media.providerCaptions)
