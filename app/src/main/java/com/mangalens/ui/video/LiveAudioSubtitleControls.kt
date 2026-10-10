package com.mangalens.ui.video

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
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
                !current.enabled && !current.generated -> ""
                synced != null -> synced
                prefs.getBoolean("show_latest", true) && System.currentTimeMillis() - arrival < prefs.getInt("hold_ms", 6000) -> current.latestText
                else -> ""
            }
            delay(100)
        }
    }
    if (displayed.isNotBlank()) {
        val size = prefs.getInt("font_size", 22)
        Surface(
            modifier = modifier.widthIn(max = 920.dp),
            shape = RoundedCornerShape(10.dp),
            color = Color.Black.copy(alpha = prefs.getFloat("opacity", .75f)),
            border = BorderStroke(1.dp, Color.White.copy(alpha = .10f)),
            shadowElevation = 8.dp
        ) {
            Text(
                displayed,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                color = Color.White,
                fontSize = size.sp,
                lineHeight = (size * 1.18f).sp,
                fontWeight = FontWeight.Medium,
                style = MaterialTheme.typography.bodyLarge.copy(
                    shadow = Shadow(Color.Black, Offset(1f, 1f), 4f)
                ),
                textAlign = TextAlign.Center,
                maxLines = if (state.generated && state.generatedOutputMode == SubtitleOutputMode.DUAL) Int.MAX_VALUE else 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun LiveAudioSubtitleSettings(
    engine: VideoSpeechEngine,
    showEnableControl: Boolean = true
) {
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
        if (showEnableControl) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = state.enabled, enabled = state.ready && !state.busy, onCheckedChange = engine::setEnabled)
                Text(if (state.enabled) "Listening to video" else "Off")
            }
        } else {
            Text(
                "Web subtitles start only after Android confirms playback capture below. " +
                    "The speech engine no longer reports Active before audio capture is actually running.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(state.status, style = MaterialTheme.typography.bodySmall)
        if (state.enabled) {
            Text(
                "Decoded audio: ${"%.1f".format(state.capturedAudioMs / 1000f)} s • processed windows: ${state.processedWindows}",
                style = MaterialTheme.typography.bodySmall,
                color = if (state.capturedAudioMs > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            if (state.capturedAudioMs == 0L) {
                Text(
                    "If this stays at 0.0 s while the video is playing, use Generate Full Subtitles. That path reads the media audio track directly.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (state.inferenceMs > 0) Text("Last audio processing: ${state.inferenceMs} ms", style = MaterialTheme.typography.bodySmall)
        SpeechModelInstallControls(engine)
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
            listOf("Low latency" to 3, "Balanced" to 4, "Quality" to 8).forEach { (label, seconds) ->
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


@Composable
fun SubtitleToolsPanel(
    engine: VideoSpeechEngine,
    generator: FullVideoSubtitleGenerator,
    source: SubtitleMediaSource?,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Subtitle Tools", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Live speech uses the offline multilingual Whisper model. Complete-video subtitles first check genuine provider captions, then recognise audio when needed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        LiveAudioSubtitleSettings(engine)
        HorizontalDivider()
        FullSubtitleGeneratorCard(generator, source)
    }
}

@Composable
fun FullSubtitleGeneratorCard(
    generator: FullVideoSubtitleGenerator,
    source: SubtitleMediaSource?,
    modifier: Modifier = Modifier
) {
    val state by generator.state.collectAsState()
    val context = LocalContext.current
    val prefs = remember(context.applicationContext) { context.getSharedPreferences("full_video_subtitles", Context.MODE_PRIVATE) }
    var attachToPlayer by remember { mutableStateOf(true) }
    var target by rememberSaveable { mutableStateOf(prefs.getString("target", "en")?.takeIf { it in setOf("en", "hi", "hi-latn") } ?: "en") }
    var dual by rememberSaveable { mutableStateOf(prefs.getBoolean("dual", false)) }
    var style by rememberSaveable { mutableStateOf(prefs.getString("style", "natural")?.takeIf { it in setOf("natural", "faithful") } ?: "natural") }
    val options = remember(target, dual, style) { SubtitleTargetOptions(target,
        if (dual) SubtitleOutputMode.DUAL else SubtitleOutputMode.TRANSLATED, style).capture() }
    val exports = rememberSubtitleExportActions(state, source)
    LaunchedEffect(generator, source, options) { source?.let { generator.bind(it, options) } }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f),
        tonalElevation = 1.dp
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Generate Full Subtitles", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Use genuine original provider captions first, then recognise audio when captions are unavailable. Work continues in the background; saved dialogue and translations survive pause and restart. Translation models may need an initial download.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = attachToPlayer,
                    onCheckedChange = { attachToPlayer = it }
                )
            }
            Text(
                if (attachToPlayer) "Attach to player: Yes (recommended)" else "Attach to player: No",
                style = MaterialTheme.typography.labelMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("en" to "English", "hi" to "Hindi", "hi-latn" to "Hinglish").forEach { (code, label) ->
                    FilterChip(selected = target == code, enabled = !state.running, onClick = {
                        target = code; prefs.edit().putString("target", code).apply()
                    }, label = { Text(label) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(false to "Translated", true to "Original + translation").forEach { (paired, label) ->
                    FilterChip(selected = dual == paired, enabled = !state.running, onClick = {
                        dual = paired; prefs.edit().putBoolean("dual", paired).apply()
                    }, label = { Text(label) })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("natural" to "Natural", "faithful" to "Faithful").forEach { (id, label) ->
                    FilterChip(selected = style == id, enabled = !state.running, onClick = {
                        style = id; prefs.edit().putString("style", id).apply()
                    }, label = { Text(label) })
                }
            }

            if (state.running) {
                LinearProgressIndicator(progress = state.progress, modifier = Modifier.fillMaxWidth())
                Text(
                    "${(state.progress * 100).toInt()}% • ${state.stage}",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    state.stage + if (state.cached) " • cached for this video" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (state.cues.isNotEmpty()) {
                Surface(
                    Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Black.copy(alpha = .28f)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Subtitle Preview (${FullVideoSubtitleGenerator.targetName(state.targetLanguage)}${if (state.outputMode == SubtitleOutputMode.DUAL) " + original" else ""})", style = MaterialTheme.typography.labelLarge)
                        state.cues.takeLast(3).forEach { cue ->
                            Text(
                                "${previewTime(cue.startMs)}  ${cue.text}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = if (state.outputMode == SubtitleOutputMode.DUAL) Int.MAX_VALUE else 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.running) {
                    OutlinedButton(onClick = generator::pause) { Text("Pause") }
                    Button(onClick = generator::cancel, modifier = Modifier.weight(1f)) {
                        Text("Cancel")
                    }
                } else {
                    if (state.status in setOf(SubtitleGenerationStatus.PAUSED, SubtitleGenerationStatus.PARTIAL, SubtitleGenerationStatus.FAILED)) {
                        OutlinedButton(onClick = generator::resume) { Text("Resume") }
                    }
                    Button(
                        enabled = source != null,
                        onClick = { source?.let { generator.generate(it, attachToPlayer = attachToPlayer, force = state.cues.isNotEmpty(), targetOptions = options) } },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(if (state.cues.isEmpty()) "Generate now" else "Regenerate")
                    }
                }
                OutlinedButton(
                    enabled = state.cues.isNotEmpty(),
                    onClick = generator::applyToPlayer,
                    modifier = Modifier.weight(1f)
                ) { Text("Apply to player") }
                OutlinedButton(
                    enabled = exports.srtEnabled,
                    onClick = exports.srt,
                    modifier = Modifier.weight(1f)
                ) { Text(exports.srtLabel) }
            }
            TextButton(enabled = exports.vttEnabled, onClick = exports.vtt) { Text(exports.vttLabel) }
            if (state.sourceCues.isNotEmpty() && state.pendingTargetCues > 0) {
                Text("${state.pendingTargetCues} speech cues still need translation. Resume keeps completed cues.", style = MaterialTheme.typography.bodySmall)
            }
            if (source == null) {
                Text("Open a local or online video first.", style = MaterialTheme.typography.bodySmall)
            }
            exports.status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

internal data class SubtitleExportActions(val srtEnabled: Boolean, val vttEnabled: Boolean, val srtLabel: String,
    val vttLabel: String, val status: String?, val srt: () -> Unit, val vtt: () -> Unit)

/** Only opaque tokens enter saved instance state; accepted bytes remain in app-private AtomicFiles. */
@Composable
internal fun rememberSubtitleExportActions(state: FullSubtitleState, source: SubtitleMediaSource?): SubtitleExportActions {
    val context = LocalContext.current
    val spool = remember(context.applicationContext) { SubtitleExportSpool.shared(context) }
    val writing by spool.writing.collectAsState()
    val scope = rememberCoroutineScope()
    var pendingSrt by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingVtt by rememberSaveable { mutableStateOf<String?>(null) }
    var waitingSrt by rememberSaveable { mutableStateOf(false) }
    var waitingVtt by rememberSaveable { mutableStateOf(false) }
    var status by rememberSaveable { mutableStateOf<String?>(null) }
    var preparingSrt by remember { mutableStateOf(false) }
    var preparingVtt by remember { mutableStateOf(false) }
    fun receive(uri: android.net.Uri?, token: String?, format: String) {
        if (uri == null) {
            if (format == "srt") waitingSrt = false else waitingVtt = false
            status = "Export cancelled"
            if (format == "srt") pendingSrt = null else pendingVtt = null
            token?.let { scope.launch(Dispatchers.IO) { runCatching { spool.remove(it) } } }
            return
        }
        if (token == null) {
            if (format == "srt") waitingSrt = false else waitingVtt = false
            status = "Export request could not be restored. Export again."
            return
        }
        val lease = try { spool.claim(token) } catch (failure: Exception) {
            if (format == "srt") waitingSrt = false else waitingVtt = false
            status = failure.message?.take(300) ?: "This subtitle export is already being written."
            return
        }
        if (format == "srt") waitingSrt = false else waitingVtt = false
        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            var available = false
            val result = runCatching {
                // A selected destination is an accepted write. Finish its bounded
                // immutable bytes even if the Activity rotates during provider IO.
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    try {
                        spool.read(token)
                        available = true
                        spool.deliver(lease) { context.contentResolver.openOutputStream(uri) }
                    } finally { spool.release(lease) }
                }
                "Requested ${format.uppercase(java.util.Locale.ROOT)} subtitles exported"
            }
            status = result.getOrElse { it.message?.take(300) ?: "Export failed. Retry the export." }
            if (result.isSuccess || !available) {
                if (format == "srt" && pendingSrt == token) pendingSrt = null
                if (format == "vtt" && pendingVtt == token) pendingVtt = null
                if (!available) withContext(Dispatchers.IO) { runCatching { spool.remove(token) } }
            }
        }
    }
    val srtLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { receive(it, pendingSrt, "srt") }
    val vttLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/vtt")) { receive(it, pendingVtt, "vtt") }
    fun request(format: String) {
        val token = if (format == "srt") pendingSrt else pendingVtt
        if (format == "srt" && (preparingSrt || waitingSrt) || format == "vtt" && (preparingVtt || waitingVtt) || token in writing) return
        val receipt = if (token == null) source?.let { SubtitleExportReceipt.capture(state, it, format) } else null
        if (token == null && receipt == null) { status = "Subtitles are not ready for export."; return }
        if (format == "srt") preparingSrt = true else preparingVtt = true
        scope.launch {
            var prepared = false
            try {
                val durable = withContext(Dispatchers.IO) {
                    if (token == null) spool.stage(receipt!!)
                    else {
                        check(token !in spool.writing.value) { "This subtitle export is already being written." }
                        spool.read(token)
                        token
                    }
                }
                prepared = true
                if (format == "srt") { pendingSrt = durable; waitingSrt = true } else { pendingVtt = durable; waitingVtt = true }
                status = "Choose a destination for the requested ${format.uppercase(java.util.Locale.ROOT)} export."
                if (format == "srt") srtLauncher.launch("MangaLens-subtitles.srt") else vttLauncher.launch("MangaLens-subtitles.vtt")
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (format == "srt") waitingSrt = false else waitingVtt = false
                if (!prepared && token != null && token !in spool.writing.value) {
                    if (format == "srt" && pendingSrt == token) pendingSrt = null
                    if (format == "vtt" && pendingVtt == token) pendingVtt = null
                }
                status = failure.message?.take(300) ?: "Cannot prepare export. Try again."
            }
            finally { if (format == "srt") preparingSrt = false else preparingVtt = false }
        }
    }
    val ready = state.cues.isNotEmpty() && state.sourceCacheKey == source?.cacheKey &&
        (state.pipeline != SubtitlePipeline.SOURCE_TRANSLATION || state.status == SubtitleGenerationStatus.COMPLETED && state.pendingTargetCues == 0)
    return SubtitleExportActions(srtEnabled = !preparingSrt && !waitingSrt && pendingSrt?.let(writing::contains) != true && (pendingSrt != null || ready && state.srt.isNotBlank()),
        vttEnabled = !preparingVtt && !waitingVtt && pendingVtt?.let(writing::contains) != true && (pendingVtt != null || ready && state.vtt.isNotBlank()),
        srtLabel = if (pendingSrt == null) "Export SRT" else "Retry SRT",
        vttLabel = if (pendingVtt == null) "Export VTT" else "Retry VTT", status = status,
        srt = { request("srt") }, vtt = { request("vtt") })
}

private fun previewTime(ms: Long): String {
    val t = ms.coerceAtLeast(0L)
    return "%02d:%02d:%02d".format(t / 3_600_000, t / 60_000 % 60, t / 1_000 % 60)
}

/** Shared explicit model actions. Opening controls never installs, imports or records audio. */
@Composable
internal fun SpeechModelInstallControls(engine: VideoSpeechEngine,
    ownedScope: kotlinx.coroutines.CoroutineScope? = null) {
    val uiScope = rememberCoroutineScope()
    val scope = ownedScope ?: uiScope
    val state by engine.state.collectAsState()
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { engine.importModel(uri) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(enabled = !state.busy, onClick = { scope.launch { engine.installTiny() } }) {
            Text(if (state.ready) "Replace with tiny (74 MiB)" else "Download model (74 MiB)")
        }
        TextButton(enabled = !state.busy, onClick = { import.launch(arrayOf("application/octet-stream", "*/*")) }) { Text("Import model") }
    }
}
