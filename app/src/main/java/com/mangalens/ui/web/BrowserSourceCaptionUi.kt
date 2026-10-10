package com.mangalens.ui.web

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mangalens.ui.video.SubtitleGenerationStatus
import com.mangalens.ui.video.SubtitleOutputMode
import com.mangalens.ui.video.SubtitleTargetOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Source text controls never call audio/model/capture APIs. Options are captured at explicit start. */
@Composable
internal fun BrowserSourceCaptionControls(
    controller: BrowserSourceCaptionController,
    state: BrowserSourceCaptionState,
    enabled: Boolean,
    initialTargetLanguage: String,
    onStart: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var preferenceLoad by remember(context) {
        mutableStateOf<BrowserCaptionPreferenceLoad>(BrowserCaptionPreferenceLoad.Loading)
    }
    LaunchedEffect(context) {
        val acceptedWrite = synchronized(browserCaptionPreferenceWriteLock) { browserCaptionPrecedingWrite }
        val loaded: BrowserCaptionPreferenceLoad = withContext(Dispatchers.IO) {
            // Reopening observes every already accepted choice, even if its old sheet closed.
            acceptedWrite?.join()
            try {
                val preferences = context.getSharedPreferences("full_video_subtitles", Context.MODE_PRIVATE)
                BrowserCaptionPreferenceLoad.Ready(preferences,
                    preferences.getString("browser_target", null)?.takeIf { it in setOf("en", "hi", "hi-latn") },
                    preferences.getBoolean("dual", false),
                    preferences.getString("style", "natural")?.takeIf { it in setOf("natural", "faithful") } ?: "natural")
            } catch (_: Exception) {
                BrowserCaptionPreferenceLoad.Failed
            }
        }
        preferenceLoad = loaded
    }
    val saved = preferenceLoad as? BrowserCaptionPreferenceLoad.Ready
    val preferenceWrites = rememberCoroutineScope()
    var preferenceWriteFailed by remember { mutableStateOf(false) }
    fun saveOptions(edit: SharedPreferences.Editor.() -> Unit) {
        val preferences = saved?.preferences ?: return
        // A user-accepted small preference write survives closing this sheet, entirely on IO.
        synchronized(browserCaptionPreferenceWriteLock) {
            val previous = browserCaptionPrecedingWrite
            browserCaptionPrecedingWrite = preferenceWrites.launch(Dispatchers.IO + NonCancellable) {
                // Main accepts these clicks in order; independent IO scheduling cannot reverse them.
                previous?.join()
                val failed = try { preferences.edit().apply(edit).commit().let { !it } }
                    catch (_: Exception) { true }
                withContext(Dispatchers.Main.immediate) { preferenceWriteFailed = failed }
            }
        }
    }
    var target by remember(saved, initialTargetLanguage) {
        mutableStateOf(saved?.target ?: initialTargetLanguage.takeIf { it in setOf("en", "hi", "hi-latn") } ?: "en")
    }
    var dual by remember(saved) { mutableStateOf(saved?.dual ?: false) }
    var style by remember(saved) { mutableStateOf(saved?.style ?: "natural") }
    var optionsOpen by remember { mutableStateOf(false) }
    val options = remember(target, dual, style) { SubtitleTargetOptions(targetLanguage = target,
        outputMode = if (dual) SubtitleOutputMode.DUAL else SubtitleOutputMode.TRANSLATED, style = style).capture() }
    Button(onClick = { controller.start(options); onStart() }, enabled = enabled && saved != null && !state.running) {
        Text("Use source captions here")
    }
    Text("Uses original captions on the current video. Verified translations follow its playback; audio capture and speech models are separate.",
        style = MaterialTheme.typography.bodySmall)
    when (preferenceLoad) {
        BrowserCaptionPreferenceLoad.Loading -> Text("Loading caption options…", style = MaterialTheme.typography.bodySmall)
        BrowserCaptionPreferenceLoad.Failed -> Text("Caption options could not be loaded. Reopen English CC to retry.",
            style = MaterialTheme.typography.bodySmall)
        is BrowserCaptionPreferenceLoad.Ready -> Text("Next start: ${captionLanguageLabel(target)} • ${if (style == "faithful") "Faithful" else "Natural"} • ${if (dual) "Original + translation" else "Translated"}",
        style = MaterialTheme.typography.labelMedium)
    }
    if (preferenceWriteFailed) Text("Caption options could not be saved. This request still uses your selected options.",
        style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { optionsOpen = !optionsOpen }) {
        Text(if (optionsOpen) "Hide caption options" else "Caption language and style")
    }
    if (optionsOpen) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("en", "hi", "hi-latn").forEach { code ->
                FilterChip(selected = target == code, enabled = saved != null && !state.running, onClick = {
                    target = code; saveOptions { putString("browser_target", code) }
                },
                    label = { Text(captionLanguageLabel(code)) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("natural" to "Natural", "faithful" to "Faithful").forEach { (id, label) ->
                FilterChip(selected = style == id, enabled = saved != null && !state.running, onClick = {
                    style = id; saveOptions { putString("style", id) }
                }, label = { Text(label) })
            }
        }
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Checkbox(checked = dual, enabled = saved != null && !state.running,
                modifier = Modifier.semantics { contentDescription = "Browser source caption dual output" }, onCheckedChange = {
                dual = it; saveOptions { putBoolean("dual", it) }
            })
            Text("Original + translation", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (state.requested) {
        Text(state.message, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (state.status in setOf(SubtitleGenerationStatus.QUEUED, SubtitleGenerationStatus.RUNNING)) {
                TextButton(onClick = controller::pause, enabled = !state.scheduling) { Text("Pause source captions") }
            }
            if (state.status in setOf(SubtitleGenerationStatus.PAUSED, SubtitleGenerationStatus.PARTIAL, SubtitleGenerationStatus.FAILED)) {
                TextButton(onClick = controller::resume, enabled = enabled && !state.scheduling) { Text("Resume source captions") }
            }
            TextButton(onClick = { controller.retire("Source captions stopped.") }) { Text("Stop source captions") }
        }
    }
}

/** Text comes only from the verified current generation and an actual observed media clock. */
@Composable
internal fun BrowserSourceCaptionOverlay(text: String?, modifier: Modifier = Modifier) {
    if (text.isNullOrBlank()) return
    Surface(modifier = modifier.widthIn(max = 920.dp).semantics(mergeDescendants = true) {
        contentDescription = "Source captions for current browser video"
    }, shape = RoundedCornerShape(10.dp), color = Color.Black.copy(alpha = .82f), shadowElevation = 6.dp) {
        Text(text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
            color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium,
            maxLines = 8, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

private fun captionLanguageLabel(code: String): String = when (code) {
    "hi" -> "Hindi"
    "hi-latn" -> "Hinglish"
    else -> "English"
}


private sealed interface BrowserCaptionPreferenceLoad {
    data object Loading : BrowserCaptionPreferenceLoad
    data object Failed : BrowserCaptionPreferenceLoad
    data class Ready(val preferences: SharedPreferences, val target: String?, val dual: Boolean, val style: String) : BrowserCaptionPreferenceLoad
}


// All sheet/controller instances share accepted write order; retiring a sheet cannot
// let its older IO commit overwrite a newer choice from a reopened sheet.
private val browserCaptionPreferenceWriteLock = Any()
private var browserCaptionPrecedingWrite: Job? = null
