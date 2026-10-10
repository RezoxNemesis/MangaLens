package com.mangalens.ui.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mangalens.core.translation.ReaderBubbleToolsController
import com.mangalens.core.translation.ReaderBubbleAlternativeKind
import com.mangalens.orez.SavedBubbleOrezAnswerKind
import com.mangalens.orez.SavedBubbleOrezAnswerPolicy

/** This sheet receives saved values and verified previews, never crop coordinates from tap geometry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderBubbleToolsPanel(controller: ReaderBubbleToolsController, onRetranslate: (Int) -> Unit) {
    val state by controller.state.collectAsState()
    if (!state.open) return
    ModalBottomSheet(onDismissRequest = controller::dismiss) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Saved bubble · Page ${state.pageIndex}", style = MaterialTheme.typography.headlineSmall)
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.message?.let { Text(it, color = if (state.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { contentDescription = "Saved bubble status: $it" }) }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val view = state.view
                if (view != null) {
                    state.preview?.let { ownership -> OriginalBubblePreview(ownership) }
                    Text("Original OCR", style = MaterialTheme.typography.titleSmall)
                    Text(view.originalOcr, Modifier.semantics { contentDescription = "Saved bubble original OCR: ${view.originalOcr}" })
                    Text("Saved translation (${view.targetLanguage})", style = MaterialTheme.typography.titleSmall)
                    Text(view.originalTranslation, Modifier.semantics { contentDescription = "Saved bubble translation: ${view.originalTranslation}" })
                    view.personalOcr?.let {
                        Text("Personal OCR correction", style = MaterialTheme.typography.titleSmall)
                        Text(it)
                    }
                    view.personalTranslation?.let {
                        Text("Personal translation correction", style = MaterialTheme.typography.titleSmall)
                        Text(it)
                    }
                    if (view.hasOriginalCrop) {
                        TextButton(controller::edit, enabled = !state.busy,
                            modifier = Modifier.semantics { contentDescription = "Edit selected saved bubble" }) { Text("Edit personal correction") }
                        if (controller.regionActionsAvailable) {
                            TextButton(controller::retryOriginalOcr, enabled = !state.busy,
                                modifier = Modifier.semantics { contentDescription = "Retry selected bubble OCR from original pixels" }) { Text("Retry OCR from original pixels") }
                            TextButton(controller::regenerateTranslation, enabled = !state.busy,
                                modifier = Modifier.semantics { contentDescription = "Regenerate selected bubble translation alternatives" }) { Text("Regenerate translation alternatives") }
                        }
                        if (view.linkedSeriesId != null) {
                            Text("Series: ${view.linkedSeriesTitle.orEmpty()}", style = MaterialTheme.typography.bodySmall)
                            if (state.glossaryEditor == null) TextButton(controller::prepareGlossary, enabled = !state.busy,
                                modifier = Modifier.semantics { contentDescription = "Add selected bubble to glossary" }) { Text("Add to series glossary") }
                        } else Text("Link this chapter to a series in Library to save a glossary term.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("This saved translation has no original image coordinates. You can read its text; retranslate the page to edit it or show its original crop.",
                            style = MaterialTheme.typography.bodySmall)
                        TextButton({ state.pageIndex?.let(onRetranslate); controller.dismiss() }, enabled = !state.busy) { Text("Retranslate this page") }
                    }
                    state.glossaryEditor?.let { editor ->
                        var source by remember(editor.captured, editor.bubble.editRevision) {
                            mutableStateOf((editor.bubble.correction?.edit?.correctedOcr ?: view.originalOcr).take(256))
                        }
                        var preferred by remember(editor.captured, editor.bubble.editRevision) {
                            mutableStateOf((editor.bubble.correction?.edit?.translated ?: view.originalTranslation).take(256))
                        }
                        Text("Series glossary term", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(source, { if (it.length <= 256) source = it }, label = { Text("Phrase from this bubble") }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Selected bubble glossary phrase" })
                        OutlinedTextField(preferred, { if (it.length <= 256) preferred = it }, label = { Text("Preferred wording (${view.targetLanguage})") }, enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Selected bubble glossary wording" })
                        Button({ controller.saveGlossary(source, preferred) }, enabled = !state.busy && source.isNotBlank() && preferred.isNotBlank(),
                            modifier = Modifier.semantics { contentDescription = "Save selected bubble glossary term" }) { Text("Save term") }
                    }
                    state.alternatives.forEach { alternative ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(when (alternative.kind) {
                                    ReaderBubbleAlternativeKind.ORIGINAL_OCR -> "Original OCR · ${alternative.recognizerScript.orEmpty()} · ${alternative.pixelVariant.orEmpty()}"
                                    ReaderBubbleAlternativeKind.ON_DEVICE_DRAFT -> "On-device draft"
                                    ReaderBubbleAlternativeKind.PINNED_LOCAL_REFINEMENT -> "Pinned local refinement"
                                }, style = MaterialTheme.typography.titleSmall)
                                Text(alternative.sourceText)
                                alternative.translated?.let { Text(it.text) }
                                Text("Unsaved alternative", style = MaterialTheme.typography.bodySmall)
                                TextButton({ controller.chooseAlternative(alternative) }, enabled = !state.busy,
                                    modifier = Modifier.semantics { contentDescription = "Review bubble alternative ${alternative.id} in correction editor" }) {
                                    Text("Review in correction editor")
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    Text("Ask Orez about this saved text", style = MaterialTheme.typography.titleMedium)
                    Text("Uses this bubble and up to two saved neighbours on the same page. An explanation is separate from the saved translation.",
                        style = MaterialTheme.typography.bodySmall)
                    TextButton({ controller.ask("Explain the meaning and tone of this saved bubble, using only the supplied saved text.") }, enabled = !state.busy,
                        modifier = Modifier.semantics { contentDescription = "Explain selected saved bubble" }) { Text("Explain this bubble") }
                    var question by remember(state.pageIndex, state.letteringIndex, view.originalOcr, view.originalTranslation) { mutableStateOf("") }
                    OutlinedTextField(question, { if (it.length <= SavedBubbleOrezAnswerPolicy.MAX_QUESTION_CHARACTERS) question = it },
                        label = { Text("Question about this text") }, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Saved bubble question" })
                    Button({ controller.ask(question) }, enabled = question.isNotBlank() && !state.busy,
                        modifier = Modifier.semantics { contentDescription = "Ask Orez about selected saved bubble" }) { Text("Ask Orez") }
                    state.answer?.let { answer ->
                        Text(if (answer.kind == SavedBubbleOrezAnswerKind.MODEL_EXPLANATION) "Local explanation" else "Saved text · explanation unavailable",
                            style = MaterialTheme.typography.titleSmall)
                        Text(answer.text, Modifier.semantics { contentDescription = "Saved bubble answer: ${answer.text}" })
                    }
                }
            }
            TextButton(controller::dismiss, modifier = Modifier.fillMaxWidth()) { Text("Close") }
        }
    }
}

@Composable
private fun OriginalBubblePreview(ownership: ReaderBubblePreviewOwnership<ReaderBubbleOriginalCrop>) {
    var crop by remember(ownership) { mutableStateOf<ReaderBubbleOriginalCrop?>(null) }
    DisposableEffect(ownership) {
        crop = ownership.claim()
        onDispose { ownership.disposeUi() }
    }
    crop?.let { original ->
        val bitmap = remember(original) { original.bitmap.asImageBitmap() }
        Image(bitmap, "Original image crop for the selected saved bubble",
            Modifier.fillMaxWidth().heightIn(max = 240.dp), contentScale = ContentScale.Fit)
    }
}
