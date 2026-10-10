package com.mangalens.ui.reader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mangalens.core.translation.SavedOcrGeometryKind
import com.mangalens.core.translation.SavedOcrOutcome
import com.mangalens.core.translation.SavedPageOcrDiagnostics
import java.util.Locale

internal object ReaderOcrDiagnosticsPolicy {
    fun matchesRevision(value: SavedPageOcrDiagnostics?, contentRevision: String?): Boolean {
        if (value == null) return false
        val digest = contentRevision?.substringBefore(':')?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        return digest == null || digest == value.sourceSha256
    }
    fun rejected(value: SavedOcrOutcome): Boolean = value in setOf(SavedOcrOutcome.QUALITY_REJECTED, SavedOcrOutcome.TRANSLATION_FAILED,
        SavedOcrOutcome.GEOMETRY_CONFLICT, SavedOcrOutcome.RECONSTRUCTION_DEFERRED, SavedOcrOutcome.FITTING_DEFERRED)
    fun confidence(value: Float?): String = value?.takeIf { it.isFinite() && it > 0f && it <= 1f }
        ?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "unavailable"
}

/** Opt-in diagnostics share the actual image plane; there is deliberately no pointer handler. */
@Composable
internal fun ReaderOcrDiagnosticsOverlay(value: SavedPageOcrDiagnostics?, modifier: Modifier = Modifier) {
    if (value == null) return
    val labelPaint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.YELLOW; typeface = android.graphics.Typeface.DEFAULT_BOLD
    } }
    Canvas(modifier.semantics { contentDescription = "Recorded OCR region boxes" }) {
        val sx = size.width / value.width; val sy = size.height / value.height
        val stroke = 1.5.dp.toPx()
        value.proposedPanels.forEach { box -> drawRect(Color.Cyan.copy(alpha = .65f), Offset(box.left * sx, box.top * sy),
            Size((box.right - box.left) * sx, (box.bottom - box.top) * sy), style = Stroke(stroke)) }
        value.findings.forEach { row ->
            val box = row.bounds
            val color = if (ReaderOcrDiagnosticsPolicy.rejected(row.outcome)) Color.Red else Color.Yellow
            drawRect(color, Offset(box.left * sx, box.top * sy), Size((box.right - box.left) * sx, (box.bottom - box.top) * sy), style = Stroke(stroke))
            row.writableBounds?.let { writable -> drawRect(Color.Green.copy(alpha = .7f), Offset(writable.left * sx, writable.top * sy),
                Size((writable.right - writable.left) * sx, (writable.bottom - writable.top) * sy), style = Stroke(stroke)) }
            row.lines.forEach { line -> drawRect(color.copy(alpha = .4f), Offset(line.left * sx, line.top * sy),
                Size((line.right - line.left) * sx, (line.bottom - line.top) * sy), style = Stroke(stroke / 2)) }
            labelPaint.textSize = 10.dp.toPx()
            drawContext.canvas.nativeCanvas.drawText("${row.ordinal + 1}", box.left * sx, maxOf(labelPaint.textSize, box.top * sy), labelPaint)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderOcrDiagnosticsControls(value: SavedPageOcrDiagnostics?, pageIndex: Int?, presentationEpoch: Long?,
    visible: Boolean, onVisible: (Boolean) -> Unit, onRetry: () -> Unit) {
    var details by remember(pageIndex, value?.sourceSha256, value, presentationEpoch) { mutableStateOf(false) }
    HorizontalDivider()
    Text("Developer OCR diagnostics", style = MaterialTheme.typography.titleSmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Show recorded region boxes", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        Switch(visible, onVisible, modifier = Modifier.semantics { contentDescription = "Show developer OCR region boxes" })
    }
    if (value == null) Text("No recorded OCR diagnostics for this saved page. Retranslate from the original to record them.", style = MaterialTheme.typography.bodySmall)
    else TextButton({ details = true }, modifier = Modifier.semantics { contentDescription = "Open recorded OCR diagnostics" }) { Text("Recorded OCR details") }
    if (details && value != null) ModalBottomSheet(onDismissRequest = { details = false }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Recorded OCR · Page $pageIndex", style = MaterialTheme.typography.titleLarge)
            Text("${value.acceptedRegionCount} accepted engine regions; ${value.findings.size} recorded details. Reconstruction v${value.reconstructionVersion}.")
            Text("Confidence is ML Kit-derived where supplied. Missing confidence stays unavailable. Red boxes show a translation or placement rejection, not an invented OCR rejection.", style = MaterialTheme.typography.bodySmall)
            Text("Cyan boxes are original-image gutter proposals; green boxes are recorded writable areas. Numbers retain the engine's observed reading order. Bubble tails, semantic kinds and pre-fusion rejected recognizer candidates were not recorded.", style = MaterialTheme.typography.bodySmall)
            value.findings.forEach { row ->
                HorizontalDivider()
                Text("#${row.ordinal + 1} · ${row.recognizerScript} · confidence ${ReaderOcrDiagnosticsPolicy.confidence(row.mlKitDerivedConfidence)}", style = MaterialTheme.typography.titleSmall)
                Text("${row.outcome.name.lowercase().replace('_', ' ')}${row.nativeLetteringIndex?.let { " · saved region ${it + 1}" }.orEmpty()}")
                Text("Original sample box ${row.bounds.left},${row.bounds.top}–${row.bounds.right},${row.bounds.bottom}; ${row.lines.size} recorded lines${row.proposedPanel?.let { " · gutter group ${it + 1}" }.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                row.fittedSizeAtOne?.let { Text("Generation fit at 1×: ${String.format(Locale.ROOT, "%.1f", it)} source-sample pixels", style = MaterialTheme.typography.bodySmall) }
                when (row.geometryKind) {
                    SavedOcrGeometryKind.VERTICAL_GEOMETRY -> Text("Vertical text geometry", style = MaterialTheme.typography.bodySmall)
                    SavedOcrGeometryKind.SMALL_KANA_ADJACENT -> Text("Small kana beside #${(row.smallKanaNeighbourOrdinal ?: 0) + 1}: furigana-like association suggestion; not merged or deleted.", style = MaterialTheme.typography.bodySmall)
                    else -> Unit
                }
            }
            TextButton({ details = false; onRetry() }) { Text("Retranslate this page from original") }
            TextButton({ details = false }) { Text("Close diagnostics") }
        }
    }
}
