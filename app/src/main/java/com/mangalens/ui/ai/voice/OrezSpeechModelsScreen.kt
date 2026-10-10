package com.mangalens.ui.ai.voice

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mangalens.ui.video.SpeechModelInstallControls
import com.mangalens.ui.video.VideoSpeechEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** IO initialization/installer is process-owned until all actual jobs and native cleanup settle. */
@OptIn(ExperimentalCoroutinesApi::class)
internal object OrezSpeechModelControlsOwner {
    data class Lease(val engine: VideoSpeechEngine, val scope: CoroutineScope)
    data class State(val token: Long = 0L, val lease: Lease? = null, val message: String = "Preparing speech model controls…")
    private class Session(val owner: OwnedVoiceSlot.Owner) { var producer: Job? = null }
    private val capacity = OwnedVoiceSlot()
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable
    private var current: Session? = null
    private var retainedLease: Lease? = null

    /** Only memory registration/coroutine dispatch runs on Main; no settings or engine construction. */
    @Synchronized fun start(context: Context): Long? {
        val owner = capacity.acquire() ?: return null
        val session = Session(owner)
        current = session
        mutable.value = State(owner.token)
        session.producer = applicationScope.launch(start = CoroutineStart.ATOMIC) { initialize(session, context.applicationContext) }
        return owner.token
    }
    fun retire(token: Long) {
        capacity.retire(token) // Delivery detaches before cancellation; only app Context is retained.
        val work = synchronized(this) { current?.takeIf { it.owner.token == token }?.producer }
        work?.cancel()
    }
    private suspend fun initialize(session: Session, app: Context) {
        val modelJob = SupervisorJob()
        val scope = CoroutineScope(modelJob + Dispatchers.IO)
        var engine: VideoSpeechEngine? = null
        var proven = false
        try {
            if (!capacity.isCurrent(session.owner)) return
            engine = VideoSpeechEngine(app, scope) // Cold preferences and construction stay on IO.
            if (!capacity.isCurrent(session.owner)) return
            mutable.value = State(session.owner.token, Lease(requireNotNull(engine), scope))
            awaitCancellation()
        } catch (cancelled: CancellationException) { }
        catch (_: Exception) {
            if (capacity.isCurrent(session.owner)) mutable.value = State(session.owner.token,
                message = "Speech model controls could not be prepared. Close this page and try later.")
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try { modelJob.cancelAndJoin(); engine?.close(); proven = true } catch (_: Exception) { }
                synchronized(this@OrezSpeechModelControlsOwner) {
                    if (!proven && engine != null) retainedLease = Lease(requireNotNull(engine), scope)
                    if (current === session) current = null
                    if (mutable.value.token == session.owner.token) mutable.value = State(session.owner.token,
                        message = if (proven) "Speech model controls closed" else "Model cleanup did not prove release; further controls are unavailable in this process.")
                    capacity.releaseAfterCleanup(session.owner, proven)
                }
            }
        }
    }
}

@Composable
internal fun OrezSpeechModelsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var token by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    val state by OrezSpeechModelControlsOwner.state.collectAsState()
    LaunchedEffect(Unit) {
        token = OrezSpeechModelControlsOwner.start(context)
        busy = token == null
    }
    DisposableEffect(token) {
        val owned = token
        onDispose { owned?.let(OrezSpeechModelControlsOwner::retire) }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Offline speech models", style = MaterialTheme.typography.headlineSmall)
        Text("Orez microphone input uses the verified multilingual Tiny model. A microphone tap requests Android permission; after approval, tap again to record. Installing here is explicit; opening this page does not download or capture audio.",
            style = MaterialTheme.typography.bodyMedium)
        val owned = state.lease.takeIf { state.token == token }
        if (busy) {
            Text("Previous model controls are still releasing their resources. Try again after they return.")
            TextButton(onClick = { token = OrezSpeechModelControlsOwner.start(context); busy = token == null }) { Text("Retry model controls") }
        } else if (owned == null) {
            Text(state.message.takeIf { state.token == token } ?: "Preparing speech model controls…")
        } else {
            val modelState by owned.engine.state.collectAsState()
            Text(modelState.status, style = MaterialTheme.typography.bodySmall)
            SpeechModelInstallControls(owned.engine, owned.scope)
            if (modelState.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Tiny: 74 MiB • free multilingual Whisper model. Imported larger/custom models remain usable by Video, but microphone input accepts only the exact verified Tiny weights.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
