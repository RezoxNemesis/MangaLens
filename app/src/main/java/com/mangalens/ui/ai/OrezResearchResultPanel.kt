package com.mangalens.ui.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mangalens.orez.research.OrezResearchEvidence

/** Source quotations stay outside ordinary assistant history and cannot execute tools. */
@Composable
internal fun OrezResearchResultPanel(evidence: OrezResearchEvidence, onDismiss: () -> Unit, onOpenSource: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Saved source excerpts") },
        text = { Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(evidence.query, style = MaterialTheme.typography.titleSmall)
            Text("Current facts remain unverified. Retrieval dates show when these pages were read; they are not publication dates.", style = MaterialTheme.typography.bodySmall)
            if (evidence.incomplete) Text("These excerpts are bounded. Some sources or text could not be included.", style = MaterialTheme.typography.bodySmall)
            evidence.citations.forEach { citation ->
                HorizontalDivider()
                Text("${citation.ordinal}. ${citation.title}", style = MaterialTheme.typography.titleSmall)
                SelectionContainer { Text(citation.excerpt, style = MaterialTheme.typography.bodyMedium) }
                val provider = if (citation.providerId == "wikipedia-rest") "Wikipedia search" else "DuckDuckGo search"
                Text("$provider • retrieved ${java.time.Instant.ofEpochMilli(citation.source.capturedAtMs)}", style = MaterialTheme.typography.labelSmall)
                if (citation.providerId == "wikipedia-rest") Text("Wikipedia excerpts: CC BY-SA 4.0. This does not verify current news, prices or schedules.", style = MaterialTheme.typography.bodySmall)
                if (citation.source.bodyTruncated || citation.excerptTruncated) Text("A bounded part of the source was retained.", style = MaterialTheme.typography.bodySmall)
                SelectionContainer { Text(citation.source.finalUrl, style = MaterialTheme.typography.bodySmall) }
                if (citation.source.requestedUrl != citation.source.finalUrl) Text("Redirected from ${citation.source.requestedUrl}", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onOpenSource(citation.source.finalUrl) }) { Text("Open source") }
            }
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}
