package com.mangalens.ui.video

import android.net.Uri

/** Canonical, app-private caption bytes created by the bounded import/translation store. */
internal data class PlayerCaptionTrack(
    val uri: Uri,
    val mimeType: String,
    val language: String,
    val label: String,
    val sha256: String,
    val bytes: Long,
    val sourceUri: Uri = uri,
    val sourceSha256: String = sha256,
    val sourceBytes: Long = bytes
)
