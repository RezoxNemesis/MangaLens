package com.mangalens.ui.video

import android.app.Application
import android.net.Uri
import com.mangalens.download.ProviderCaptionInventory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import com.mangalens.download.MangaLensDownloadService
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.PlayerView

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class PlaybackSession(private val app: Application) {
    val policy = PlaybackLifecyclePolicy(java.util.UUID.randomUUID().toString().replace("-", ""))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var disposed = false
    val lifecycle = kotlinx.coroutines.flow.MutableStateFlow(PlaybackLifecycleSnapshot())
    private var capturedSource: PlaybackSessionSource? = null
    private val captionPublication = CaptionPublication()
    var currentCaptionTrack: PlayerCaptionTrack? by mutableStateOf(null)
        private set
    private val recentVideos = RecentVideoStore.shared(app)
    private var recentSource: RecentVideoSource? = null
    private var recentRecordedKey: String? = null
    private var recentRecordedPosition = -1L
    private val positions = app.getSharedPreferences("mangalens_video_positions", 0)
    private fun positionKey(value: Uri) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toString().toByteArray()).joinToString("") { "%02x".format(it) }
    private fun savePosition() {
        uri?.let { if (player.currentPosition > 0) positions.edit().putLong(positionKey(it), player.currentPosition).apply() }
        val source = recentSource ?: return
        if (policy.closed || player.playerError != null ||
            player.currentMediaItem?.localConfiguration?.uri?.toString() != capturedSource?.uri ||
            player.playbackState !in setOf(androidx.media3.common.Player.STATE_READY, androidx.media3.common.Player.STATE_ENDED)) return
        val seekable = player.isCurrentMediaItemSeekable && !player.isCurrentMediaItemLive && !player.isCurrentMediaItemDynamic
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        if (seekable && duration > 0) {
            val position = player.currentPosition.coerceIn(0, duration)
            val resume = if (position >= duration - duration / 20) 0L else position
            positions.edit().putLong(positionKey(Uri.parse(source.uri)), resume).apply()
        }
        val recordedPosition = if (seekable && duration > 0) player.currentPosition.coerceIn(0, duration) else 0L
        if (recentRecordedKey != source.key || recentRecordedPosition != recordedPosition) {
            recentRecordedKey = source.key
            recentRecordedPosition = recordedPosition
            recentVideos.record(source, recordedPosition, duration, seekable)
        }
    }
    val speech = VideoSpeechEngine(app, scope)
    private val speechListener = object : androidx.media3.common.Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == androidx.media3.common.Player.STATE_READY || state == androidx.media3.common.Player.STATE_ENDED) savePosition()
        }
        override fun onPositionDiscontinuity(oldPosition: androidx.media3.common.Player.PositionInfo, newPosition: androidx.media3.common.Player.PositionInfo, reason: Int) {
            speech.positionMs = newPosition.positionMs
            speech.invalidate()
        }
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            if (item?.localConfiguration?.uri?.toString() != capturedSource?.uri) speech.invalidate(clear = true)
        }
    }
    private fun sourceFactory(http: androidx.media3.datasource.DataSource.Factory): DefaultMediaSourceFactory {
        val cached = CacheDataSource.Factory()
            .setCache(MangaLensDownloadService.Holder.cache(app))
            .setUpstreamDataSourceFactory(http)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DefaultMediaSourceFactory(DefaultDataSource.Factory(app, cached))
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
        .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(androidx.media3.common.C.WAKE_MODE_LOCAL)
        .setSeekBackIncrementMs(PLAYBACK_SEEK_INCREMENT_MS)
        .setSeekForwardIncrementMs(PLAYBACK_SEEK_INCREMENT_MS)
        .build()

    init {
        player.addListener(speechListener)
        scope.launch {
            launch(Dispatchers.IO) { runCatching { speech.loadInstalled() } }
            var ticks = 0
            while (isActive) { speech.positionMs = player.currentPosition; if (++ticks % 20 == 0) savePosition(); delay(100) }
        }
    }

    private var uri: Uri? = null
    private var httpRequestKey: String? = null
    val sourceRevision: Long get() = policy.sourceRevision

    fun open(value: Uri, epoch: Long): Boolean {
        checkMain()
        if (!policy.ownsPresentation(epoch)) return false
        if (httpRequestKey == null && uri == value && player.mediaItemCount > 0) return true
        savePosition()
        uri = value
        recentSource = RecentVideoSource.local(value.toString())
        recentRecordedKey = null
        httpRequestKey = null
        check(policy.acceptSource(epoch))
        val source = PlaybackSessionSource(value.toString())
        capturedSource = source
        val revision = sourceRevision
        captionPublication.invalidate(); currentCaptionTrack = null
        speech.invalidate(clear = true)
        if (!ownsSource(epoch, revision, source)) return false
        player.setMediaItem(MediaItem.fromUri(value), positions.getLong(positionKey(value), 0L))
        if (!ownsSource(epoch, revision, source)) return false
        player.prepare()
        if (!ownsSource(epoch, revision, source)) return false
        player.playWhenReady = true
        if (!ownsSource(epoch, revision, source)) return false
        publishLifecycle()
        return true
    }

    fun openHttp(
        epoch: Long,
        value: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        audioUrl: String? = null,
        audioHeaders: Map<String, String> = emptyMap(),
        refreshFromRevision: Long? = null,
        sourceResolutionId: String? = null,
        providerCaptions: ProviderCaptionInventory? = null,
        videoMimeType: String? = null, audioMimeType: String? = null
    ): Boolean {
        checkMain()
        if (!policy.ownsPresentation(epoch)) return false
        if (refreshFromRevision != null && refreshFromRevision != sourceRevision) return false
        val videoHeaders = headers.toMap()
        val soundHeaders = audioHeaders.toMap()
        val capturedVideoMime = com.mangalens.download.MediaTransportMime.capture(videoMimeType)
        val capturedAudioMime = audioMimeType.takeIf { !audioUrl.isNullOrBlank() }?.let(com.mangalens.download.MediaTransportMime::capture)
        val captionInventory = providerCaptions?.captureSnapshot()
        if (captionInventory != null && (sourceResolutionId?.matches(Regex("[a-f0-9]{32}")) != true ||
            !runCatching { captionInventory.validate(); true }.getOrDefault(false))) return false
        val captionResolution = sourceResolutionId.takeIf { captionInventory != null }
        val requestKey = buildString {
            append(value).append('\n')
            append(referer.orEmpty()).append('\n')
            videoHeaders.entries.sortedBy { it.key.lowercase() }.forEach { (name, headerValue) ->
                append(name.lowercase()).append('=').append(headerValue).append('\n')
            }
            append("audio=").append(audioUrl.orEmpty()).append('\n')
            soundHeaders.entries.sortedBy { it.key.lowercase() }.forEach { (name, headerValue) ->
                append("audio-").append(name.lowercase()).append('=').append(headerValue).append('\n')
            }
            append(com.mangalens.download.MediaTransportMime.identitySuffix(capturedVideoMime, capturedAudioMime))
            if (captionInventory != null) append("provider-resolution=").append(captionResolution).append('\n')
                .append("provider-inventory=").append(captionInventory.fingerprint()).append('\n')
        }
        if (httpRequestKey == requestKey && player.mediaItemCount > 0) return true

        // Each media source owns a request-context snapshot. This matters for signed/CDN media:
        // redirects and segment requests keep the source-page Referer/Cookie instead of inheriting
        // whichever WebView page happened to load most recently.
        val videoFactory = sourceFactory(
            MediaPlaybackDataSource.factory(MediaRequestContext(value, referer, videoHeaders))
        )
        val videoSource = videoFactory.createMediaSource(playbackHttpMediaItem(value, capturedVideoMime))
        val source = if (!audioUrl.isNullOrBlank()) {
            val audioFactory = sourceFactory(
                MediaPlaybackDataSource.factory(MediaRequestContext(audioUrl, referer, soundHeaders))
            )
            val audioSource = audioFactory.createMediaSource(playbackHttpMediaItem(audioUrl, capturedAudioMime))
            MergingMediaSource(videoSource, audioSource)
        } else {
            videoSource
        }
        // Capture after building the source and on the player's thread. A resolver-start
        // snapshot could already be stale because playback or a user seek continued.
        if (refreshFromRevision != null && refreshFromRevision != sourceRevision) return false
        savePosition()
        val nextRecentSource = RecentVideoSource.online(referer ?: value)
        val savedPosition = positions.getLong(positionKey(Uri.parse(nextRecentSource?.uri ?: value)),
            positions.getLong(positionKey(Uri.parse(value)), 0L))
        val capturedPlayWhenReady = player.playWhenReady
        val start = playbackReplacementStart(
            sourceRevision, refreshFromRevision,
            if (refreshFromRevision != null) playbackContinuationSnapshot(player) else null,
            savedPosition
        )
        if (start == PlaybackReplacementStart.Superseded) return false
        uri = Uri.parse(value)
        recentSource = nextRecentSource
        recentRecordedKey = null
        httpRequestKey = requestKey
        check(policy.acceptSource(epoch))
        val nextSource = PlaybackSessionSource(value, referer, videoHeaders, audioUrl, soundHeaders, captionResolution, captionInventory, capturedVideoMime, capturedAudioMime)
        capturedSource = nextSource
        val revision = sourceRevision
        captionPublication.invalidate(); currentCaptionTrack = null
        speech.invalidate(clear = true)
        if (!ownsSource(epoch, revision, nextSource)) return false
        if (!preparePlaybackReplacementWhileOwned(
            current = { ownsSource(epoch, revision, nextSource) },
            refreshFromRevision = refreshFromRevision,
            capturedPlayWhenReady = capturedPlayWhenReady,
            replaceSource = { applyPlaybackReplacementStart(player, source, start) },
            prepare = { player.prepare() },
            setPlayWhenReady = { player.playWhenReady = it }
        )) return false
        publishLifecycle()
        return true
    }

    fun bind(view: PlayerView) {
        view.player = player
    }

    fun sourceSnapshot(): PlaybackSessionSource? = capturedSource?.captured()

    fun captureCaptionSource(epoch: Long): CaptionSourceTicket? {
        checkMain()
        val source = capturedSource ?: return null
        if (!ownsSource(epoch, sourceRevision, source) || player.currentMediaItem?.localConfiguration?.uri?.toString() != source.uri) return null
        return captionPublication.capture(sourceRevision, source.uri)
    }

    fun captionSourceMatches(epoch: Long, ticket: CaptionSourceTicket): Boolean {
        checkMain()
        val source = capturedSource ?: return false
        return ownsSource(epoch, ticket.sourceRevision, source) &&
            captionPublication.accepts(ticket, sourceRevision, source.uri) &&
            player.currentMediaItem?.localConfiguration?.uri?.toString() == source.uri
    }

    /** The private caption store already verified these bytes on IO before this player-thread call. */
    fun applyImportedCaption(epoch: Long, ticket: CaptionSourceTicket, track: PlayerCaptionTrack): Boolean {
        checkMain()
        if (!captionSourceMatches(epoch, ticket) || !isManagedCaption(track)) return false
        val source = requireNotNull(capturedSource).captured()
        val item = requireNotNull(player.currentMediaItem).buildUpon().setSubtitleConfigurations(listOf(
            MediaItem.SubtitleConfiguration.Builder(track.uri).setMimeType(track.mimeType)
                .setLanguage(track.language).setLabel(track.label).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
        )).build()
        val replacement = sourceWithItem(source, item)
        val position = player.currentPosition.coerceAtLeast(0)
        val playing = player.playWhenReady
        val parameters = player.playbackParameters
        val tracks = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setPreferredTextLanguages(track.language)
            .setSelectUndeterminedTextLanguage(true).build()
        fun current() = captionSourceMatches(epoch, ticket) && capturedSource == source
        val applied = mutatePlaybackWhileOwned(::current, listOf(
            { player.setMediaSource(replacement, position) },
            { player.trackSelectionParameters = tracks },
            { player.playbackParameters = parameters },
            { player.prepare() },
            { player.playWhenReady = playing },
            { currentCaptionTrack = track.copy() },
            // Native text is now selected. Retire live/generated overlay publication to avoid duplicate captions.
            { speech.setEnabled(false) },
            { speech.invalidate(clear = true) }
        ))
        if (applied) publishLifecycle()
        return applied
    }

    private fun sourceWithItem(source: PlaybackSessionSource, item: MediaItem): MediaSource {
        val video = sourceFactory(MediaPlaybackDataSource.factory(MediaRequestContext(source.uri, source.referer, source.headers)))
            .createMediaSource(item.buildUpon().setMimeType(source.videoMimeType).build())
        val audioUrl = source.audioUrl
        return if (!audioUrl.isNullOrBlank()) {
            val audio = sourceFactory(MediaPlaybackDataSource.factory(MediaRequestContext(audioUrl, source.referer, source.audioHeaders)))
                .createMediaSource(playbackHttpMediaItem(audioUrl, source.audioMimeType))
            MergingMediaSource(video, audio)
        } else video
    }

    private fun isManagedCaption(track: PlayerCaptionTrack): Boolean {
        if (track.mimeType != "application/x-subrip" ||
            track.language.length !in 1..64 || track.label.length !in 1..160 ||
            track.language.any { it.isISOControl() } || track.label.any { it.isISOControl() }) return false
        return runCatching {
            val directory = java.io.File(app.filesDir, "captions/imported").canonicalFile
            fun valid(uri: Uri, sha: String, bytes: Long): Boolean {
                if (uri.scheme != "file" || uri.query != null || uri.fragment != null || !uri.authority.isNullOrEmpty() ||
                    !sha.matches(Regex("[a-f0-9]{64}")) || bytes !in 1..(2L * 1024 * 1024)) return false
                val file = java.io.File(requireNotNull(uri.path))
                return file.isAbsolute && file.canonicalFile.parentFile == directory &&
                    file.canonicalFile.name == "$sha.srt" && file.isFile && file.length() == bytes
            }
            valid(track.uri, track.sha256, track.bytes) && valid(track.sourceUri, track.sourceSha256, track.sourceBytes)
        }.getOrDefault(false)
    }

    private fun ownsSource(epoch: Long, revision: Long, source: PlaybackSessionSource) =
        policy.ownsPresentation(epoch) && sourceRevision == revision && capturedSource == source

    fun canAttachGenerated(epoch: Long, task: SubtitleGenerationTask): Boolean {
        checkMain()
        return currentCaptionTrack == null && matchesGeneratedSource(epoch, task)
    }

    /** Called only after the facade verifies the exact saved generation and its source on IO. */
    fun selectGeneratedCaption(epoch: Long, task: SubtitleGenerationTask): Boolean {
        checkMain()
        if (!matchesGeneratedSource(epoch, task)) return false
        val ticket = captureCaptionSource(epoch) ?: return false
        val tracks = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        val applied = mutatePlaybackWhileOwned({ captionSourceMatches(epoch, ticket) && matchesGeneratedSource(epoch, task) }, listOf(
            { player.trackSelectionParameters = tracks },
            { currentCaptionTrack = null },
            { speech.applyGeneratedCues(task.cues, task.config.outputMode) }
        ))
        if (applied) publishLifecycle()
        return applied
    }

    private fun matchesGeneratedSource(epoch: Long, task: SubtitleGenerationTask): Boolean {
        val source = capturedSource ?: return false
        if (!policy.ownsPresentation(epoch) || player.currentMediaItem?.localConfiguration?.uri?.toString() != source.uri ||
            task.validationPending || task.pcmValidationRequired || !hasSubtitlePlaybackProof(task) || task.cues.isEmpty()) return false
        if (SubtitleGenerationStore.shared(app).get(task.id) != task) return false
        val audio = source.audioUrl?.takeIf { it.isNotBlank() }
        val input = if (audio != null) audio else source.uri
        val headers = if (audio != null) source.audioHeaders else source.headers
        return task.source.source.uri == input && task.source.source.headers == headers &&
            matchesProviderPlaybackSource(task, source.sourceResolutionId, source.providerCaptions)
    }

    fun addClient() { checkMain(); policy.addClient() }
    fun attachPresentation(): Long { checkMain(); return policy.attachPresentation().also { publishLifecycle() } }
    fun resumePresentation(epoch: Long) {
        checkMain()
        policy.resumePresentation(epoch); publishLifecycle()
    }
    fun stopPresentation(epoch: Long, configuration: Boolean) {
        checkMain()
        if (policy.stopPresentation(epoch, configuration)) {
            if (policy.backgroundRequested && !policy.backgroundActive && player.playWhenReady)
                policy.retainBackgroundContinuation()
            player.pause()
        }
        savePosition(); publishLifecycle()
    }
    fun detachPresentation(epoch: Long, configuration: Boolean = false) {
        checkMain()
        if (policy.detachPresentation(epoch, configuration)) {
            if (policy.backgroundRequested && !policy.backgroundActive && player.playWhenReady)
                policy.retainBackgroundContinuation()
            player.pause()
        }
        publishLifecycle(); PlaybackSessions.releaseIfIdle(this)
    }
    fun removeClient(epoch: Long) {
        checkMain(); detachPresentation(epoch); policy.removeClient(); PlaybackSessions.releaseIfIdle(this)
    }
    fun requestBackground(epoch: Long, enabled: Boolean): Boolean {
        checkMain()
        if (!policy.requestBackground(epoch, enabled)) return false
        val requestEpoch = policy.backgroundEpoch
        if (enabled) {
            try {
                androidx.core.content.ContextCompat.startForegroundService(app, VideoPlaybackService.startIntent(app, policy.sessionId, requestEpoch))
                scope.launch {
                    delay(10_000)
                    if (!policy.closed && policy.backgroundEpoch == requestEpoch && policy.backgroundRequested && !policy.backgroundActive) {
                        policy.serviceStopped(policy.sessionId, requestEpoch)
                        if (policy.shouldPause) player.pause()
                        publishLifecycle("Background playback did not start. Return to the player and try again.")
                        PlaybackSessions.releaseIfIdle(this@PlaybackSession)
                    }
                }
            }
            catch (failure: RuntimeException) {
                policy.stopBackground(policy.sessionId)
                if (policy.shouldPause) player.pause()
                publishLifecycle("Background playback could not start. Return to the player and try again.")
                return false
            }
        } else app.stopService(android.content.Intent(app, VideoPlaybackService::class.java))
        publishLifecycle(); return true
    }
    fun setPictureInPicture(epoch: Long, enabled: Boolean, pauseWhenStopped: Boolean = true): Boolean {
        checkMain(); if (!policy.setPictureInPicture(epoch, enabled)) return false
        if (pauseWhenStopped && policy.shouldPause) player.pause()
        publishLifecycle(); return true
    }
    fun reportStatus(message: String) { checkMain(); publishLifecycle(message) }
    fun serviceStarted(id: String, epoch: Long): Boolean {
        checkMain()
        return policy.acknowledgeService(id, epoch).also { accepted ->
            if (accepted && policy.consumeBackgroundContinuation(id, epoch)) player.play()
            publishLifecycle()
        }
    }
    fun serviceStopped(id: String, epoch: Long) {
        checkMain(); policy.serviceStopped(id, epoch)
        if (policy.shouldPause) player.pause()
        publishLifecycle(); PlaybackSessions.releaseIfIdle(this)
    }
    fun acceptsControl(receipt: PlaybackControlReceipt) = policy.acceptsControl(receipt)
    fun stopFromControl(receipt: PlaybackControlReceipt): Boolean {
        checkMain(); if (!acceptsControl(receipt)) return false
        savePosition(); policy.stopBackground(policy.sessionId); player.pause(); player.stop()
        app.stopService(android.content.Intent(app, VideoPlaybackService::class.java))
        publishLifecycle(); return true
    }
    fun publishLifecycle(status: String? = null) {
        lifecycle.value = PlaybackLifecycleSnapshot(policy.backgroundRequested, policy.backgroundActive,
            policy.inPictureInPicture, status ?: lifecycle.value.status, sourceRevision, currentCaptionTrack)
    }
    fun dispose() {
        checkMain(); if (disposed) return
        disposed = true; savePosition(); policy.close()
        player.removeListener(speechListener); player.release()
        // close() keeps its native barrier; the session job is cancelled only after it returns.
        scope.launch { try { withContext(NonCancellable + Dispatchers.IO) { speech.close() } } finally { scope.cancel() } }
    }
    private fun checkMain() { check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) }
}

internal data class PlaybackLifecycleSnapshot(
    val backgroundRequested: Boolean = false,
    val backgroundActive: Boolean = false,
    val inPictureInPicture: Boolean = false,
    val status: String = "",
    val sourceRevision: Long = 0L,
    val nativeCaption: PlayerCaptionTrack? = null
)

internal data class PlaybackSessionSource(
    val uri: String,
    val referer: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val audioUrl: String? = null,
    val audioHeaders: Map<String, String> = emptyMap(),
    val sourceResolutionId: String? = null,
    val providerCaptions: ProviderCaptionInventory? = null,
    val videoMimeType: String? = null,
    val audioMimeType: String? = null
) {
    fun captured() = copy(headers = headers.toMap(), audioHeaders = audioHeaders.toMap(), providerCaptions = providerCaptions?.captureSnapshot())
    val online get() = uri.startsWith("https://", true) || uri.startsWith("http://", true)
}

internal object PlaybackSessions {
    private var current: PlaybackSession? = null
    fun acquire(app: Application): PlaybackSession {
        check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper())
        return (current ?: PlaybackSession(app).also { current = it }).also { it.addClient() }
    }
    fun find(id: String): PlaybackSession? = current?.takeIf { it.policy.sessionId == id && !it.policy.closed }
    fun releaseIfIdle(session: PlaybackSession) {
        if (current === session && session.policy.canRelease) { current = null; session.dispose() }
    }
}
