package com.mangalens.ui.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderDisplayControls(options: ReaderDisplayOptions, mode: String, translated: Boolean,
    onChange: (ReaderDisplayOptions) -> Unit, onPeek: (Boolean) -> Unit) {
    HorizontalDivider()
    Text("Display and screen", style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(ReaderComparison.TRANSLATED to "Translated", ReaderComparison.ORIGINAL to "Original",
            ReaderComparison.SIDE_BY_SIDE to "Side by side", ReaderComparison.SPLIT to "Split slider",
            ReaderComparison.HOLD_PEEK to "Hold to peek").forEach { (value, label) ->
            FilterChip(options.comparison == value, { onChange(options.copy(comparison = value)) }, label = { Text(label) },
                enabled = (translated || value in listOf(ReaderComparison.TRANSLATED, ReaderComparison.ORIGINAL)) &&
                    (mode != "guided" || value != ReaderComparison.SIDE_BY_SIDE))
        }
    }
    if (options.comparison == ReaderComparison.SPLIT) {
        Text("Translation covers ${(options.splitFraction * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        Slider(options.splitFraction, { onChange(options.copy(splitFraction = it)) }, valueRange = 0f..1f,
            modifier = Modifier.semantics { contentDescription = "Original translation split position" })
    }
    if (options.comparison == ReaderComparison.HOLD_PEEK) {
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth().pointerInput(Unit) {
                detectTapGestures(onPress = {
                    onPeek(true)
                    try { tryAwaitRelease() } finally { onPeek(false) }
                })
            }.semantics { contentDescription = "Hold to peek at original pages" }) {
            Text("Hold here to peek at the original", Modifier.padding(12.dp))
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ReaderWindowOrientation.entries.forEach { value ->
            FilterChip(options.window.orientation == value, { onChange(options.copy(window = options.window.copy(orientation = value))) },
                label = { Text(value.name.lowercase().replaceFirstChar { it.uppercase() }) })
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Rotation lock")
        Switch(options.window.rotationLocked, { onChange(options.copy(window = options.window.copy(rotationLocked = it))) })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Keep screen awake")
        Switch(options.window.keepScreenOn, { onChange(options.copy(window = options.window.copy(keepScreenOn = it))) })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Reader brightness")
        Switch(options.window.brightnessOverride != null, { onChange(options.copy(window = options.window.copy(brightnessOverride = if (it) .5f else null))) })
    }
    options.window.brightnessOverride?.let { value ->
        Slider(value, { onChange(options.copy(window = options.window.copy(brightnessOverride = it))) }, valueRange = .05f..1f,
            modifier = Modifier.semantics { contentDescription = "Reader brightness value" })
    }
    Text("Page spacing: ${options.pageSpacingDp} dp", style = MaterialTheme.typography.bodySmall)
    Slider(options.pageSpacingDp.toFloat(), { onChange(options.copy(pageSpacingDp = it.toInt())) }, valueRange = 0f..64f,
        modifier = Modifier.semantics { contentDescription = "Reader page spacing" })
    Text("Crop each margin: ${(options.marginCrop * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
    Slider(options.marginCrop, { onChange(options.copy(marginCrop = it)) }, valueRange = 0f..ReaderDisplayOptions.MAX_MARGIN_CROP,
        modifier = Modifier.semantics { contentDescription = "Reader display margin crop" }, enabled = mode != "guided")
    Text("Margin crop changes the view. Original image coordinates and saved translation stay available.", style = MaterialTheme.typography.bodySmall)
    if (mode == "guided") Text("Panel views show their full detected bounds. Margin crop and side-by-side comparison remain available in other reading modes.", style = MaterialTheme.typography.bodySmall)
    if (mode in setOf("spread", "guided")) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(if (mode == "guided") "Right-to-left panels" else "Right-to-left spread")
        Switch(options.spreadRtl, { onChange(options.copy(spreadRtl = it)) })
    }
    TextButton({ onChange(options.copy(controlsLocked = true)) }, modifier = Modifier.semantics { contentDescription = "Lock Reader screen controls" }) {
        Text("Lock screen controls")
    }
}
