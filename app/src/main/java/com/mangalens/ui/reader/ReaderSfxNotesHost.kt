package com.mangalens.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mangalens.core.translation.memory.MemorySfxPresentation

internal interface ReaderSfxNoteSource {
    /** Reads only bounded display metadata and current gates; never opens a file or claims crop ownership. */
    fun noteGroup(pageIndex: Int): ReaderSfxNoteGroup?
}

/** Main-composition metadata registry. The image session, not this registry, owns all original resources. */
internal class ReaderSfxNoteRegistry : AutoCloseable {
    private val sources = mutableStateListOf<ReaderSfxNoteSource>()
    private var closed = false
    fun register(source: ReaderSfxNoteSource) {
        if (closed) return
        if (sources.any { it === source }) return
        if (sources.size >= ReaderSfxNotePolicy.MAX_SESSIONS) sources.removeAt(0)
        sources.add(source)
    }
    fun unregister(source: ReaderSfxNoteSource) { sources.indexOfFirst { it === source }.takeIf { it >= 0 }?.let(sources::removeAt) }
    fun visible(pageIndices: Set<Int>): List<ReaderSfxNoteGroup> {
        if (closed) return emptyList()
        val groups = pageIndices.filter { it >= 0 }.take(ReaderSfxNotePolicy.MAX_VISIBLE_PAGES).flatMap { page ->
            sources.mapNotNull { it.noteGroup(page) }
        }
        return ReaderSfxNotePolicy.visible(pageIndices, groups)
    }
    override fun close() { closed = true; sources.clear() }
}

internal val LocalReaderSfxNotes = staticCompositionLocalOf<ReaderSfxNoteRegistry?> { null }

/** Lives in the fixed Reader UI, outside every crop/split/zoom image plane. Default Reader has no note UI. */
@Composable
internal fun ReaderSfxNotesHost(registry: ReaderSfxNoteRegistry?, visiblePages: Set<Int>, enabled: Boolean,
    modifier: Modifier = Modifier) {
    if (!enabled || registry == null) return
    val groups = registry.visible(visiblePages)
    if (groups.isEmpty()) return
    var open by remember(registry, visiblePages) { mutableStateOf(false) }
    val count = groups.sumOf { it.rows.size }
    Surface(modifier, shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.surface.copy(alpha = .96f)) {
        TextButton({ open = true }, Modifier.semantics { contentDescription = "Open SFX reading notes" }) {
            Text("SFX reading notes ($count)", style = MaterialTheme.typography.labelLarge)
        }
    }
    if (open) AlertDialog(onDismissRequest = { open = false },
        title = { Text("SFX reading notes") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Your saved classifications. Notes stay outside the artwork and keep their text size when the page is cropped or zoomed.", style = MaterialTheme.typography.bodySmall)
                groups.forEach { group ->
                    group.rows.forEach { row ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Page ${group.pageIndex} · SFX #${row.nativeIndex + 1}", style = MaterialTheme.typography.labelMedium)
                            when (row.presentation) {
                                MemorySfxPresentation.ALONGSIDE -> Text("Translation note: ${row.translated}", style = MaterialTheme.typography.bodyMedium)
                                MemorySfxPresentation.ANNOTATE -> Text("Personal annotation: ${row.annotation ?: row.translated.take(ReaderSfxNotePolicy.MAX_ANNOTATION)}", style = MaterialTheme.typography.bodyMedium)
                                else -> Unit
                            }
                            Text(when (row.original) {
                                ReaderSfxOriginalReadiness.RESTORED -> "Verified original patch ready for the page."
                                ReaderSfxOriginalReadiness.PREPARING -> "Preparing verified original pixels…"
                                ReaderSfxOriginalReadiness.UNAVAILABLE -> "Original patch unavailable. Translated view kept; use Original in Reader tools."
                            }, style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.semantics { contentDescription = "SFX original restoration status" })
                        }
                    }
                    if (group.overflow > 0) Text("${group.overflow} more SFX on this page remain translated within the preview budget. Use whole-page Original to retain them all.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }, confirmButton = { TextButton({ open = false }) { Text("Close") } })
}
