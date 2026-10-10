package com.mangalens.ui.reader

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import kotlinx.coroutines.flow.collect

/** Reads the existing transition; preserves its Boolean visibility, placement and animation. */
@Composable
internal fun AnimatedVisibilityScope.readerHudDrawProbe(
    component: ReaderHudComponent,
    observe: (ReaderHudPhase, ReaderHudComponent, ReaderHudAnimation, ReaderHudAnimation, Boolean) -> Unit
): Modifier {
    if (!ReaderHudDiagnostics.enabled) return Modifier
    val latestObserver by rememberUpdatedState(observe)
    val actualTransition = transition
    val lastDraw = remember(actualTransition) { arrayOfNulls<Triple<EnterExitState, EnterExitState, Boolean>>(1) }
    LaunchedEffect(actualTransition) {
        snapshotFlow { Triple(actualTransition.currentState, actualTransition.targetState, actualTransition.isRunning) }
            .collect { state ->
                runCatching { latestObserver(ReaderHudPhase.ANIMATION, component,
                    state.first.hudState(), state.second.hudState(), state.third) }
            }
    }
    DisposableEffect(actualTransition) {
        onDispose {
            runCatching { latestObserver(ReaderHudPhase.ANIMATION_DISPOSED, component,
                actualTransition.currentState.hudState(), actualTransition.targetState.hudState(), actualTransition.isRunning) }
        }
    }
    return Modifier.drawWithContent {
        drawContent()
        val state = Triple(actualTransition.currentState, actualTransition.targetState, actualTransition.isRunning)
        if (lastDraw[0] != state) {
            lastDraw[0] = state
            runCatching { latestObserver(ReaderHudPhase.DRAW, component,
                state.first.hudState(), state.second.hudState(), state.third) }
        }
    }
}

private fun EnterExitState.hudState(): ReaderHudAnimation = when (this) {
    EnterExitState.PreEnter -> ReaderHudAnimation.PRE_ENTER
    EnterExitState.Visible -> ReaderHudAnimation.VISIBLE
    EnterExitState.PostExit -> ReaderHudAnimation.POST_EXIT
}
