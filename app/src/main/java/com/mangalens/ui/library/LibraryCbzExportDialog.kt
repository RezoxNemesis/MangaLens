package com.mangalens.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mangalens.core.reader.ChapterCbzExportFiles
import com.mangalens.core.reader.ChapterCbzOperation
import com.mangalens.core.reader.ChapterCbzPolicy
import com.mangalens.core.reader.ChapterCbzScope
import com.mangalens.core.reader.ChapterCbzStage
import com.mangalens.core.reader.SavedChapter
import com.mangalens.ui.downloads.SavedVideoProbeBusyException
import com.mangalens.ui.downloads.SavedVideoProbeCleanupException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

@Composable
internal fun LibraryCbzExportDialog(chapter: SavedChapter, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val operation = remember(chapter.id) { ChapterCbzOperation() }
    val progress by operation.progress.collectAsState()
    var alive by remember { mutableStateOf(true) }
    var pickerPending by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var captured by remember { mutableStateOf<ChapterCbzScope?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var completed by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { alive = false; job?.cancel() } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { destination ->
        pickerPending = false
        val accepted = captured
        captured = null
        if (alive && destination != null && accepted != null) {
            job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                busy = true; completed = false; notice = null
                try {
                    val receipt = ChapterCbzExportFiles.export(app, destination, accepted, operation)
                    if (alive) { completed = true; notice = "Exported ${receipt.pages} original pages (${receipt.archiveBytes / 1024} KiB). Archive SHA-256: ${receipt.archiveSha256}." }
                } catch (_: TimeoutCancellationException) {
                    if (alive) notice = "Export timed out. The selected destination may be incomplete; the provider may still be finishing."
                } catch (cancelled: CancellationException) {
                    if (alive) notice = "Export cancelled. The selected destination may be incomplete; cleanup may still be finishing."
                    throw cancelled
                } catch (_: SavedVideoProbeBusyException) {
                    if (alive) notice = "A previous export is still finishing. Retry after it closes, or restart if it could not close safely."
                } catch (_: SavedVideoProbeCleanupException) {
                    if (alive) notice = "The document provider could not close safely. The destination may be incomplete. Restart the app before exporting again."
                } catch (_: Exception) {
                    if (alive) notice = "Export could not finish. The destination may be incomplete. Check document access, reopen the Library and retry missing or changed original pages."
                } finally { if (alive) busy = false }
            }
        }
    }
    AlertDialog(
        onDismissRequest = { job?.cancel(); onDismiss() },
        title = { Text("Export original pages (CBZ)") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(chapter.title, style = MaterialTheme.typography.titleSmall)
            Text("Save the existing offline original images in page order. Translations, corrections, notes, browser data and app task journals are excluded.")
            Text("Up to 1000 pages, 40 MiB per page and 512 MiB total. Missing pages must be downloaded before exporting. No network or model runs are started.", style = MaterialTheme.typography.bodySmall)
            if (pickerPending) Text("Choose a destination in the document picker.")
            if (busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                val stage = when (progress.stage) {
                    ChapterCbzStage.READY -> "Starting"
                    ChapterCbzStage.VERIFYING -> "Verifying offline originals"
                    ChapterCbzStage.PREPARING -> "Preparing archive"
                    ChapterCbzStage.RECHECKING -> "Rechecking current originals"
                    ChapterCbzStage.WRITING -> "Writing selected destination"
                    ChapterCbzStage.FINISHING -> "Closing destination and rechecking sources"
                    ChapterCbzStage.COMPLETE -> "Finishing"
                }
                Text("$stage · ${progress.pages} of ${progress.totalPages} pages", Modifier.semantics { contentDescription = "CBZ export progress: $stage" })
            }
            notice?.let { Text(it, color = if (completed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        } },
        confirmButton = {
            if (!completed) TextButton(enabled = !busy && !pickerPending, onClick = {
                try {
                    captured = ChapterCbzScope.capture(chapter); notice = null; pickerPending = true
                    launcher.launch(ChapterCbzPolicy.suggestedName(chapter.title))
                } catch (_: Exception) { captured = null; pickerPending = false; notice = "This chapter needs 1–1000 available offline originals before it can be exported." }
            }, modifier = Modifier.semantics { contentDescription = "Choose CBZ export destination" }) { Text("Choose destination") }
        },
        dismissButton = { TextButton({ job?.cancel(); onDismiss() }) { Text(if (busy) "Cancel and close" else "Close") } }
    )
}
