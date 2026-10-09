package com.mangalens.ui.video

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ImportedCaptionControls(vm: LocalVideoPlayerViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) { ImportedCaptionStore(context) }
    val translator = remember(context) { VideoSubtitleTranslator(context) }
    val scope = rememberCoroutineScope()
    var target by rememberSaveable {
        mutableStateOf(context.getSharedPreferences("mangalens_preferences", Context.MODE_PRIVATE)
            .getString("translation_target", "hi") ?: "hi")
    }
    var source by rememberSaveable { mutableStateOf("auto") }
    var dual by rememberSaveable { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    var pendingPicker by rememberSaveable(stateSaver = CaptionPickerSaver) { mutableStateOf<CaptionSourceTicket?>(null) }
    var pendingExport by rememberSaveable(stateSaver = CaptionExportSaver) {
        mutableStateOf<Pair<CaptionSourceTicket, PlayerCaptionTrack>?>(null)
    }
    val languages = listOf("hi" to "Hindi", "hi-latn" to "Hinglish", "en" to "English", "ja" to "Japanese", "ko" to "Korean", "zh" to "Chinese")

    fun fail(failure: Throwable) {
        if (failure !is CancellationException) status = failure.message ?: "Subtitle operation failed."
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val ticket = pendingPicker
        pendingPicker = null
        if (uri == null || ticket == null || !vm.captionSourceMatches(ticket)) return@rememberLauncherForActivityResult
        busy = true
        status = "Checking subtitle file…"
        job = scope.launch {
            try {
                val captions = store.read(uri)
                ensureActive()
                if (!vm.captionSourceMatches(ticket)) return@launch
                val track = store.publish(captions, "und", "Imported subtitles")
                ensureActive()
                if (store.verify(track) && vm.applyImportedCaption(ticket, track)) {
                    status = "${captions.cues.size} subtitle cues applied."
                }
            } catch (failure: Throwable) {
                if (vm.captionSourceMatches(ticket)) fail(failure)
                if (failure is CancellationException) throw failure
            } finally {
                if (vm.captionSourceMatches(ticket)) busy = false
            }
        }
    }

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) { uri ->
        val request = pendingExport
        pendingExport = null
        if (uri == null || request == null || !vm.captionSourceMatches(request.first)) return@rememberLauncherForActivityResult
        busy = true
        job = scope.launch {
            try {
                val bytes = store.readExport(request.second)
                ensureActive()
                require(vm.captionSourceMatches(request.first)) { "The selected video changed." }
                withContext(Dispatchers.IO) {
                    requireNotNull(context.contentResolver.openOutputStream(uri, "w")) { "Unable to write subtitle destination." }
                        .use { it.write(bytes); it.flush() }
                }
                if (vm.captionSourceMatches(request.first)) status = "Subtitle file exported."
            } catch (failure: Throwable) {
                if (vm.captionSourceMatches(request.first)) fail(failure)
                if (failure is CancellationException) throw failure
            } finally {
                if (vm.captionSourceMatches(request.first)) busy = false
            }
        }
    }

    LaunchedEffect(vm.sourceRevision) {
        job?.cancel()
        busy = false
        if (pendingPicker?.let(vm::captionSourceMatches) == false) pendingPicker = null
        if (pendingExport?.first?.let(vm::captionSourceMatches) == false) pendingExport = null
        status = null
    }
    DisposableEffect(vm) { onDispose { job?.cancel() } }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Subtitle files", style = MaterialTheme.typography.titleMedium)
        Button(enabled = !busy, onClick = {
            val ticket = vm.captureCaptionSource() ?: return@Button
            job?.cancel()
            pendingPicker = ticket
            picker.launch(arrayOf("application/x-subrip", "text/vtt", "text/*", "application/octet-stream"))
        }, modifier = Modifier.fillMaxWidth()) { Text("Choose SRT / VTT") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Translate to ${languages.firstOrNull { it.first == target }?.second ?: target.uppercase(Locale.ROOT)}")
            TextButton(enabled = !busy, onClick = { target = languages[(languages.indexOfFirst { it.first == target } + 1) % languages.size].first }) { Text("Change target") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Source: ${languages.firstOrNull { it.first == source }?.second ?: "Automatic"}")
            TextButton(enabled = !busy, onClick = {
                val choices = listOf("auto") + languages.filter { it.first != "hi-latn" }.map { it.first }
                source = choices[(choices.indexOf(source) + 1) % choices.size]
            }) { Text("Change source") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Original + translated cues")
            Switch(checked = dual, enabled = !busy, onCheckedChange = { dual = it })
        }
        Button(enabled = vm.currentCaptionTrack != null && !busy, onClick = {
            val track = vm.currentCaptionTrack ?: return@Button
            val ticket = vm.captureCaptionSource() ?: return@Button
            val capturedTarget = target
            val capturedSource = source.takeUnless { it == "auto" }
            val capturedDual = dual
            busy = true
            status = "Translating subtitle cues…"
            job = scope.launch {
                try {
                    require(store.verify(track)) { "Subtitle file has changed; import it again." }
                    val original = store.readSource(track)
                    val result = translator.translateFile(original, capturedTarget, capturedSource, capturedDual) { done, total ->
                        scope.launch { if (vm.captionSourceMatches(ticket)) status = "Translated $done / $total cues…" }
                    }
                    ensureActive()
                    if (!vm.captionSourceMatches(ticket)) return@launch
                    val translated = store.publish(result, capturedTarget, if (capturedDual) "Original + translated subtitles" else "Translated subtitles", original = track)
                    ensureActive()
                    if (store.verify(translated) && vm.applyImportedCaption(ticket, translated)) status = "Translated subtitles applied."
                } catch (failure: Throwable) {
                    if (vm.captionSourceMatches(ticket)) fail(failure)
                    if (failure is CancellationException) throw failure
                } finally {
                    if (vm.captionSourceMatches(ticket)) busy = false
                }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Working…" else "Translate and apply subtitles") }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(enabled = vm.currentCaptionTrack != null && !busy, onClick = {
                val track = vm.currentCaptionTrack ?: return@TextButton
                val ticket = vm.captureCaptionSource() ?: return@TextButton
                pendingExport = ticket to track
                exporter.launch("MangaLens-subtitles-${track.language}.srt")
            }) { Text("Export SRT") }
            if (busy) TextButton(onClick = {
                job?.cancel()
                vm.captureCaptionSource()
                busy = false
                status = "Subtitle operation cancelled."
            }) { Text("Cancel") }
        }
        status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

private val CaptionPickerSaver = listSaver<CaptionSourceTicket?, String>(
    save = { it?.savedFields() ?: emptyList() }, restore = { restoreCaptionTicket(it) }
)

private val CaptionExportSaver = listSaver<Pair<CaptionSourceTicket, PlayerCaptionTrack>?, String>(
    save = { request ->
        request?.let { (ticket, track) -> ticket.savedFields() + listOf(track.uri.toString(), track.mimeType,
            track.language, track.label, track.sha256, track.bytes.toString(), track.sourceUri.toString(),
            track.sourceSha256, track.sourceBytes.toString()) } ?: emptyList()
    },
    restore = { fields ->
        if (fields.size != 12 || fields.any { it.length > 8192 }) null else {
            val ticket = restoreCaptionTicket(fields.take(3))
            val bytes = fields[8].toLongOrNull()
            val sourceBytes = fields[11].toLongOrNull()
            if (ticket == null || bytes == null || sourceBytes == null ||
                bytes !in 1..ImportedCaptionFile.MAX_BYTES.toLong() || sourceBytes !in 1..ImportedCaptionFile.MAX_BYTES.toLong() ||
                fields[4] != "application/x-subrip" || fields[6].length > 120 ||
                !fields[7].matches(Regex("[a-f0-9]{64}")) || !fields[10].matches(Regex("[a-f0-9]{64}"))) null
            else ticket to PlayerCaptionTrack(Uri.parse(fields[3]), fields[4], fields[5], fields[6], fields[7], bytes,
                Uri.parse(fields[9]), fields[10], sourceBytes)
        }
    }
)
