package com.mangalens.ui.video

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.util.UnstableApi

/** Internal immutable PiP actions, bound to one session and source revision. */
@UnstableApi
class PlaybackControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(VideoPlaybackService.EXTRA_SESSION) ?: return
        val current = PlaybackSessions.find(id) ?: return
        val receipt = PlaybackControlReceipt(id, intent.getLongExtra(VideoPlaybackService.EXTRA_REVISION, -1))
        if (!current.acceptsControl(receipt)) return
        when (intent.action) {
            VideoPlaybackService.ACTION_PLAY -> current.player.play()
            VideoPlaybackService.ACTION_PAUSE -> current.player.pause()
        }
    }
    companion object {
        internal fun pendingIntent(context: Context, receipt: PlaybackControlReceipt, action: String): PendingIntent =
            PendingIntent.getBroadcast(context, 0, Intent(context, PlaybackControlReceiver::class.java).setAction(action)
                .setData(Uri.parse("mangalens://pip-control/${receipt.sessionId}/${receipt.sourceRevision}/$action"))
                .putExtra(VideoPlaybackService.EXTRA_SESSION, receipt.sessionId)
                .putExtra(VideoPlaybackService.EXTRA_REVISION, receipt.sourceRevision),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
