package com.mangalens.ui.video

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
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

    private val mediaSourceFactory = DefaultMediaSourceFactory(
        DefaultDataSource.Factory(app, httpFactory)
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

    fun openHttp(value: String, referer: String? = null) {
        if (uri?.toString() == value && player.mediaItemCount > 0) return
        uri = Uri.parse(value)
        val properties = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36",
            "Accept" to "*/*"
        )
        referer?.takeIf { it.startsWith("http") }?.let { properties["Referer"] = it }
        httpFactory.setDefaultRequestProperties(properties)
        player.setMediaItem(MediaItem.fromUri(uri!!))
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
