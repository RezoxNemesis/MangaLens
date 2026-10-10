package com.mangalens.ui.ai.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

internal data class OrezVoiceControls(val inputState: OrezVoiceState?, val inputNotice: String?,
    val inputOwned: Boolean, val record: () -> Unit, val stopInput: () -> Unit, val cancelInput: () -> Unit,
    val outputEnabled: Boolean, val outputState: OrezSpeechOutputState?, val setOutput: (Boolean) -> Unit,
    val selectOutput: (String) -> Unit, val speak: (String) -> Unit, val stopOutput: () -> Unit)

/** UI owns its callback and draft snapshot; process producers receive only tokens and application Context. */
@Composable
internal fun rememberOrezVoiceControls(draft: String, draftRevision: Long,
    onTranscript: (String) -> Unit): OrezVoiceControls {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val routeScope = remember { UUID.randomUUID().toString() }
    val alive = remember { AtomicBoolean(true) }
    val mic = remember(context.applicationContext) { OrezOfflineVoiceRuntime.get(context) }
    val output = remember(context.applicationContext) { OrezOfflineSpeechOutput.get(context) }
    val micState by mic.state.collectAsState()
    val speechState by output.state.collectAsState()
    val currentDraft by rememberUpdatedState(draft)
    val currentRevision by rememberUpdatedState(draftRevision)
    val applyTranscript by rememberUpdatedState(onTranscript)
    var inputToken by remember { mutableStateOf<Long?>(null) }
    var capture by remember { mutableStateOf<VoiceDraftCapture?>(null) }
    var pendingPermission by remember { mutableStateOf<VoiceDraftCapture?>(null) }
    var permissionSerial by remember { mutableLongStateOf(0L) }
    var inputNotice by remember { mutableStateOf<String?>(null) }
    var outputToken by remember { mutableStateOf<Long?>(null) }
    val resumed = { alive.get() && lifecycle.currentState == Lifecycle.State.RESUMED }
    fun retireInput(clearPermission: Boolean) {
        val token = inputToken
        // Clear UI delivery eligibility before retiring the independently owned producer.
        capture = null; inputToken = null
        if (clearPermission) pendingPermission = null
        token?.let(mic::cancel)
    }
    fun cancelInput() { retireInput(clearPermission = true) }
    fun begin(expected: VoiceDraftCapture) {
        if (!expected.matches(routeScope, currentRevision, currentDraft, expected.token, resumed())) return
        val token = mic.start()
        if (token == null) {
            inputNotice = "A previous microphone/native operation is still releasing resources. Try later."
        } else {
            inputToken = token
            capture = expected.copy(token = token)
            inputNotice = null
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val expected = pendingPermission
        pendingPermission = null
        // Consent never starts recording. A fresh explicit foreground tap is required after approval.
        if (expected != null && alive.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
            expected.matches(routeScope, currentRevision, currentDraft, permissionSerial, resumed = true)) {
            inputNotice = if (granted) "Microphone permission granted. Tap Voice input again to record offline."
                else "Microphone permission was declined. No audio was captured."
        }
    }
    val requestInput: () -> Unit = {
        if (resumed() && inputToken == null && pendingPermission == null) {
            val expected = VoiceDraftCapture(routeScope, currentRevision, currentDraft, ++permissionSerial)
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                begin(expected)
            else { pendingPermission = expected; permission.launch(Manifest.permission.RECORD_AUDIO) }
        }
    }
    LaunchedEffect(draftRevision) {
        val expected = capture ?: pendingPermission
        if (expected != null && (expected.revision != draftRevision || expected.draft != draft)) cancelInput()
    }
    LaunchedEffect(micState, draftRevision) {
        val expected = capture
        if (expected != null && micState.token == expected.token) {
            if (micState.phase == OrezVoicePhase.READY && micState.transcript != null) {
                val accepted = expected.matches(routeScope, currentRevision, currentDraft, inputToken, resumed())
                val text = micState.transcript ?: return@LaunchedEffect
                cancelInput()
                if (accepted) {
                    val combined = OfflineOrezVoicePolicy.mergeDraft(expected.draft, text)
                    if (combined == null) inputNotice = "Your draft is too long to add speech. Shorten it and try again."
                    else { applyTranscript(combined); inputNotice = "Speech added to your draft. Review it and tap Send." }
                }
            } else if (micState.phase in setOf(OrezVoicePhase.MODEL_REQUIRED, OrezVoicePhase.ERROR, OrezVoicePhase.RETAINED)) {
                inputNotice = micState.message
                cancelInput()
            }
        }
    }
    DisposableEffect(lifecycle, mic, output) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                // Retain only a UI consent ticket through the permission dialog's transient pause;
                // it can report permission status, never start capture. Stop/dispose retires it too.
                retireInput(clearPermission = event != Lifecycle.Event.ON_PAUSE)
                val token = outputToken; outputToken = null
                token?.let(output::disable)
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            alive.set(false)
            lifecycle.removeObserver(observer)
            cancelInput()
            val token = outputToken; outputToken = null
            token?.let(output::disable)
        }
    }
    val enableOutput: (Boolean) -> Unit = { enabled ->
        if (!enabled) {
            val token = outputToken; outputToken = null; token?.let(output::disable)
        } else if (resumed() && outputToken == null) {
            val token = output.enable()
            if (token == null) inputNotice = "The earlier Android speech provider is still releasing resources. Try later."
            else outputToken = token
        }
    }
    return OrezVoiceControls(micState.takeIf { it.token == inputToken }, inputNotice, inputToken != null,
        requestInput, { inputToken?.let(mic::stop) }, ::cancelInput,
        outputToken != null, speechState.takeIf { it.token == outputToken }, enableOutput,
        { name -> if (resumed()) outputToken?.let { output.select(it, name) } },
        { text -> if (resumed()) outputToken?.let { output.speak(it, text) } },
        { outputToken?.let(output::stop) })
}

@Composable
internal fun OrezOfflineVoicePanel(controls: OrezVoiceControls, onOpenSpeechModels: () -> Unit) {
    var voiceMenu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!controls.inputOwned) {
                TextButton(onClick = controls.record, modifier = Modifier.semantics {
                    contentDescription = "Record offline voice input"
                }) { Text("Voice input") }
            } else {
                TextButton(onClick = controls.stopInput, enabled = controls.inputState?.phase == OrezVoicePhase.RECORDING) { Text("Stop recording") }
                TextButton(onClick = controls.cancelInput) { Text("Cancel voice input") }
            }
            TextButton(onClick = onOpenSpeechModels) { Text("Speech models") }
        }
        (controls.inputState?.message ?: controls.inputNotice)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = controls.outputEnabled, onCheckedChange = controls.setOutput)
            Text("Android offline speech output", style = MaterialTheme.typography.bodySmall)
            if (controls.outputState?.speaking == true) TextButton(onClick = controls.stopOutput) { Text("Stop speech") }
        }
        controls.outputState?.let { state ->
            Text(state.message, style = MaterialTheme.typography.bodySmall)
            if (state.ready) {
                Box {
                    TextButton(onClick = { voiceMenu = true }) {
                        val voice = state.voices.firstOrNull { it.name == state.selected }
                        Text("${voice?.localeTag.orEmpty()} • ${voice?.name.orEmpty()}", maxLines = 1)
                    }
                    DropdownMenu(expanded = voiceMenu, onDismissRequest = { voiceMenu = false }) {
                        state.voices.forEach { voice -> DropdownMenuItem(text = { Text("${voice.localeTag} • ${voice.name}") },
                            onClick = { voiceMenu = false; controls.selectOutput(voice.name) }) }
                    }
                }
                Text("Default Android provider: ${state.provider.ifBlank { "unreported" }}. The provider implements synthesis; only voices marked offline are offered.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
