package com.mangalens.ui.video

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun LiveAudioSubtitleOverlay(engine: VideoSpeechEngine, modifier: Modifier = Modifier) {
    val state by engine.state.collectAsState()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("live_audio_subtitles", Context.MODE_PRIVATE) }
    var displayed by remember { mutableStateOf("") }
    var recentRevision by remember { mutableLongStateOf(-1L) }
    var arrival by remember { mutableLongStateOf(0L) }
    LaunchedEffect(engine) {
        while (true) {
            val current = engine.state.value
            if (current.revision != recentRevision) { recentRevision = current.revision; arrival = System.currentTimeMillis() }
            val offset = prefs.getInt("offset_ms", 0)
            val timing = engine.positionMs - offset
            val synced = current.cues.lastOrNull { timing in it.startMs..it.endMs }?.text
            displayed = when {
                !current.enabled -> ""
                synced != null -> synced
                prefs.getBoolean("show_latest", true) && System.currentTimeMillis() - arrival < prefs.getInt("hold_ms", 6000) -> current.latestText
                else -> ""
            }
            delay(100)
        }
    }
    if (displayed.isNotBlank()) {
        val size = prefs.getInt("font_size", 22)
        Text(
            displayed,
            modifier = modifier
                .widthIn(max = 920.dp)
                .background(Color.Black.copy(alpha = prefs.getFloat("opacity", .75f)), RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            color = Color.White,
            fontSize = size.sp,
            lineHeight = (size * 1.18f).sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
    }
}

@Composable
fun LiveAudioSubtitleSettings(engine: VideoSpeechEngine) {
    val context = LocalContext.current
    val state by engine.state.collectAsState()
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("live_audio_subtitles", Context.MODE_PRIVATE) }
    var fontSize by remember { mutableFloatStateOf(prefs.getInt("font_size", 22).toFloat()) }
    var opacity by remember { mutableFloatStateOf(prefs.getFloat("opacity", .75f)) }
    var hold by remember { mutableFloatStateOf(prefs.getInt("hold_ms", 6000).toFloat()) }
    var language by remember { mutableStateOf(engine.language) }
    var chunk by remember { mutableFloatStateOf(engine.chunkSeconds.toFloat()) }
    var showLatest by remember { mutableStateOf(prefs.getBoolean("show_latest", true)) }
    var offset by remember { mutableFloatStateOf(prefs.getInt("offset_ms", 0).toFloat()) }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { engine.importModel(uri) }
    }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        if (uri != null) scope.launch {
            exportStatus = runCatching { withContext(Dispatchers.IO) {
                context.contentResolver.openOutputStream(uri)?.use { it.write(engine.srt().toByteArray()) } ?: error("Cannot write subtitle file")
            }; "English subtitles exported" }.getOrElse { it.message }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Live English audio subtitles", style = MaterialTheme.typography.titleMedium)
        Text("Translates the video's decoded speech locally. Supports Whisper languages, with a short processing delay. Accuracy and speed depend on language, audio and your device.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Switch(checked = state.enabled, enabled = state.ready && !state.busy, onCheckedChange = engine::setEnabled)
            Text(if (state.enabled) "Listening to video" else "Off")
        }
        Text(state.status, style = MaterialTheme.typography.bodySmall)
        if (state.inferenceMs > 0) Text("Last audio processing: ${state.inferenceMs} ms", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = !state.busy, onClick = { scope.launch { engine.installTiny() } }) { Text("Download model (74 MiB)") }
            TextButton(enabled = !state.busy, onClick = { import.launch(arrayOf("application/octet-stream", "*/*")) }) { Text("Import model") }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("Import multilingual GGML tiny/base/small models for offline use. Larger models improve quality but take longer.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Source: $language")
            TextButton(onClick = {
                val languages = listOf("auto", "en", "hi", "ja", "ko", "zh", "es", "fr", "de", "ar", "ru", "ta", "te")
                language = languages[(languages.indexOf(language) + 1) % languages.size]; engine.language = language; prefs.edit().putString("source", language).apply(); engine.invalidate()
            }) { Text("Change") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Low latency" to 3, "Balanced" to 6, "Quality" to 12).forEach { (label, seconds) ->
                FilterChip(selected = chunk.toInt() == seconds, onClick = {
                    chunk = seconds.toFloat(); engine.chunkSeconds = seconds; engine.invalidate()
                    prefs.edit().putInt("chunk_seconds", seconds).apply()
                }, label = { Text(label) })
            }
        }
        Text("Chunk: ${chunk.toInt()} seconds (shorter = quicker; longer = more context)", style = MaterialTheme.typography.bodySmall)
        Slider(chunk, onValueChange = { chunk = it; engine.chunkSeconds = it.toInt(); prefs.edit().putInt("chunk_seconds", it.toInt()).apply() }, valueRange = 3f..12f, steps = 8)
        Text("Subtitle size: ${fontSize.toInt()} sp")
        Slider(fontSize, onValueChange = { fontSize = it; prefs.edit().putInt("font_size", it.toInt()).apply() }, valueRange = 14f..36f)
        Text("Background opacity")
        Slider(opacity, onValueChange = { opacity = it; prefs.edit().putFloat("opacity", it).apply() }, valueRange = 0f..1f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(checked = showLatest, onCheckedChange = { showLatest = it; prefs.edit().putBoolean("show_latest", it).apply() })
            Text("Show newly recognised speech after processing")
        }
        Text("Sync offset: ${(offset / 1000).toInt()} seconds")
        Slider(offset, onValueChange = { offset = it; prefs.edit().putInt("offset_ms", it.toInt()).apply() }, valueRange = -10000f..10000f)
        Text("Display time: ${(hold / 1000).toInt()} seconds")
        Slider(hold, onValueChange = { hold = it; prefs.edit().putInt("hold_ms", it.toInt()).apply() }, valueRange = 3000f..12000f)
        TextButton(enabled = state.cues.isNotEmpty(), onClick = { export.launch("MangaLens-English.srt") }) { Text("Export generated English SRT") }
        exportStatus?.let { Text(it) }
    }
}
