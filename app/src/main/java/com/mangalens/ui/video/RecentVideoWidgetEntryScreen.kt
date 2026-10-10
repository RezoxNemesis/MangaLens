package com.mangalens.ui.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** User-selected native history key; cold loading is bounded and final opening rechecks authority. */
@Composable
internal fun RecentVideoWidgetEntryScreen(key: String, lifecycle: Lifecycle, isCurrent: () -> Boolean,
    opening: Boolean, error: String?, onOpen: (String, () -> Boolean) -> Unit, onCancel: () -> Unit, onWatch: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val authority = remember(key, lifecycle) { RecentVideoWidgetEntryAuthority() }
    val latestCurrent by rememberUpdatedState(isCurrent)
    val latestCancel by rememberUpdatedState(onCancel)
    val latestOpen by rememberUpdatedState(onOpen)
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState == Lifecycle.State.RESUMED) }
    var attempted by remember(key, lifecycle) { mutableStateOf(false) }
    var retry by remember(key, lifecycle) { mutableIntStateOf(0) }
    var loading by remember(key, lifecycle) { mutableStateOf(false) }
    var notice by remember(key, lifecycle) { mutableStateOf<String?>(null) }
    DisposableEffect(lifecycle, authority) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumed = true
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                authority.retire() // Synchronous; a later resume cannot restore this ticket.
                resumed = false; loading = false; latestCancel()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { authority.close(); lifecycle.removeObserver(observer); latestCancel() }
    }
    LaunchedEffect(key, resumed, retry, authority) {
        if (!resumed || lifecycle.currentState != Lifecycle.State.RESUMED || attempted || !latestCurrent()) return@LaunchedEffect
        val ticket = authority.capture()
        fun current() = authority.isCurrent(ticket) && lifecycle.currentState == Lifecycle.State.RESUMED && latestCurrent()
        attempted = true; loading = true; notice = null
        try {
            require(key.matches(Regex("[a-f0-9]{64}")))
            withTimeout(8_000) {
                val store = withContext(Dispatchers.IO) { RecentVideoStore.shared(app) }
                store.state.first { !it.loading }
            }
            if (current()) latestOpen(key, ::current)
        } catch (_: TimeoutCancellationException) {
            if (current()) notice = "Recent video history did not respond in time. Try again or open Watch."
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (current()) notice = "This recent video is unavailable. Open Watch to select a source again."
        } finally { if (current()) loading = false }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Continue Watching", style = MaterialTheme.typography.headlineSmall)
        Text("Opening the current saved video with fresh file access or source resolution.")
        if (loading || opening) LinearProgressIndicator()
        (notice ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (attempted && !loading && !opening) Button({ authority.retire(); attempted = false; retry++ }) { Text("Try again") }
        if (opening) TextButton({ authority.retire(); latestCancel(); loading = false }) { Text("Cancel opening") }
        TextButton({ authority.retire(); latestCancel(); onWatch() }) { Text("Open Watch") }
    }
}
