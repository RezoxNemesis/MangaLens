package com.mangalens.ui.web

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mangalens.orez.agent.*
import kotlinx.coroutines.*

@Composable
internal fun BrowserCleanReadingDialog(tools: OrezBrowserTools, onOpenReader: (String) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var source by remember(tools) { mutableStateOf<BrowserDomOwner?>(null) }
    var evidence by remember(tools) { mutableStateOf<BrowserDomEvidence?>(null) }
    var busy by remember(tools) { mutableStateOf(false) }
    var status by remember(tools) { mutableStateOf<String?>(null) }
    var job by remember(tools) { mutableStateOf<Job?>(null) }
    var epoch by remember(tools) { mutableLongStateOf(0L) }
    suspend fun read(owner: BrowserDomOwner, name: String): BrowserDomEvidence {
        val call = OrezToolRegistry().call(name, mapOf("pageId" to owner.pageId))
        val result = tools.execute(call, BrowserDomGrant.capture(owner, call, OrezAgentContext()))
        val completed = result as? OrezToolResult.Completed ?: error((result as? OrezToolResult.Failed)?.reason ?: "Page text is unavailable.")
        return BrowserDomCodec.decode(requireNotNull(completed.outputs["browserEvidence"]))
    }
    fun refresh() {
        if (busy) return
        val owner = tools.currentOwner() ?: run { status = "Wait for a loaded current Web page."; return }
        source = owner; evidence = null; status = null; busy = true
        val requestEpoch = ++epoch
        job = scope.launch {
            try {
                val text = read(owner, "browser_extract")
                val links = read(owner, "browser_observe")
                if (requestEpoch == epoch && tools.currentOwner() == owner) {
                    require(text.pageId == owner.pageId && links.pageId == owner.pageId)
                    evidence = links.copy(text = text.text, elements = links.elements.filter { it.kind == "chapter_link" },
                        truncated = text.truncated || links.truncated)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (failure: Exception) { if (requestEpoch == epoch) status = failure.message ?: "Page reading is unavailable." }
            finally { if (requestEpoch == epoch) { busy = false; job = null } }
        }
    }
    fun openChapter(element: BrowserDomElement) {
        val owner = source ?: return
        if (busy || tools.currentOwner() != owner || evidence?.elements?.none { it.id == element.id } != false) return
        val selection = BrowserChapterSelection.capture(owner, element.id, OrezAgentContext())
        val requestEpoch = ++epoch; busy = true; status = null
        job = scope.launch {
            try {
                if (tools.openSelectedChapter(selection, onOpenReader)) onDismiss()
                else if (requestEpoch == epoch) status = "That chapter link changed or expired. Refresh reading before selecting it again."
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { if (requestEpoch == epoch) status = "The selected chapter could not be opened." }
            finally { if (requestEpoch == epoch) { busy = false; job = null } }
        }
    }
    LaunchedEffect(tools) { refresh() }
    LaunchedEffect(source) {
        val owner = source ?: return@LaunchedEffect
        while (isActive) {
            delay(500)
            if (tools.currentOwner() != owner) {
                epoch++; job?.cancel(); job = null; busy = false; evidence = null; source = null
                status = "The source page changed. Refresh to read the current loaded page."
                break
            }
        }
    }
    val shown = evidence?.takeIf { it.pageId == tools.currentOwner()?.pageId }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Clean reading") }, confirmButton = {
        TextButton(onClick = onDismiss) { Text("Close") }
    }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = ::refresh, enabled = !busy && tools.currentOwner() != null) { Text("Refresh reading") }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            shown?.let { page ->
                Text(page.title.ifBlank { "Current page" }, style = MaterialTheme.typography.titleSmall)
                Text(page.address, style = MaterialTheme.typography.bodySmall)
                if (page.text.isBlank()) Text("No rendered article text was found. Image chapters can be opened in Reader.")
                else SelectionContainer { Text(page.text) }
                if (page.truncated) Text("Showing a bounded portion of the page. Chapter links below are currently visible links, not a verified chapter catalog.", style = MaterialTheme.typography.bodySmall)
                if (page.elements.isNotEmpty()) {
                    HorizontalDivider(); Text("Detected chapter links", style = MaterialTheme.typography.titleSmall)
                    page.elements.forEach { element ->
                        Text(element.label.ifBlank { "Chapter link" })
                        if (element.address.isNotBlank()) Text(element.address, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { openChapter(element) }, enabled = !busy) { Text("Open selected chapter in Reader") }
                    }
                }
            }
        }
    })
}

@Composable
internal fun BrowserResearchDialog(onReview: (String) -> Unit, onDismiss: () -> Unit) {
    var question by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Research in Orez") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Enter your public question. It will be placed in Orez's message field; review it and press Send to begin research.")
            OutlinedTextField(question, { if (it.length <= 512) { question = it; error = null } }, label = { Text("Your public question") }, maxLines = 4)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton(onClick = {
        val typed = question.trim()
        if (BrowserResearchInput.command(typed) == null) error = "Use one short question without line breaks or quotation marks."
        else onReview(typed)
    }, enabled = question.isNotBlank()) { Text("Review in Orez") } }, dismissButton = {
        TextButton(onClick = onDismiss) { Text("Cancel") }
    })
}

@Composable
internal fun BrowserPublicLinkDialog(link: BrowserPublicLink, share: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (share) "Share public link" else "Copy public link") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SelectionContainer { Text(link.address) }
            Text(link.detail, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onConfirm) { Text(if (share) "Choose sharing app" else "Copy this address") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}
