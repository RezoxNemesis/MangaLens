package com.mangalens.ui.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mangalens.orez.OrezRoomDatabase
import com.mangalens.orez.agent.*
import com.mangalens.ui.video.SpeechCue
import com.mangalens.ui.video.SubtitleGenerationStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private data class SubtitleResultContent(val target: String, val mode: String, val cues: List<SpeechCue>)

/** Reopens exact owned managed exports. It never starts work or follows a page/model-selected URL. */
@Composable
fun OrezSubtitleResultScreen(reference: OrezSubtitleResultReference, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext
    var content by remember(reference) { mutableStateOf<SubtitleResultContent?>(null) }
    var loading by remember(reference) { mutableStateOf(true) }
    var unavailable by remember(reference) { mutableStateOf(false) }
    LaunchedEffect(reference) {
        try {
            content = withContext(Dispatchers.IO) {
                val plans = OrezTaskStore(OrezRoomDatabase.get(app).tasks())
                val plan = requireNotNull(plans.load(reference.planId))
                val step = OrezSubtitleResult.step(plan, reference)
                val expected = OrezSubtitlePlanScope.expected(plan, step)
                val owner = OrezDurablePlanRules.requestId(plan.id, step.index)
                val options = requireNotNull(plan.authorization?.subtitle)
                val observed = requireNotNull(OrezNativeSubtitleHost(app).observe(reference.taskId, owner, expected, options, reference.generation))
                OrezSubtitleResult.verify(plan, reference, observed)
                val native = requireNotNull(SubtitleGenerationStore.shared(app).exportVerified(reference.taskId, reference.generation))
                val finalReceipt = OrezSubtitleNativeEvidence.receipt(native, expected.sourceId, File(app.filesDir, "subtitle_jobs"), expected.descriptor)
                OrezSubtitleResult.verify(requireNotNull(plans.load(reference.planId)), reference, finalReceipt)
                SubtitleResultContent(options.targetLanguage, options.outputMode.name.lowercase(), native.cues.toList())
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { unavailable = true }
        finally { loading = false }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = onBack) { Text("Back") }
        Text("Saved subtitle result", style = MaterialTheme.typography.headlineSmall)
        when {
            loading -> { CircularProgressIndicator(); Text("Checking the saved subtitle track…") }
            unavailable || content == null -> Text("This saved subtitle track is no longer verified. Review the original Orez task or start a new request.")
            else -> content?.let { result ->
                val target = when (result.target) { "hi-latn" -> "Hinglish"; "hi" -> "Hindi"; "en" -> "English"; else -> result.target }
                Text("$target • ${result.mode} • ${result.cues.size} timed cues", style = MaterialTheme.typography.labelLarge)
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(result.cues) { cue ->
                        Column {
                            Text("${cue.startMs / 1000}s – ${cue.endMs / 1000}s", style = MaterialTheme.typography.labelSmall)
                            Text(cue.text)
                        }
                    }
                }
            }
        }
    }
}
