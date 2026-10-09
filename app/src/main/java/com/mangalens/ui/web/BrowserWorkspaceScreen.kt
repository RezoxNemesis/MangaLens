package com.mangalens.ui.web

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mangalens.ui.video.SniffedMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/** Host URL requests are applied separately from the active tab's own navigation publications. */
@Composable
internal fun BrowserWorkspaceScreen(
    session: BrowserWorkspaceSession,
    url: String,
    translationEnabled: Boolean,
    adBlockEnabled: Boolean = true,
    modifier: Modifier = Modifier,
    targetLanguage: String = "hi",
    onOpenManga: (String) -> Unit = {},
    onOpenVideo: (SniffedMedia, String) -> Unit = { _, _ -> },
    onClose: (() -> Unit)? = null,
    onPageChanged: (String) -> Unit = {}
) {
    val snapshot by session.state.collectAsState()
    val storeError by session.error.collectAsState()
    var initialized by remember(session) { mutableStateOf(false) }
    var applyingIncoming by remember(session) { mutableStateOf(false) }
    var incomingRevision by remember(session) { mutableLongStateOf(0) }
    var incomingError by remember(session) { mutableStateOf<String?>(null) }
    LaunchedEffect(session, url) {
        val current = session.state.filterNotNull().first()
        val decision = planBrowserIncoming(current, url, initial = !initialized)
        try {
            when (decision.action) {
                BrowserIncomingAction.IGNORE -> Unit
                BrowserIncomingAction.REJECT -> incomingError = "Enter a valid HTTP or HTTPS address without account credentials."
                else -> {
                    applyingIncoming = true // Revoke the old view/chooser/capture before accepting a different host source.
                    session.submit { store ->
                        val latest = planBrowserIncoming(store.snapshot(), url, initial = !initialized)
                        when (latest.action) {
                            BrowserIncomingAction.SELECT -> store.selectTab(latest.tabId!!)
                            BrowserIncomingAction.NAVIGATE -> store.navigate(latest.tabId!!, latest.url!!)
                            else -> store.snapshot()
                        }
                    }.await()
                    incomingError = null
                    incomingRevision++
                }
            }
            initialized = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { initialized = true }
        finally { applyingIncoming = false }
    }
    val ready = snapshot
    if (ready == null || !initialized || applyingIncoming) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (storeError == null) CircularProgressIndicator()
                Text(storeError ?: "Restoring browser workspace…")
                if (storeError != null) TextButton(onClick = { session.submit { it.snapshot() } }) { Text("Retry") }
            }
        }
        return
    }
    key(session, ready.activeTabId, incomingRevision) {
        GuardedBrowserTabScreen(
            url = ready.activeTab.url, tab = ready.activeTab, workspace = ready, session = session,
            workspaceError = incomingError ?: storeError ?: ready.notice,
            translationEnabled = translationEnabled, adBlockEnabled = adBlockEnabled, modifier = modifier,
            targetLanguage = targetLanguage, onOpenManga = onOpenManga, onOpenVideo = onOpenVideo,
            onClose = onClose, onPageChanged = onPageChanged
        )
    }
}
