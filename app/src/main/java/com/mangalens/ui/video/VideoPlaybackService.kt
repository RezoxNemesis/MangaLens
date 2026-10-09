package com.mangalens.ui.video

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/** Owns the already opened player; this service never parses or reopens a network source. */
@UnstableApi
class VideoPlaybackService : Service() {
    private var session: PlaybackSession? = null
    private var mediaSession: MediaSession? = null
    private var serviceEpoch = 0L
    private val main = Handler(Looper.getMainLooper())
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) { publish() }
    }
    private val tick = object : Runnable {
        override fun run() { if (session != null) { publish(); main.postDelayed(this, 1000) } }
    }
    inner class LocalBinder : Binder() {
        fun sessionToken(): MediaSession.Token? = mediaSession?.sessionToken
    }
    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_SESSION) ?: run { stopSelfResult(startId); return START_NOT_STICKY }
        val current = PlaybackSessions.find(id)
        val epoch = intent.getLongExtra(EXTRA_EPOCH, -1)
        if (intent.action == ACTION_START) {
            if (current == null || !current.policy.backgroundRequested || epoch != current.policy.backgroundEpoch || current.player.mediaItemCount == 0) {
                if (session?.let { it.policy.isServiceOwner(it.policy.sessionId, serviceEpoch) } != true) stopSelfResult(startId)
                return START_NOT_STICKY
            }
            if (session !== current || serviceEpoch != epoch) {
                detach()
                session = current
                serviceEpoch = epoch
                mediaSession = MediaSession(this, "MangaLens playback").apply {
                    setCallback(object : MediaSession.Callback() {
                        override fun onPlay() = controlOwned(current, epoch, ACTION_PLAY)
                        override fun onPause() = controlOwned(current, epoch, ACTION_PAUSE)
                        override fun onStop() = controlOwned(current, epoch, ACTION_STOP)
                        override fun onRewind() = controlOwned(current, epoch, ACTION_REWIND)
                        override fun onFastForward() = controlOwned(current, epoch, ACTION_FORWARD)
                        override fun onSeekTo(pos: Long) {
                            if (session === current && current.policy.acceptsServiceControl(current.policy.controlReceipt(), epoch) && current.player.isCurrentMediaItemSeekable)
                                current.player.seekTo(pos.coerceAtLeast(0))
                        }
                    }, main)
                    isActive = true
                }
                getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL, "Video and background audio", NotificationManager.IMPORTANCE_LOW))
                try {
                    val notice = notification(current)
                    if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                    else startForeground(NOTIFICATION_ID, notice)
                    if (!current.serviceStarted(id, epoch)) { stopSelfResult(startId); return START_NOT_STICKY }
                    current.player.addListener(listener)
                    main.removeCallbacks(tick); main.post(tick)
                } catch (failure: RuntimeException) {
                    current.reportStatus("Background playback could not start. Return to the player and try again.")
                    detach()
                    stopSelfResult(startId)
                }
            } else publish()
        } else if (current === session && current?.policy?.isServiceOwner(id, epoch) == true) {
            val receipt = PlaybackControlReceipt(id, intent.getLongExtra(EXTRA_REVISION, -1))
            if (current.policy.acceptsServiceControl(receipt, epoch)) execute(current, intent.action, receipt)
        } else if (session == null) {
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private fun controlOwned(current: PlaybackSession, epoch: Long, action: String) {
        if (session !== current) return
        val receipt = current.policy.controlReceipt()
        if (current.policy.acceptsServiceControl(receipt, epoch)) execute(current, action, receipt)
    }
    private fun execute(current: PlaybackSession, action: String?, receipt: PlaybackControlReceipt) {
        when (action) {
            ACTION_PLAY -> { if (current.player.playbackState == Player.STATE_IDLE) current.player.prepare(); current.player.play() }
            ACTION_PAUSE -> current.player.pause()
            ACTION_REWIND -> if (current.player.isCurrentMediaItemSeekable) current.player.seekBack()
            ACTION_FORWARD -> if (current.player.isCurrentMediaItemSeekable) current.player.seekForward()
            ACTION_STOP -> { current.stopFromControl(receipt); stopSelf() }
        }
        if (action != ACTION_STOP) publish()
    }
    private fun publish() {
        val current = session ?: return
        if (!current.policy.isServiceOwner(current.policy.sessionId, serviceEpoch)) return
        val player = current.player
        val state = when {
            player.playbackState == Player.STATE_ENDED -> PlaybackState.STATE_STOPPED
            player.playbackState == Player.STATE_BUFFERING -> PlaybackState.STATE_BUFFERING
            player.isPlaying -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        var actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
        if (player.isCurrentMediaItemSeekable) actions = actions or PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_REWIND or PlaybackState.ACTION_FAST_FORWARD
        mediaSession?.setPlaybackState(PlaybackState.Builder().setActions(actions)
            .setState(state, player.currentPosition, if (player.isPlaying) player.playbackParameters.speed else 0f).build())
        val metadata = MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Video")
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "MangaLens")
        if (player.duration != C.TIME_UNSET && player.duration > 0) metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, player.duration)
        mediaSession?.setMetadata(metadata.build())
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(current))
    }
    private fun notification(current: PlaybackSession): Notification {
        val receipt = current.policy.controlReceipt()
        val resume = Intent(this, com.mangalens.MainActivity::class.java).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("mangalens://video/session/${receipt.sessionId}"))
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val content = PendingIntent.getActivity(this, 0, resume, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val playing = current.player.playWhenReady
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("MangaLens video").setContentText(if (playing) "Background audio playing" else "Playback paused")
            .setContentIntent(content).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(playing).setStyle(Notification.MediaStyle().setMediaSession(mediaSession!!.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_rew, "Back 10 seconds", commandIntent(this, receipt, ACTION_REWIND, serviceEpoch)).build())
            .addAction(Notification.Action.Builder(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play", commandIntent(this, receipt, if (playing) ACTION_PAUSE else ACTION_PLAY, serviceEpoch)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_ff, "Forward 10 seconds", commandIntent(this, receipt, ACTION_FORWARD, serviceEpoch)).build())
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "Stop", commandIntent(this, receipt, ACTION_STOP, serviceEpoch)).build())
            .setDeleteIntent(commandIntent(this, receipt, ACTION_STOP, serviceEpoch)).build()
    }
    override fun onDestroy() {
        detach(); stopForeground(STOP_FOREGROUND_REMOVE); super.onDestroy()
    }
    private fun detach() {
        main.removeCallbacks(tick)
        val previous = session; session = null
        previous?.player?.removeListener(listener)
        mediaSession?.release(); mediaSession = null
        previous?.serviceStopped(previous.policy.sessionId, serviceEpoch)
        serviceEpoch = 0L
    }
    companion object {
        internal const val CHANNEL = "video_playback"
        internal const val NOTIFICATION_ID = 7402
        internal const val EXTRA_SESSION = "playback_session"
        internal const val EXTRA_REVISION = "playback_revision"
        internal const val EXTRA_EPOCH = "playback_service_epoch"
        internal const val ACTION_START = "com.mangalens.PLAYBACK_START"
        internal const val ACTION_PLAY = "com.mangalens.PLAYBACK_PLAY"
        internal const val ACTION_PAUSE = "com.mangalens.PLAYBACK_PAUSE"
        internal const val ACTION_REWIND = "com.mangalens.PLAYBACK_REWIND"
        internal const val ACTION_FORWARD = "com.mangalens.PLAYBACK_FORWARD"
        internal const val ACTION_STOP = "com.mangalens.PLAYBACK_STOP"
        internal fun startIntent(context: Context, id: String, epoch: Long) = Intent(context, VideoPlaybackService::class.java)
            .setAction(ACTION_START).putExtra(EXTRA_SESSION, id).putExtra(EXTRA_EPOCH, epoch)
        internal fun commandIntent(context: Context, receipt: PlaybackControlReceipt, action: String, epoch: Long): PendingIntent {
            val command = Intent(context, VideoPlaybackService::class.java).setAction(action)
                .setData(Uri.parse("mangalens://playback-control/${receipt.sessionId}/${receipt.sourceRevision}/$epoch/$action"))
                .putExtra(EXTRA_SESSION, receipt.sessionId).putExtra(EXTRA_REVISION, receipt.sourceRevision).putExtra(EXTRA_EPOCH, epoch)
            return PendingIntent.getService(context, 0, command, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
