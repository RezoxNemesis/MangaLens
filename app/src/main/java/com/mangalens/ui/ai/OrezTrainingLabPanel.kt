package com.mangalens.ui.ai

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mangalens.orez.OrezLabEntry
import com.mangalens.orez.OrezModelPin
import com.mangalens.orez.OrezTrainingLabFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.TimeoutCancellationException

/** Advanced, explicit local exchange. Ordinary model selection and startup require no lab setup. */
@Composable
internal fun OrezTrainingLabPanel(selectedPin: OrezModelPin?) {
    var open by remember(selectedPin) { mutableStateOf(false) }
    TextButton(onClick = { open = true }) { Text("Advanced Training Lab") }
    if (open) OrezTrainingLabDialog(selectedPin) { open = false }
}

private sealed interface LabDocumentOperation {
    data object Import : LabDocumentOperation
    data class Export(val digest: String) : LabDocumentOperation
    data class Request(val pin: OrezModelPin) : LabDocumentOperation
}

@Composable
private fun OrezTrainingLabDialog(selectedPin: OrezModelPin?, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<OrezLabEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var notice by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<LabDocumentOperation?>(null) }
    fun perform(action: suspend () -> List<OrezLabEntry>, success: String) {
        if (busy) return
        busy = true; notice = null
        scope.launch {
            try { entries = action(); notice = success }
            catch (_: TimeoutCancellationException) { notice = "Lab file access timed out. The provider may still be finishing; retry after it closes." }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice = "Lab exchange failed. Check the package format, access and available storage; then retry." }
            finally { busy = false }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val accepted = pending == LabDocumentOperation.Import; pending = null
        if (accepted && uri != null) perform({ OrezTrainingLabFiles.importPackage(app, uri) }, "Package checked and saved privately. Android qualification remains pending.")
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val accepted = pending as? LabDocumentOperation.Export; pending = null
        if (accepted != null && uri != null) perform({
            OrezTrainingLabFiles.export(app, uri, accepted.digest); OrezTrainingLabFiles.list(app)
        }, "Selected evidence package exported.")
    }
    val requestExporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val accepted = pending as? LabDocumentOperation.Request; pending = null
        if (accepted != null && uri != null) perform({
            OrezTrainingLabFiles.exportRequest(app, uri, accepted.pin); OrezTrainingLabFiles.list(app)
        }, "Evaluation request exported. Requested settings are separate from actual observations.")
    }
    LaunchedEffect(app) {
        try { entries = OrezTrainingLabFiles.list(app) }
        catch (_: TimeoutCancellationException) { notice = "Lab file access timed out. Retry after the previous access finishes." }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { notice = "Stored lab evidence could not be checked. Import or export is unavailable until access recovers." }
        finally { busy = false }
    }
    val enabled = !busy && pending == null
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Advanced Training Lab") }, text = {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            Text("Import locally prepared provenance or native evaluation evidence; export only the selected package. Host evaluation and unchecked reports cannot qualify an Android model.")
            Text("Packages stay in private lab storage and do not install weights, modify chat, train models or authorize tools.", style = MaterialTheme.typography.bodySmall)
            if (busy) Text("Checking local evidence…")
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            TextButton(enabled = enabled, onClick = { pending = LabDocumentOperation.Import; importer.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Import lab evidence package") }
            TextButton(enabled = enabled && selectedPin != null, onClick = {
                selectedPin?.let { pending = LabDocumentOperation.Request(it); requestExporter.launch("orez-evaluation-request.json") }
            }) { Text("Export selected model evaluation request") }
            Text("Saved packages (${entries.size}/8)", style = MaterialTheme.typography.labelLarge)
            entries.forEach { entry ->
                Text(entry.model?.modelId ?: "Unverified package", style = MaterialTheme.typography.labelMedium)
                Text("${entry.digest.take(12)} · ${entry.bytes} bytes\n${entry.description}", style = MaterialTheme.typography.bodySmall)
                if (selectedPin != null && entry.model != null && selectedPin != entry.model) Text("Different from the selected model.", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = enabled && entry.model != null, onClick = { pending = LabDocumentOperation.Export(entry.digest); exporter.launch("orez-lab-${entry.digest.take(12)}.zip") }) { Text("Export this package") }
                TextButton(enabled = enabled, onClick = { perform({ OrezTrainingLabFiles.delete(app, entry.digest) }, "Private package removed.") }) { Text("Remove this package") }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}
