package com.mangalens.ui.ai

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mangalens.orez.*

@Composable
internal fun OrezResourceRoutingPanel(state: OrezResourcePreferenceState, onSelect: (OrezResourceMode) -> Unit) {
    Text("Resource mode", style = MaterialTheme.typography.labelLarge)
    if (!state.loaded) Text("Loading saved resource preference…", style = MaterialTheme.typography.bodySmall)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OrezResourceMode.entries.forEach { mode ->
            FilterChip(selected = state.mode == mode, enabled = state.loaded && !state.saving,
                onClick = { onSelect(mode) }, label = { Text(mode.label) }, modifier = Modifier.weight(1f))
        }
    }
    Text(when (state.mode) {
        OrezResourceMode.FAST -> "New requests try the smallest verified installed model first."
        OrezResourceMode.BALANCED -> "New requests keep your selected model and its existing lighter fallback."
        OrezResourceMode.MAXIMUM -> "Context and planning requests prefer installed Core; routine chat tries smaller models. Max weights are unavailable pending evaluation."
    }, style = MaterialTheme.typography.bodySmall)
    Text("Routing uses task hints and live resource pressure. Weight size estimates cost; model quality and speed are not yet qualified. Captured jobs keep their original pin. Memory and cooling safeguards always apply. CPU runtime; GPU/NPU acceleration is not enabled.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.saving) Text("Saving resource preference…", style = MaterialTheme.typography.bodySmall)
    state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
}
