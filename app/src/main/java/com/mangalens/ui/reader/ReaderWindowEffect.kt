package com.mangalens.ui.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** MainActivity handles orientation configuration changes; the Reader lease stays attached to that window. */
@Composable
internal fun ReaderWindowEffect(chapterId: String, settings: ReaderWindowSettings) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = remember(context) { context.readerActivity() }
    if (activity == null) return
    val lease = remember(activity, chapterId) { ReaderWindowLease(activity) }
    val currentSettings by rememberUpdatedState(settings)
    var resumed by remember(lifecycle, chapterId) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lease, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { resumed = true; lease.update(currentSettings) }
            else if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                resumed = false; lease.deactivate()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); resumed = false; lease.close() }
    }
    SideEffect { if (resumed) lease.update(settings) }
}

private fun Context.readerActivity(): Activity? {
    var value: Context = this
    repeat(16) {
        if (value is Activity) return value as Activity
        if (value !is ContextWrapper) return null
        val next = (value as ContextWrapper).baseContext
        if (next === value) return null
        value = next
    }
    return null
}
