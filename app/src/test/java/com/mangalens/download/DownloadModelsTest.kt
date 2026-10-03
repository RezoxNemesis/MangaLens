package com.mangalens.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadModelsTest {
    @Test
    fun adaptiveHlsAndDashSourcesAreClassifiedAsVideo() {
        val hls = DownloadEntity("hls", "https://cdn.example/video/master.m3u8?token=1", "HLS", "application/x-mpegURL")
        val dash = DownloadEntity("dash", "https://cdn.example/video/stream", "DASH", "application/dash+xml")
        val manifest = DownloadEntity("manifest", "https://cdn.example/manifest/session?id=3", "Manifest", "application/octet-stream")
        listOf(hls, dash, manifest).forEach {
            assertTrue(it.isAdaptive)
            assertTrue(it.isVideo)
        }
    }

    @Test
    fun ordinaryFilesKeepTheirExpectedClassification() {
        val mp4 = DownloadEntity("mp4", "https://cdn.example/movie.mp4", "Movie", "video/mp4")
        val image = DownloadEntity("image", "https://cdn.example/page.webp", "Page", "image/webp")
        assertFalse(mp4.isAdaptive)
        assertTrue(mp4.isVideo)
        assertFalse(image.isAdaptive)
        assertFalse(image.isVideo)
    }
}
