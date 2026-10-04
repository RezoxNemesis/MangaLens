package com.mangalens.ui.video

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import com.mangalens.download.MangaLensDownloadService
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
class LocalVideoPlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val httpFactory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(15_000)
        .setReadTimeoutMs(30_000)
        .setDefaultRequestProperties(
            mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36",
                "Accept" to "*/*"
            )
        )

    private val cachedHttpFactory = CacheDataSource.Factory()
        .setCache(MangaLensDownloadService.Holder.cache(app))
        .setUpstreamDataSourceFactory(httpFactory)
        .setCacheWriteDataSinkFactory(null)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private val mediaSourceFactory = DefaultMediaSourceFactory(
        DefaultDataSource.Factory(app, cachedHttpFactory)
    )

    val player: ExoPlayer = ExoPlayer.Builder(
        app,
        DefaultRenderersFactory(app)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
    )
        .setMediaSourceFactory(mediaSourceFactory)
        .build()

    private var uri: Uri? = null

    fun open(value: Uri) {
        if (uri == value && player.mediaItemCount > 0) return
        uri = value
        player.setMediaItem(MediaItem.fromUri(value))
        player.prepare()
        player.playWhenReady = true
    }

    fun openHttp(
        value: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap()
    ) {
        if (uri?.toString() == value && player.mediaItemCount > 0) return
        val parsedUri = Uri.parse(value)
        uri = parsedUri
        val properties = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36",
            "Accept" to "*/*"
        )
        val allowed = setOf("accept", "cookie", "origin", "referer", "user-agent")
        headers.forEach { (name, headerValue) ->
            if (name.lowercase() in allowed && headerValue.length <= 16_384) {
                properties[name] = headerValue
            }
        }
        referer?.takeIf { it.startsWith("http") }?.let { properties["Referer"] = it }
        httpFactory.setDefaultRequestProperties(properties)
        player.setMediaItem(MediaItem.fromUri(parsedUri))
        player.prepare()
        player.playWhenReady = true
    }

    fun bind(view: PlayerView) {
        view.player = player
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
