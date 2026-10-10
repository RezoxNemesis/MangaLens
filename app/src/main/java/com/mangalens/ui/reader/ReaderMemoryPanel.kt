package com.mangalens.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mangalens.core.translation.ReaderMemoryController
import com.mangalens.core.translation.HindiRomanization
import com.mangalens.core.translation.PersonalMemoryEditorInputPolicy
import com.mangalens.core.translation.PersonalMemoryEditorValues
import com.mangalens.core.translation.ReaderBubbleAlternativeFormPolicy
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderMemoryPanel(controller: ReaderMemoryController, onRetranslate: (Int) -> Unit) {
    val state by controller.state.collectAsState()
    val scope = rememberCoroutineScope()
    if (!state.open) return
    ModalBottomSheet(onDismissRequest = controller::dismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Personal corrections · Page ${state.pageIndex}", style = MaterialTheme.typography.headlineSmall)
            Text("Your saved edits appear in this Reader. Original text and translated pages stay available.", style = MaterialTheme.typography.bodySmall)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it, color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = "Correction status: $it" }) }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val editor = state.editor
                if (editor == null) {
                    state.choices.forEach { choice ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(choice.originalOcr, style = MaterialTheme.typography.bodyMedium)
                                Text(choice.originalTranslation, style = MaterialTheme.typography.bodyMedium)
                                if (choice.editable) TextButton({ scope.launch { controller.selectBubble(choice.index) } }, enabled = !state.busy,
                                    modifier = Modifier.semantics { contentDescription = "Correct bubble ${choice.index + 1}" }) { Text("Edit this bubble") }
                                else Text("Retranslate this page to establish its original image coordinates.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (state.choices.isEmpty() || state.choices.any { !it.editable }) TextButton({ state.pageIndex?.let { onRetranslate(it) }; controller.dismiss() }, enabled = !state.busy) { Text("Retranslate this page") }
                } else {
                    val receipt = editor.captured.receipt
                    val edit = editor.bubble.correction?.edit
                    val initial = PersonalMemoryEditorInputPolicy.initial(receipt, editor.savedHindiDraft, edit)
                    val preset = state.generatedEdit?.let { ReaderBubbleAlternativeFormPolicy.preset(initial, it) } ?: initial
                    var ocr by rememberSaveable(receipt, editor.bubble.editRevision, state.generatedEdit) { mutableStateOf(preset.ocr) }
                    var translated by rememberSaveable(receipt, editor.bubble.editRevision, state.generatedEdit) { mutableStateOf(preset.translated) }
                    var hindi by rememberSaveable(receipt, editor.bubble.editRevision, state.generatedEdit) { mutableStateOf(preset.hindiDraft) }
                    state.generatedLabel?.let { Text(it, style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.semantics { contentDescription = "Unsaved alternative: $it" }) }
                    Text("Original OCR", style = MaterialTheme.typography.titleSmall)
                    Text(receipt.originalOcr, Modifier.semantics { contentDescription = "Original OCR: ${receipt.originalOcr}" })
                    Text("Original translation", style = MaterialTheme.typography.titleSmall)
                    Text(receipt.originalTranslation.orEmpty(), Modifier.semantics { contentDescription = "Original translation: ${receipt.originalTranslation.orEmpty()}" })
                    OutlinedTextField(ocr, { if (it.length <= 4096) ocr = it }, label = { Text("Personal OCR correction") },
                        enabled = !state.busy, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Personal OCR correction" })
                    OutlinedTextField(translated, { if (it.length <= 4096) translated = it }, label = { Text("Personal translation (${receipt.targetLanguage})") },
                        enabled = !state.busy, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Personal translation" })
                    if (HindiRomanization.isTarget(receipt.targetLanguage)) OutlinedTextField(hindi, { if (it.length <= 4096) hindi = it }, label = { Text("Hindi draft for Roman Hindi") },
                        supportingText = { Text("Keep the Hindi draft that produces this Roman Hindi translation.") }, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Personal Hindi draft" })
                    val values = PersonalMemoryEditorValues(ocr, translated, hindi)
                    val changed = PersonalMemoryEditorInputPolicy.changed(initial, values, receipt.targetLanguage)
                    Button({ scope.launch { controller.save(PersonalMemoryEditorInputPolicy.edit(receipt, editor.savedHindiDraft, values)) } },
                        enabled = changed && ocr.isNotBlank() && translated.isNotBlank() && !state.busy,
                        modifier = Modifier.semantics { contentDescription = "Save personal correction" }) { Text("Save correction") }
                    if (editor.bubble.correction != null) TextButton({ scope.launch { controller.remove() } }, enabled = !state.busy,
                        modifier = Modifier.semantics { contentDescription = "Remove personal correction" }) { Text("Remove correction history") }
                    Text("History", style = MaterialTheme.typography.titleMedium)
                    if (editor.bubble.correction == null) Text("No saved personal correction.")
                    else {
                        TextButton({ scope.launch { controller.rollback(0) } }, enabled = !state.busy,
                            modifier = Modifier.semantics { contentDescription = "Rollback personal correction to original" }) { Text("Restore original values") }
                        editor.bubble.correction.revisions.asReversed().forEach { revision ->
                            OutlinedCard(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("Revision ${revision.revision}", style = MaterialTheme.typography.labelLarge)
                                    Text(revision.edit.correctedOcr ?: receipt.originalOcr)
                                    Text(revision.edit.translated ?: receipt.originalTranslation.orEmpty())
                                    TextButton({ scope.launch { controller.rollback(revision.revision) } }, enabled = !state.busy,
                                        modifier = Modifier.semantics { contentDescription = "Rollback personal correction to revision ${revision.revision}" }) { Text("Restore this revision") }
                                }
                            }
                        }
                    }
                }
            }
            TextButton(controller::dismiss, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) { Text("Close") }
        }
    }
}
