package com.mangalens.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mangalens.core.reader.SavedChapter
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Resolve a link only through the currently loaded Library and its ordinary Reader selection. */
@Composable
internal fun SavedChapterLinkScreen(chapterId: String, library: List<SavedChapter>, lifecycle: Lifecycle,
    isCurrentOwner: () -> Boolean, onOpen: (String) -> Boolean, onLibrary: () -> Unit) {
    val currentLibrary by rememberUpdatedState(library)
    val currentOwner by rememberUpdatedState(isCurrentOwner)
    val currentOpen by rememberUpdatedState(onOpen)
    var generation by remember(chapterId, lifecycle) { mutableLongStateOf(0L) }
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState == Lifecycle.State.RESUMED) }
    var message by remember(chapterId) { mutableStateOf("Opening saved chapter…") }
    DisposableEffect(chapterId, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                generation++; resumed = false
            }
            if (event == Lifecycle.Event.ON_RESUME) { generation++; resumed = true }
        }
        lifecycle.addObserver(observer)
        onDispose { generation++; lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(chapterId, resumed, generation) {
        if (!resumed || !chapterId.matches(Regex("[a-f0-9]{32}"))) return@LaunchedEffect
        val capturedGeneration = generation
        message = "Opening saved chapter…"
        val present = withTimeoutOrNull(8_000L) {
            snapshotFlow { currentLibrary.any { it.id == chapterId } }.first { it }
        } == true
        ensureActive()
        if (capturedGeneration != generation || lifecycle.currentState != Lifecycle.State.RESUMED || !currentOwner()) return@LaunchedEffect
        if (!present || !currentOpen(chapterId)) message = "This saved chapter is unavailable. Open the Library to select a chapter."
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Saved chapter", style = MaterialTheme.typography.headlineSmall)
        Text(message)
        Button(onClick = { if (lifecycle.currentState == Lifecycle.State.RESUMED && currentOwner()) onLibrary() }) { Text("Open Library") }
    }
}
