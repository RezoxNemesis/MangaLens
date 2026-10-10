package com.mangalens.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
internal fun ReaderGuidedControls(session: ReaderGuidedSession?, hasPreviousPage: Boolean, hasNextPage: Boolean,
    onAdvance: (Int) -> Unit, onWhole: () -> Unit) {
    Text("Auto panels · gutter estimates", style = MaterialTheme.typography.labelSmall)
    if (session == null || session.failed) Text("Panel estimates unavailable. Showing the whole page.", style = MaterialTheme.typography.bodySmall)
    else if (session.busy) Text("Finding panel gutters… Showing the whole page.", style = MaterialTheme.typography.bodySmall)
    else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton({ onAdvance(-1) }, enabled = session.panelIndex > 0 || hasPreviousPage,
            modifier = Modifier.semantics { contentDescription = "Previous guided panel" }) { Text("‹ Panel") }
        Column {
            Text(if (session.wholePage) "Whole page" else "Panel ${session.panelIndex + 1} / ${session.panelCount}", style = MaterialTheme.typography.labelSmall)
            TextButton(onWhole) { Text(if (session.wholePage) "Return to panel" else "Whole page") }
        }
        TextButton({ onAdvance(1) }, enabled = session.panelIndex < session.panelCount - 1 || hasNextPage,
            modifier = Modifier.semantics { contentDescription = "Next guided panel" }) { Text("Panel ›") }
    }
}
