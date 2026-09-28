package com.mangalens.ui.video

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.File
import java.net.URI

@UnstableApi
object AdvancedVideoEngine {
    private var cache: SimpleCache? = null

    private fun cache(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.cacheDir, "mangalens_media"),
            LeastRecentlyUsedCacheEvictor(256L * 1024L * 1024L)
        ).also { cache = it }
    }

    fun create(
        context: Context,
        url: String,
        headers: Map<String, String> = emptyMap(),
        pageUrl: String? = null
    ): ExoPlayer {
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "Video source must be an HTTP(S) media URL."
        }

        val inherited = buildMap {
            put("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
            put("Accept", "*/*")
            pageUrl?.let {
                put("Referer", it)
                runCatching { URI(it) }.getOrNull()?.let { uri ->
                    uri.scheme?.let { scheme -> uri.host?.let { host -> put("Origin", "$scheme://$host") } }
                }
            }
        }.toMutableMap().apply { putAll(headers) }

        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(inherited)

        val dataSource = CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(http)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val mediaItemBuilder = MediaItem.Builder().setUri(url)
        when {
            url.contains(".m3u8", true) -> mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            url.contains(".mpd", true) -> mediaItemBuilder.setMimeType(MimeTypes.APPLICATION_MPD)
        }

        return ExoPlayer.Builder(context)
            .setRenderersFactory(renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
            .build()
            .apply {
                setMediaItem(mediaItemBuilder.build())
                prepare()
                playWhenReady = true
            }
    }

    fun create(
        context: Context,
        media: SniffedMedia,
        pageUrl: String? = null
    ): ExoPlayer = create(context, media.url, media.headers, pageUrl)
}
