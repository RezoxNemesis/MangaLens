package com.mangalens.ui.video

import android.app.Application
import android.net.Uri
import com.mangalens.download.ProviderCaptionInventory
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

/** A presentation lease; playback/native lifetimes are owned by PlaybackSession. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class LocalVideoPlayerViewModel(app: Application) : AndroidViewModel(app) {
    internal val session = PlaybackSessions.acquire(app)
    internal var presentation = session.attachPresentation()
        private set
    val player get() = session.player
    val speech get() = session.speech
    internal val lifecycle get() = session.lifecycle
    internal val sourceRevision get() = session.sourceRevision
    internal val currentCaptionTrack get() = session.currentCaptionTrack.takeIf { session.policy.ownsPresentation(presentation) }
    internal fun captureCaptionSource() = session.captureCaptionSource(presentation)
    internal fun captionSourceMatches(ticket: CaptionSourceTicket) = session.captionSourceMatches(presentation, ticket)
    internal fun applyImportedCaption(ticket: CaptionSourceTicket, track: PlayerCaptionTrack) = session.applyImportedCaption(presentation, ticket, track)
    internal fun canAttachGenerated(task: SubtitleGenerationTask) = session.canAttachGenerated(presentation, task)
    internal fun selectGeneratedCaption(task: SubtitleGenerationTask) = session.selectGeneratedCaption(presentation, task)

    fun open(value: Uri) { session.open(value, presentation) }
    fun openHttp(value: String, referer: String? = null, headers: Map<String, String> = emptyMap(),
        audioUrl: String? = null, audioHeaders: Map<String, String> = emptyMap(), refreshFromRevision: Long? = null,
        sourceResolutionId: String? = null, providerCaptions: ProviderCaptionInventory? = null): Boolean =
        session.openHttp(presentation, value, referer, headers, audioUrl, audioHeaders, refreshFromRevision, sourceResolutionId, providerCaptions)
    fun bind(view: PlayerView, epoch: Long = presentation) { view.player = player.takeIf { session.policy.ownsPresentation(epoch) } }
    internal fun attachPresentation(): Long {
        presentation = session.attachPresentation()
        return presentation
    }
    internal fun detachPresentation(configuration: Boolean = false, epoch: Long = presentation) = session.detachPresentation(epoch, configuration)
    internal fun onPresentationStopped(configuration: Boolean, epoch: Long = presentation) = session.stopPresentation(epoch, configuration)
    internal fun onPresentationResumed(epoch: Long = presentation) = session.resumePresentation(epoch)
    internal fun requestBackground(enabled: Boolean) = session.requestBackground(presentation, enabled)
    override fun onCleared() { session.removeClient(presentation); super.onCleared() }
}
