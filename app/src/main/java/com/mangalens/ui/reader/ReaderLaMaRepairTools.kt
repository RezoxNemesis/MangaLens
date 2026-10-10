package com.mangalens.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mangalens.core.translation.ReaderBubbleToolsController
import com.mangalens.core.translation.inpainting.*

/** Explicit experimental tools are outside the page raster/pan/zoom plane; no final-use or save action. */
@Composable
internal fun ReaderLaMaRepairTools(controller: ReaderBubbleToolsController, repair: SavedBubbleArtworkRepair,
    preview: ReaderBubblePreviewOwnership<ReaderLaMaRepairPreview>?, busy: Boolean) {
    var expanded by remember(controller) { mutableStateOf(false) }
    TextButton({ expanded = !expanded }, modifier = Modifier.semantics { contentDescription = "Experimental artwork repair tools" }) {
        Text(if (expanded) "Hide experimental artwork repair" else "Experimental artwork repair")
    }
    if (!expanded) return
    // Opening this explicit section is the first access to the pack manager. No startup hash/native work.
    val manager = remember(repair) { repair.model }
    val model by manager.state.collectAsState()
    val uriHandler = LocalUriHandler.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(controller, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumed = true
            else if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                resumed = false; controller.clearArtworkPreview()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); controller.clearArtworkPreview() }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Optional LaMa repair preview", style = MaterialTheme.typography.titleSmall)
            Text("Carve/LaMa-ONNX · Apache 2.0 · pinned 208 MB FP32 pack. CPU only; may need more memory than this device offers.", style = MaterialTheme.typography.bodySmall)
            TextButton({ uriHandler.openUri(LaMaReconstructionPin.LICENSE_URL) }) { Text("Publisher license and attribution") }
            Text("Experimental and unqualified for artwork fidelity. Uses a conservative original-pixel glyph estimate. Shows a temporary comparison only.", style = MaterialTheme.typography.bodySmall)
            Text(when {
                model.restartRequired -> "Repair cleanup could not be confirmed. Restart the app before more local work."
                model.stopping -> "Pausing download; current partial retained."
                model.downloading -> "Downloading ${model.receivedBytes / 1_000_000} / ${model.totalBytes / 1_000_000} MB"
                model.checking -> "Verifying pinned private pack…"
                !model.verified -> "Pack not installed or not verified."
                model.runtimeSupported == false -> "Pack checksum matches; runtime graph was not accepted."
                model.runtimeSupported == true -> "Pack checksum and live CPU graph accepted; image fidelity remains unqualified."
                else -> "Pack checksum verified. Live runtime graph is checked when you request a preview."
            }, modifier = Modifier.semantics { contentDescription = "Experimental artwork repair model status" })
            model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (model.downloading || model.stopping || model.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
            val transferIdle = !model.downloading && !model.checking && !model.stopping && !model.restartRequired
            if (model.downloading) TextButton(manager::pauseDownload, enabled = !model.stopping) { Text("Pause pack download") }
            else {
                if (!model.verified) TextButton(manager::download, enabled = transferIdle) {
                    Text(if (model.receivedBytes > 0) "Resume pinned pack download" else "Download pinned pack")
                }
                TextButton(manager::recheck, enabled = transferIdle && !busy) { Text("Recheck pack") }
                TextButton(manager::remove, enabled = transferIdle && !busy) { Text("Remove repair pack and partial") }
            }
            Button(controller::previewArtworkRepair, enabled = resumed && transferIdle && model.verified && !busy,
                modifier = Modifier.semantics { contentDescription = "Preview experimental repair of selected original bubble" }) {
                Text("Preview repair from original pixels")
            }
            preview?.let { ExperimentalRepairPreview(it) }
        }
    }
}

@Composable
private fun ExperimentalRepairPreview(ownership: ReaderBubblePreviewOwnership<ReaderLaMaRepairPreview>) {
    var preview by remember(ownership) { mutableStateOf<ReaderLaMaRepairPreview?>(null) }
    DisposableEffect(ownership) { preview = ownership.claim(); onDispose { ownership.disposeUi() } }
    preview?.takeIf { it.isCurrent() }?.let { value ->
        val original = remember(value) { value.original.asImageBitmap() }
        val proposed = remember(value) { value.proposed.asImageBitmap() }
        Text("Verified original context")
        Image(original, "Original artwork context for experimental repair", Modifier.fillMaxWidth().heightIn(max = 220.dp), contentScale = ContentScale.Fit)
        Text("Experimental repaired context · ${value.selectedPixels} estimated glyph pixels")
        Image(proposed, "Unsaved experimental artwork repair preview", Modifier.fillMaxWidth().heightIn(max = 220.dp), contentScale = ContentScale.Fit)
        Text("No lettering is rendered into this preview. It cannot replace or save the page.", style = MaterialTheme.typography.bodySmall)
    }
}
