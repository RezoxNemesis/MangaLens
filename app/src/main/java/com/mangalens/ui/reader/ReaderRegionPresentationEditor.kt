package com.mangalens.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mangalens.core.translation.memory.*

@Composable
internal fun ReaderRegionPresentationEditor(kind: MemoryUserRegionKind?, policy: MemorySfxPresentation,
    annotation: String, enabled: Boolean, onKind: (MemoryUserRegionKind?) -> Unit,
    onPolicy: (MemorySfxPresentation) -> Unit, onAnnotation: (String) -> Unit) {
    HorizontalDivider()
    Text("Region type · your classification", style = MaterialTheme.typography.titleSmall)
    Text("This is a personal label, not an automatic detector result.", style = MaterialTheme.typography.bodySmall)
    val choices = listOf<MemoryUserRegionKind?>(null) + MemoryUserRegionKind.entries
    choices.chunked(2).forEach { pair -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        pair.forEach { item -> FilterChip(kind == item, { onKind(item) }, enabled = enabled,
            label = { Text(item?.name?.lowercase()?.replaceFirstChar(Char::uppercase) ?: "Unclassified") },
            modifier = Modifier.weight(1f).semantics { contentDescription = "User region type ${item?.name ?: "UNCLASSIFIED"}" }) }
    } }
    if (kind == MemoryUserRegionKind.SFX) {
        Text("SFX presentation", style = MaterialTheme.typography.titleSmall)
        MemorySfxPresentation.entries.chunked(2).forEach { pair -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { item -> FilterChip(policy == item, { onPolicy(item) }, enabled = enabled,
                label = { Text(when (item) {
                    MemorySfxPresentation.KEEP_ORIGINAL -> "Keep original"
                    MemorySfxPresentation.ALONGSIDE -> "Alongside"
                    MemorySfxPresentation.REPLACE -> "Replace"
                    MemorySfxPresentation.ANNOTATE -> "Annotate"
                }) }, modifier = Modifier.weight(1f).semantics { contentDescription = "User SFX presentation ${item.name}" }) }
        } }
        Text("Keep restores original pixels. Alongside shows the translation in a reading note. Annotate restores the original and shows a labelled note. Replace keeps the translated lettering. If a safe patch cannot be restored, use whole-page Original.", style = MaterialTheme.typography.bodySmall)
        if (policy == MemorySfxPresentation.ANNOTATE) OutlinedTextField(annotation, { if (it.length <= 256) onAnnotation(it) },
            enabled = enabled, label = { Text("Personal SFX annotation") }, supportingText = { Text("Leave blank to show the saved translation as the note.") },
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Personal SFX annotation" })
    }
}
