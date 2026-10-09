package com.mangalens.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeCustomizationSheet(initial: HomeLayout, preferences: HomeLayoutPreferences, onDismiss: () -> Unit) {
    var draft by remember { mutableStateOf(HomeLayoutPolicy.normalize(initial)) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val screenHeight = LocalConfiguration.current.screenHeightDp
    ModalBottomSheet(sheetState = sheetState, onDismissRequest = { if (!saving) onDismiss() }) {
        Column(Modifier.fillMaxWidth().heightIn(max = screenHeight.dp).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Customize Home", style = MaterialTheme.typography.titleLarge)
            Text("Choose sections and their order. Sections with content appear when available.", style = MaterialTheme.typography.bodySmall)
            // There are only nine real sections. Keep their keyed controls composed
            // while reordering, and reserve space for the always-visible save footer.
            Column(Modifier.fillMaxWidth().weight(1f, fill = false)
                .heightIn(max = minOf(400, screenHeight / 2).dp).verticalScroll(rememberScrollState())) {
                draft.order.forEachIndexed { index, module -> key(module.id) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(module !in draft.hidden, { visible -> draft = HomeLayoutPolicy.setVisible(draft, module, visible) },
                            enabled = !saving, modifier = Modifier.semantics { contentDescription = "Show ${module.label} on Home" })
                        Text(module.label, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        IconButton({ draft = HomeLayoutPolicy.move(draft, module, -1) }, enabled = !saving && index > 0,
                            modifier = Modifier.semantics { contentDescription = "Move ${module.label} up" }) {
                            Icon(Icons.Outlined.KeyboardArrowUp, null)
                        }
                        IconButton({ draft = HomeLayoutPolicy.move(draft, module, 1) }, enabled = !saving && index < draft.order.lastIndex,
                            modifier = Modifier.semantics { contentDescription = "Move ${module.label} down" }) {
                            Icon(Icons.Outlined.KeyboardArrowDown, null)
                        }
                    }
                } }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ draft = HomeLayoutPolicy.reset(); error = null }, enabled = !saving) { Text("Reset") }
                Row {
                    TextButton(onDismiss, enabled = !saving) { Text("Cancel") }
                    Button({
                        saving = true
                        scope.launch {
                            if (preferences.save(draft)) onDismiss()
                            else { error = "Home sections could not be saved. Try again."; saving = false }
                        }
                    }, enabled = !saving) { Text(if (saving) "Saving…" else "Save") }
                }
            }
        }
    }
}
