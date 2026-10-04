package com.mangalens.ui.video

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import com.mangalens.download.MangaLensDownloadService
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
class LocalVideoPlayerViewModel(app: Application) : AndroidViewModel(app) {
    private fun sourceFactory(http: androidx.media3.datasource.DataSource.Factory): DefaultMediaSourceFactory {
        val cached = CacheDataSource.Factory()
            .setCache(MangaLensDownloadService.Holder.cache(getApplication()))
            .setUpstreamDataSourceFactory(http)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DefaultMediaSourceFactory(DefaultDataSource.Factory(getApplication(), cached))
    }

    private val mediaSourceFactory = sourceFactory(
        MediaPlaybackDataSource.factory(MediaRequestContext(""))
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
    private var httpRequestKey: String? = null

    fun open(value: Uri) {
        if (uri == value && player.mediaItemCount > 0) return
        uri = value
        httpRequestKey = null
        player.setMediaItem(MediaItem.fromUri(value))
        player.prepare()
        player.playWhenReady = true
    }

    fun openHttp(
        value: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap()
    ) {
        val requestKey = buildString {
            append(value).append('\n')
            append(referer.orEmpty()).append('\n')
            headers.entries.sortedBy { it.key.lowercase() }.forEach { (name, headerValue) ->
                append(name.lowercase()).append('=').append(headerValue).append('\n')
            }
        }
        if (httpRequestKey == requestKey && player.mediaItemCount > 0) return
        uri = Uri.parse(value)
        httpRequestKey = requestKey
        // Each media source owns a snapshot, so old segment requests cannot inherit a new session.
        val factory = sourceFactory(MediaPlaybackDataSource.factory(MediaRequestContext(value, referer, headers)))
        player.setMediaSource(factory.createMediaSource(MediaItem.fromUri(value)))
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
