package com.mangalens.ui.video

import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.flow.MutableStateFlow

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
interface PlaybackWindowHost { val playbackWindow: PlaybackWindowController }

internal enum class PlaybackOrientation { SYSTEM, PORTRAIT, LANDSCAPE }
internal data class PlaybackWindowState(
    val fullscreen: Boolean = true,
    val orientation: PlaybackOrientation = PlaybackOrientation.SYSTEM,
    val pipOnHome: Boolean = false
)

/** Activity window ownership is distinct from source/native ownership. */
@UnstableApi
class PlaybackWindowController(private val activity: ComponentActivity) {
    internal val state = MutableStateFlow(PlaybackWindowState())
    private var vm: LocalVideoPlayerViewModel? = null
    private var presentationEpoch = 0L
    private var view: PlayerView? = null
    private var previousOrientation: Int? = null
    private var previousUi: Int? = null
    private var previousBrightness: Float? = null
    private var previousKeepScreen = false
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            keepScreen(); if (activity.isInPictureInPictureMode) updatePipParams()
        }
    }
    internal val pipAvailable get() = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    internal fun attach(owner: LocalVideoPlayerViewModel): Long {
        vm?.player?.removeListener(listener)
        view?.player = null; view = null
        if (previousOrientation == null) {
            previousOrientation = activity.requestedOrientation
            @Suppress("DEPRECATION")
            previousUi = activity.window.decorView.systemUiVisibility
            previousBrightness = activity.window.attributes.screenBrightness
            previousKeepScreen = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        }
        vm = owner; presentationEpoch = owner.attachPresentation(); owner.player.addListener(listener)
        applyWindow(); keepScreen()
        return presentationEpoch
    }
    internal fun bindView(owner: LocalVideoPlayerViewModel, playerView: PlayerView?, epoch: Long) {
        if (vm === owner && presentationEpoch == epoch) view = playerView
    }
    internal fun detach(owner: LocalVideoPlayerViewModel, epoch: Long = presentationEpoch) {
        if (vm !== owner || presentationEpoch != epoch) return
        owner.player.removeListener(listener)
        view?.player = null; view = null
        owner.detachPresentation(activity.isChangingConfigurations, epoch)
        vm = null
        previousOrientation?.let { activity.requestedOrientation = it }; previousOrientation = null
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        @Suppress("DEPRECATION")
        previousUi?.let { activity.window.decorView.systemUiVisibility = it }; previousUi = null
        previousBrightness?.let { brightness -> activity.window.attributes = activity.window.attributes.apply { screenBrightness = brightness } }
        previousBrightness = null
        if (!previousKeepScreen) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    fun onStart() { vm?.onPresentationResumed(presentationEpoch) }
    fun onStop() { vm?.onPresentationStopped(activity.isChangingConfigurations, presentationEpoch) }
    fun close() { vm?.let { detach(it, presentationEpoch) } }
    fun onUserLeaveHint() {
        val owner = vm ?: return
        if (state.value.pipOnHome && owner.player.playWhenReady) enterPictureInPicture()
    }
    fun onPictureInPictureModeChanged(enabled: Boolean) {
        val owner = vm ?: return
        // Android may report PiP exit before onStart when expanding the task. Actual stop or
        // presentation disposal pauses a closed PiP; the exit callback must not pause expansion.
        owner.session.setPictureInPicture(presentationEpoch, enabled, pauseWhenStopped = enabled)
        if (!enabled) applyWindow()
    }
    internal fun setFullscreen(enabled: Boolean) {
        state.value = state.value.copy(fullscreen = enabled); applyWindow()
    }
    internal fun setOrientation(value: PlaybackOrientation) {
        state.value = state.value.copy(orientation = value); applyWindow()
    }
    internal fun setPipOnHome(enabled: Boolean) { state.value = state.value.copy(pipOnHome = enabled) }
    internal fun enterPictureInPicture(): Boolean {
        val owner = vm ?: return false
        if (!pipAvailable || owner.player.mediaItemCount == 0 || !owner.session.policy.ownsPresentation(presentationEpoch)) {
            owner.session.reportStatus("Picture-in-picture is unavailable for this player or device."); return false
        }
        return try {
            val entered = activity.enterPictureInPictureMode(pipParams())
            if (entered) owner.session.setPictureInPicture(presentationEpoch, true)
            else owner.session.reportStatus("Picture-in-picture was denied. Allow it in Android app settings.")
            entered
        } catch (failure: RuntimeException) {
            owner.session.reportStatus("Picture-in-picture was denied. Allow it in Android app settings."); false
        }
    }
    private fun updatePipParams() { runCatching { activity.setPictureInPictureParams(pipParams()) } }
    private fun pipParams(): PictureInPictureParams {
        val owner = checkNotNull(vm)
        val size = owner.player.videoSize
        val ratio = if (size.width > 0 && size.height > 0)
            (size.width * size.pixelWidthHeightRatio / size.height).coerceIn(1f / 2.39f, 2.39f) else 16f / 9f
        val receipt = owner.session.policy.controlReceipt()
        val play = owner.player.playWhenReady
        val action = RemoteAction(Icon.createWithResource(activity, if (play) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
            if (play) "Pause" else "Play", if (play) "Pause video" else "Play video",
            PlaybackControlReceiver.pendingIntent(activity, receipt, if (play) VideoPlaybackService.ACTION_PAUSE else VideoPlaybackService.ACTION_PLAY))
        val builder = PictureInPictureParams.Builder().setAspectRatio(Rational((ratio * 1000).toInt(), 1000)).setActions(listOf(action))
        val rect = Rect()
        if (view?.getGlobalVisibleRect(rect) == true && !rect.isEmpty) builder.setSourceRectHint(rect)
        if (Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(false)
        return builder.build()
    }
    private fun applyWindow() {
        if (vm == null || activity.isInPictureInPictureMode) return
        activity.requestedOrientation = when (state.value.orientation) {
            PlaybackOrientation.SYSTEM -> previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            PlaybackOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            PlaybackOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        WindowCompat.setDecorFitsSystemWindows(activity.window, !state.value.fullscreen)
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            if (state.value.fullscreen) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
    private fun keepScreen() {
        if (vm?.player?.isPlaying == true && !activity.isInPictureInPictureMode)
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else if (!previousKeepScreen) activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    fun restore(bundle: Bundle?) {
        if (bundle == null) return
        state.value = PlaybackWindowState(bundle.getBoolean("playback_fullscreen", true),
            PlaybackOrientation.entries.getOrNull(bundle.getInt("playback_orientation", 0)) ?: PlaybackOrientation.SYSTEM,
            bundle.getBoolean("playback_pip_home", false))
    }
    fun save(bundle: Bundle) {
        bundle.putBoolean("playback_fullscreen", state.value.fullscreen)
        bundle.putInt("playback_orientation", state.value.orientation.ordinal)
        bundle.putBoolean("playback_pip_home", state.value.pipOnHome)
    }
}
