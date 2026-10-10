package com.mangalens.ui.web

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mangalens.orez.agent.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** A real user entry point to the same six typed tools used by the foreground agent adapter. */
@Composable
internal fun BrowserDomAgentDialog(tools: OrezBrowserTools, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var goalActive by remember { mutableStateOf(false) }
    val manualEnabled = !busy && !goalActive
    var query by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var fillText by remember { mutableStateOf("") }
    var fillElement by remember { mutableStateOf<BrowserDomElement?>(null) }
    var evidence by remember { mutableStateOf<BrowserDomEvidence?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<OrezToolCall?>(null) }
    val owner = tools.currentOwner()

    fun request(name: String, arguments: Map<String, String> = emptyMap()): OrezToolCall? = runCatching {
        val current = tools.currentOwner() ?: error("Open a loaded current page first.")
        OrezToolRegistry().call(name, mapOf("pageId" to current.pageId) + arguments)
    }.onFailure { status = it.message ?: "This browser request is unavailable." }.getOrNull()

    fun execute(call: OrezToolCall, approved: Boolean = false) {
        if (busy) return
        val current = tools.currentOwner()
        if (current == null) { status = "The page is loading or no longer available. Inspect it when it finishes."; return }
        val grant = runCatching { BrowserDomGrant.capture(current, call, OrezAgentContext(), approved) }
            .onFailure { status = it.message ?: "Inspect the current page before this action." }.getOrNull() ?: return
        busy = true; status = null
        scope.launch {
            try {
                when (val result = tools.execute(call, grant)) {
                    is OrezToolResult.Completed -> {
                        result.outputs["browserEvidence"]?.let { evidence = BrowserDomCodec.decode(it); fillElement = null }
                        if (result.outputs.containsKey("browserAction")) {
                            evidence = null; fillElement = null
                            status = "${call.name.removePrefix("browser_").replaceFirstChar { it.uppercase() }} dispatched to the page. Inspect its current state to check the result."
                        }
                    }
                    is OrezToolResult.Failed -> status = result.reason
                    is OrezToolResult.Pending -> status = result.reason
                    is OrezToolResult.Cancelled -> status = result.reason
                }
            } catch (cancelled: CancellationException) { throw cancelled }
              catch (_: Exception) { status = "The current page did not return readable browser evidence." }
            finally { busy = false }
        }
    }

    LaunchedEffect(tools) { request("browser_observe")?.let { execute(it) } }
    val shown = evidence?.takeIf { it.pageId == owner?.pageId }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Orez page tools") }, confirmButton = {
        TextButton(onClick = onDismiss) { Text("Close") }
    }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BrowserGoalPanel(tools, enabled = !busy, onActiveChange = { goalActive = it })
            if (owner == null) Text("Use a loaded page in the current Web tab. Navigation and tab changes retire previous page results.")
            OutlinedTextField(query, { if (it.length <= 256) query = it }, label = { Text("Find visible content") }, enabled = manualEnabled, singleLine = true)
            Row {
                TextButton(onClick = { request("browser_observe", mapOf("query" to query))?.let { execute(it) } }, enabled = owner != null && manualEnabled) { Text("Inspect") }
                TextButton(onClick = { request("browser_extract", mapOf("query" to query))?.let { execute(it) } }, enabled = owner != null && manualEnabled) { Text("Read page") }
            }
            OutlinedTextField(address, { if (it.length <= 8_192) address = it }, label = { Text("Web address") }, enabled = manualEnabled, singleLine = true)
            TextButton(onClick = { pending = request("browser_navigate", mapOf("value" to address.trim())) }, enabled = owner != null && manualEnabled && address.isNotBlank()) { Text("Open address") }
            Row {
                TextButton(onClick = { request("browser_scroll", mapOf("delta" to "-640"))?.let { execute(it) } }, enabled = owner != null && manualEnabled) { Text("Scroll up") }
                TextButton(onClick = { request("browser_scroll", mapOf("delta" to "640"))?.let { execute(it) } }, enabled = owner != null && manualEnabled) { Text("Scroll down") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            shown?.let { page ->
                Text(page.title.ifBlank { "Current page" }, style = MaterialTheme.typography.titleSmall)
                Text(page.address, style = MaterialTheme.typography.bodySmall)
                if (page.text.isNotBlank()) Text(page.text)
                if (page.truncated) Text("Showing bounded page evidence. Refine the search to inspect more specific content.", style = MaterialTheme.typography.bodySmall)
                page.elements.forEach { element ->
                    HorizontalDivider()
                    Text("${element.kind.replace('_', ' ')} · ${element.label.ifBlank { "Unnamed element" }}")
                    if (element.address.isNotBlank()) Text(element.address, style = MaterialTheme.typography.bodySmall)
                    when (element.kind) {
                        "link", "chapter_link", "button" -> TextButton(onClick = {
                            pending = request("browser_click", mapOf("elementId" to element.id))
                        }, enabled = manualEnabled) { Text("Click this element") }
                        "field" -> TextButton(onClick = { fillElement = element; fillText = "" }, enabled = manualEnabled) { Text("Fill this field") }
                    }
                }
            }
            fillElement?.takeIf { shown?.elements?.any { e -> e.id == it.id } == true }?.let { element ->
                OutlinedTextField(fillText, { if (it.length <= 256) fillText = it }, label = { Text(element.label.ifBlank { "Non-sensitive text" }) }, enabled = manualEnabled)
                TextButton(onClick = { pending = request("browser_fill", mapOf("elementId" to element.id, "text" to fillText)) }, enabled = manualEnabled && fillText.isNotBlank()) { Text("Apply text") }
            }
        }
    })
    pending?.let { call ->
        val label = shown?.elements?.firstOrNull { it.id == call.arguments["elementId"] }?.label.orEmpty()
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("Confirm page action") }, text = {
            Text(when (call.name) {
                "browser_navigate" -> "Open ${BrowserDomPolicy.displayAddress(call.arguments.getValue("value"))}?"
                "browser_fill" -> "Apply the entered text to ${label.ifBlank { "this field" }}? The website may react to this change."
                else -> "Click ${label.ifBlank { "this observed element" }}? The website may change account or external state."
            })
        }, confirmButton = {
            TextButton(onClick = { pending = null; execute(call, approved = true) }, enabled = manualEnabled && owner?.pageId == call.arguments["pageId"]) { Text("Confirm") }
        }, dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } })
    }
}
