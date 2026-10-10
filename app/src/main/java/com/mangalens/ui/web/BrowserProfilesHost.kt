package com.mangalens.ui.web

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Selection is never saved into Activity state; a cold process always returns to Normal. */
@Composable
internal fun BrowserProfilesHost(content: @Composable (BrowserProfileOwner, BrowserWorkspaceSession, (() -> Unit), (() -> Unit)) -> Unit) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var choice by remember { mutableStateOf(BrowserProfileChoice.Normal) }
    var selectionSerial by remember { mutableLongStateOf(0) }
    val owner = remember(choice, selectionSerial) { BrowserProfileOwner(choice) }
    val session = remember(choice, app) { BrowserWorkspaceRepository.profileSession(app, choice) }
    val catalog = remember(app) { BrowserProfileCatalog.shared(app) }
    val saved by catalog.state.collectAsState()
    val nativeNotice by BrowserProfileRuntime.notice.collectAsState()
    var show by remember { mutableStateOf(false) }
    var supported by remember { mutableStateOf<Boolean?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val dialogAuthority = remember { BrowserProfileDialogAuthority() }
    val latestOwner by rememberUpdatedState(owner)
    var addSerial by remember { mutableLongStateOf(0) }
    fun dismiss() { dialogAuthority.retire(); show = false }
    fun retire() { owner.retire(); BrowserWorkspaceRepository.retirePrivate(choice); BrowserProfileRuntime.leave(choice) }
    fun select(next: BrowserProfileChoice) {
        dialogAuthority.retire()
        if (next == choice && owner.isCurrent()) { show = false; return }
        try {
            BrowserProfileRuntime.select(next) // Reserve before old owner is retired; never silently fall back.
            retire(); selectionSerial++; choice = next; error = null; show = false
        } catch (failure: Exception) { error = failure.message?.take(240) ?: "The requested browser profile is unavailable." }
    }
    DisposableEffect(owner) { onDispose { dialogAuthority.retire(); owner.retire(); BrowserWorkspaceRepository.retirePrivate(owner.choice); BrowserProfileRuntime.leave(owner.choice) } }
    content(owner, session, {
        dialogAuthority.open(owner)
        show = true
        try { supported = BrowserProfileRuntime.supported() }
        catch (_: Exception) { supported = false; error = "Profile support could not be checked. Normal remains available." }
    }, { select(BrowserProfileChoice.Normal) })
    if (show) AlertDialog(onDismissRequest = ::dismiss, title = { Text("Browser profiles") }, confirmButton = {
        TextButton(onClick = ::dismiss) { Text("Close") }
    }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Current: ${choice.label}")
            Text("Profiles have separate cookies, tabs and history. Private is temporary; native source handoffs are available only in Normal.", style = MaterialTheme.typography.bodySmall)
            if (supported == false) Text("This Android WebView does not support isolated profiles. Update Android System WebView to use Private, Work or Custom.")
            TextButton(onClick = { select(BrowserProfileChoice.Normal) }) { Text("Normal") }
            TextButton(onClick = { select(BrowserProfileChoice.privateSession()) }, enabled = supported == true && !choice.ephemeral) { Text("New Private session") }
            TextButton(onClick = { select(BrowserProfileChoice.Work) }, enabled = supported == true) { Text("Work") }
            saved.custom.forEach { profile -> TextButton(onClick = { select(profile) }, enabled = supported == true) { Text(profile.label) } }
            if (saved.loading) Text("Loading saved profiles…")
            saved.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (supported == true && !saved.loading && saved.error == null && saved.custom.size < BrowserProfilePolicy.MAX_CUSTOM) {
                OutlinedTextField(label, { label = it.take(40).filterNot(Char::isISOControl) }, singleLine = true, label = { Text("Custom profile name") })
                TextButton(onClick = {
                    val accepted = label
                    val ticket = dialogAuthority.capture(owner) ?: return@TextButton
                    val operation = ++addSerial
                    adding = true
                    scope.launch {
                        try {
                            val created = catalog.add(accepted).await()
                            // Creation is durable; auto-selection belongs only to this exact open dialog.
                            if (dialogAuthority.accepts(ticket, latestOwner) && show) { label = ""; select(created) }
                        }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            if (dialogAuthority.accepts(ticket, latestOwner) && show) error = "Custom profile could not be saved. Existing profiles are retained."
                        }
                        finally { if (operation == addSerial) adding = false }
                    }
                }, enabled = label.isNotBlank() && !adding) { Text("Create Custom profile (${saved.custom.size}/4)") }
            }
            if (choice.ephemeral) TextButton(onClick = { select(BrowserProfileChoice.Normal) }) { Text("Close Private and return to Normal") }
            (error ?: nativeNotice)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    })
}
