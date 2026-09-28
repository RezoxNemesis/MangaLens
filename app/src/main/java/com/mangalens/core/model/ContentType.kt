package com.mangalens.core.model

/**
 * Represents the target viewing engine required for a given URL.
 */
enum class ContentType {
    /**
     * Sequential manga/webtoon images rendered in the Native Continuous Canvas Reader.
     */
    IMAGE_CHAPTER,

    /**
     * Video stream (.m3u8, .mp4, HLS manifest) rendered in Native ExoPlayer.
     */
    VIDEO_STREAM,

    /**
     * Interactive web page rendered inside the Ad-Free Sandboxed WebView.
     */
    GENERIC_WEB
}
