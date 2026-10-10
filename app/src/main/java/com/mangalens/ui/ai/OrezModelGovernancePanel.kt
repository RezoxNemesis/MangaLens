package com.mangalens.ui.ai

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mangalens.orez.OrezModelGovernance
import com.mangalens.orez.OrezModelGovernanceRecord
import com.mangalens.orez.OrezModelLicenseText
import com.mangalens.orez.OrezModelPin
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Selected-pack metadata only; it neither identifies an active fallback nor changes model controls. */
@Composable
internal fun OrezModelGovernancePanel(selectedPin: OrezModelPin?) {
    val metadata = OrezModelGovernance.display(selectedPin)
    val record = selectedPin?.let(OrezModelGovernance::find)
    var showLicenses by remember(selectedPin) { mutableStateOf(false) }
    Text("Selected model metadata", style = MaterialTheme.typography.labelLarge)
    Text(listOf(metadata.publisher, metadata.license, metadata.compatibility, metadata.evaluation, metadata.resources).joinToString("\n"),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OrezTrainingLabPanel(selectedPin)
    if (record != null) TextButton(onClick = { showLicenses = true }) { Text("Model licenses") }
    if (showLicenses && record != null) key(record.pin) { ModelLicenseDialog(record) { showLicenses = false } }
}

private sealed interface ModelLicenseContent {
    data object Loading : ModelLicenseContent
    data object Unavailable : ModelLicenseContent
    data class Ready(val text: String) : ModelLicenseContent
}

@Composable
private fun ModelLicenseDialog(record: OrezModelGovernanceRecord, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val content by produceState<ModelLicenseContent>(ModelLicenseContent.Loading, record.pin, context) {
        val loaded: ModelLicenseContent = try {
            withContext(Dispatchers.IO) {
                suspend fun load(path: String, digest: String): String {
                    val bytes = context.assets.open(path).use { input ->
                        val output = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) throw IOException("Bundled license read made no progress")
                            if (output.size() + read > OrezModelLicenseText.MAX_BYTES) throw IOException("Bundled license exceeds byte limit")
                            output.write(buffer, 0, read)
                        }
                        output.toByteArray()
                    }
                    ensureActive()
                    return OrezModelLicenseText.verified(bytes, digest) ?: throw IOException("Bundled license verification failed")
                }
                val publisher = load(record.licenseAsset, record.licenseSha256)
                val runtime = load(OrezModelGovernance.RUNTIME_LICENSE_ASSET, OrezModelGovernance.RUNTIME_LICENSE_SHA256)
                ModelLicenseContent.Ready("${record.publisherRepository}\nPublisher revision ${record.publisherRevision}\n\n$publisher\n\nllama.cpp ${OrezModelGovernance.RUNTIME_REVISION}\n\n$runtime")
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { ModelLicenseContent.Unavailable }
        catch (_: SecurityException) { ModelLicenseContent.Unavailable }
        value = loaded
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Model licenses") }, text = {
        Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) {
            when (val result = content) {
                ModelLicenseContent.Loading -> Text("Loading bundled license text…")
                ModelLicenseContent.Unavailable -> Text("Bundled license text could not be verified. Model controls remain available.")
                is ModelLicenseContent.Ready -> Text(result.text, style = MaterialTheme.typography.bodySmall)
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}
