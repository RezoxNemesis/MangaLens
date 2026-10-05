package com.mangalens.ui.video

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import com.mangalens.download.MangaLensDownloadService
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
class LocalVideoPlayerViewModel(app: Application) : AndroidViewModel(app) {
    private val positions = app.getSharedPreferences("mangalens_video_positions", 0)
    private fun positionKey(value: Uri) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toString().toByteArray()).joinToString("") { "%02x".format(it) }
    private fun savePosition() {
        uri?.let { if (player.currentPosition > 0) positions.edit().putLong(positionKey(it), player.currentPosition).apply() }
    }
    val speech = VideoSpeechEngine(app, viewModelScope)
    private val speechListener = object : androidx.media3.common.Player.Listener {
        override fun onPositionDiscontinuity(oldPosition: androidx.media3.common.Player.PositionInfo, newPosition: androidx.media3.common.Player.PositionInfo, reason: Int) {
            speech.positionMs = newPosition.positionMs
            speech.invalidate()
        }
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) { speech.invalidate(clear = true) }
    }
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
        object : DefaultRenderersFactory(app) {
            override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(speech.processor)).build()
        }
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
    )
        .setMediaSourceFactory(mediaSourceFactory)
        .build()

    init {
        player.addListener(speechListener)
        viewModelScope.launch {
            runCatching { speech.loadInstalled() }
            var ticks = 0
            while (isActive) { speech.positionMs = player.currentPosition; if (++ticks % 20 == 0) savePosition(); delay(100) }
        }
    }

    private var uri: Uri? = null
    private var httpRequestKey: String? = null

    fun open(value: Uri) {
        if (uri == value && player.mediaItemCount > 0) return
        savePosition()
        uri = value
        httpRequestKey = null
        player.setMediaItem(MediaItem.fromUri(value), positions.getLong(positionKey(value), 0L))
        player.prepare()
        player.playWhenReady = true
    }

    fun openHttp(
        value: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        audioUrl: String? = null,
        audioHeaders: Map<String, String> = emptyMap()
    ) {
        val requestKey = buildString {
            append(value).append('\n')
            append(referer.orEmpty()).append('\n')
            headers.entries.sortedBy { it.key.lowercase() }.forEach { (name, headerValue) ->
                append(name.lowercase()).append('=').append(headerValue).append('\n')
            }
            append("audio=").append(audioUrl.orEmpty()).append('\n')
            audioHeaders.entries.sortedBy { it.key.lowercase() }.forEach { (name, headerValue) ->
                append("audio-").append(name.lowercase()).append('=').append(headerValue).append('\n')
            }
        }
        if (httpRequestKey == requestKey && player.mediaItemCount > 0) return
        savePosition()
        uri = Uri.parse(value)
        httpRequestKey = requestKey

        // Each media source owns a request-context snapshot. This matters for signed/CDN media:
        // redirects and segment requests keep the source-page Referer/Cookie instead of inheriting
        // whichever WebView page happened to load most recently.
        val videoFactory = sourceFactory(
            MediaPlaybackDataSource.factory(MediaRequestContext(value, referer, headers))
        )
        val videoSource = videoFactory.createMediaSource(MediaItem.fromUri(value))
        val source = if (!audioUrl.isNullOrBlank()) {
            val audioFactory = sourceFactory(
                MediaPlaybackDataSource.factory(MediaRequestContext(audioUrl, referer, audioHeaders))
            )
            val audioSource = audioFactory.createMediaSource(MediaItem.fromUri(audioUrl))
            MergingMediaSource(videoSource, audioSource)
        } else {
            videoSource
        }
        player.setMediaSource(source, positions.getLong(positionKey(Uri.parse(value)), 0L))
        player.prepare()
        player.playWhenReady = true
    }

    fun bind(view: PlayerView) {
        view.player = player
    }

    override fun onCleared() {
        savePosition()
        player.removeListener(speechListener)
        player.release()
        // Complete native cancellation before freeing its context; viewModelScope is already cancelled.
        CoroutineScope(Dispatchers.IO).launch { speech.close() }
        super.onCleared()
    }
}
