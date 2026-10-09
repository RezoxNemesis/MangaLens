package com.mangalens.ui.video

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView

private fun Context.playbackHost(): PlaybackWindowHost? = when (this) {
    is PlaybackWindowHost -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.playbackHost() else null
    else -> null
}

@UnstableApi
@Composable
internal fun PlayerPresentationLifecycle(vm: LocalVideoPlayerViewModel, view: PlayerView?): Long {
    val context = LocalContext.current
    val host = remember(context) { context.playbackHost() }
    val lease = remember(vm, host) { androidx.compose.runtime.mutableLongStateOf(0L) }
    DisposableEffect(vm, host) {
        val epoch = if (host != null) host.playbackWindow.attach(vm) else vm.attachPresentation()
        lease.longValue = epoch
        onDispose { if (host != null) host.playbackWindow.detach(vm, epoch) else vm.detachPresentation(epoch = epoch) }
    }
    androidx.compose.runtime.SideEffect { host?.playbackWindow?.bindView(vm, view, lease.longValue) }
    return lease.longValue
}

@UnstableApi
@Composable
internal fun PlayerLifecycleControlsPanel(vm: LocalVideoPlayerViewModel) {
    val context = LocalContext.current
    val host = remember(context) { context.playbackHost() }
    val life by vm.lifecycle.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Playback in Android")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (life.backgroundActive) "Background audio active" else "Background audio")
            Switch(checked = life.backgroundRequested, onCheckedChange = { vm.requestBackground(it) },
                modifier = Modifier.semantics { contentDescription = "Background audio" })
        }
        host?.playbackWindow?.let { window ->
            val state by window.state.collectAsState()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { window.enterPictureInPicture() }, enabled = window.pipAvailable) { Text("Picture-in-picture") }
                TextButton(onClick = { window.setFullscreen(!state.fullscreen) }) { Text(if (state.fullscreen) "Exit fullscreen" else "Fullscreen") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Picture-in-picture on Home")
                Switch(checked = state.pipOnHome, enabled = window.pipAvailable, onCheckedChange = window::setPipOnHome)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (mode in PlaybackOrientation.entries) TextButton(onClick = { window.setOrientation(mode) }) {
                    Text((if (state.orientation == mode) "✓ " else "") + mode.name.lowercase().replaceFirstChar(Char::uppercase))
                }
            }
        }
        if (life.status.isNotBlank()) Text(life.status)
    }
}
