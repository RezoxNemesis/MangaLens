package com.mangalens.ui.video

import androidx.media3.common.MediaItem
import com.mangalens.download.MediaTransportMime

/** Retain known manifest transport even when its URL has no filename extension. */
internal fun playbackHttpMediaItem(uri: String, mimeType: String?): MediaItem =
    MediaItem.Builder().setUri(uri).setMimeType(MediaTransportMime.capture(mimeType)).build()
