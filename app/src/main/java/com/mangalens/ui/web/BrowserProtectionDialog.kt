package com.mangalens.ui.web

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mangalens.core.adblock.*

@Composable
internal fun BrowserProtectionDialog(site: AdBlockSite, profileLabel: String, ephemeral: Boolean,
    savedMode: AdBlockMode, globalEnabled: Boolean, current: Boolean, busy: Boolean,
    stats: AdBlockStats, error: String?, onSave: (AdBlockMode) -> Unit, onDismiss: () -> Unit) {
    var selected by remember(site.host, savedMode) { mutableStateOf(savedMode) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Protection Center") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${site.host} · $profileLabel")
            Text(if (ephemeral) "This exact host choice lasts only for this Private session." else "This exact host choice is saved locally for this profile. Subdomains have their own choices.")
            if (!globalEnabled) Text("The global ad blocker is off. Your saved site mode applies when it is enabled.")
            for (mode in AdBlockMode.entries) {
                Row {
                    RadioButton(selected == mode, onClick = { selected = mode }, enabled = current && !busy)
                    Column(Modifier.weight(1f)) {
                        Text(mode.name.lowercase().replaceFirstChar { it.uppercase() })
                        Text(when (mode) {
                            AdBlockMode.STRICT -> "Standard plus explicit analytics endpoints and marked ad containers. Shared media delivery stays available."
                            AdBlockMode.STANDARD -> "Existing network, known ad slots and visible provider Skip Ad protection."
                            AdBlockMode.ALLOW -> "Disable ad filtering for this exact top-level host. Secure connection, URL and privacy controls still apply."
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Text("Saved mode: ${savedMode.name.lowercase()}")
            Text("Local activity since reset: ${stats.blockedRequests} requests · ${stats.blockedTrackers} tracker-rule requests · ${stats.blockedPopups} popup windows")
            Text(if (stats.knownBytesSaved > 0) "Known bytes prevented: ${stats.knownBytesSaved}. Total bytes saved are unavailable." else "Estimated bytes saved: unavailable; blocked responses do not report their original size.", style = MaterialTheme.typography.bodySmall)
            Text("Recent events for this host · local memory only · no URL paths, queries or cookies", style = MaterialTheme.typography.bodySmall)
            val events = stats.events.filter { it.pageHost == site.host }.take(12)
            if (events.isEmpty()) Text("No recorded blocked requests or popups for this host.")
            events.forEach { Text("${it.host} · ${it.type} · ${it.rule}", style = MaterialTheme.typography.bodySmall) }
            if (!current) Text("The page or profile changed. Reopen Protection Center for the current page.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        TextButton(onClick = { onSave(selected) }, enabled = current && !busy,
            modifier = Modifier.semantics { contentDescription = "Save this host protection mode and reload" }) {
            Text(if (busy) "Saving…" else if (ephemeral) "Apply and reload" else "Save and reload")
        }
    }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") } })
}
