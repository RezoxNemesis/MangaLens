package com.mangalens.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mangalens.engine.OrezLiveSearchConnector
import com.mangalens.orez.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val prompts=listOf("Explain auto-scroll","How does Hinglish translation work?","Why is my video not playing?","Create a study plan","What is latest online?")

class OrezAiViewModel(app:android.app.Application):AndroidViewModel(app){
    private val db=OrezRoomDatabase.get(app)
    private val dao=db.messages()
    private val brain=OrezBrain(db,app){q->OrezLiveSearchConnector().search(q,6)}
    private val modelManager=OrezModelManager(app)
    val modelState get() = modelManager.state
    val messages=dao.observe().stateIn(viewModelScope,SharingStarted.Eagerly,emptyList())
    var typing by mutableStateOf(false);private set
    init{modelManager.refresh();viewModelScope.launch { androidx.work.WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow("orez-model").collect { modelManager.refresh() } };viewModelScope.launch{if(dao.count()==0)dao.insert(OrezMessageEntity(role="OREZ",text="Namaste! Main OREZ hoon. Main local knowledge, reasoning, OCR/translation workflows aur current web information ko coordinate kar sakta hoon."))}}
    fun sendMessage(query:String,onRoute:(String,OrezRoute)->Unit={_,_->}){
        val input=query.trim();if(input.isBlank()||typing)return
        typing=true
        viewModelScope.launch{
            try{
                dao.insert(OrezMessageEntity(role="YOU",text=input))
                val command=OrezCommandRouter().route(input)
                if(command.route!=OrezRoute.CHAT){
                    dao.insert(OrezMessageEntity(role="OREZ",text="Opening "+command.route.name.lowercase().replace('_',' ')+" flow."))
                    onRoute(command.originalInput,command.route);return@launch
                }
                val recent=messages.value.takeLast(12)
                val targetLanguage = getApplication<android.app.Application>()
                    .getSharedPreferences("mangalens_preferences", android.content.Context.MODE_PRIVATE)
                    .getString("translation_target", "hi") ?: "hi"
                val answer=brain.answer(input,OrezContext(recentMessages=recent,targetLanguage=targetLanguage))
                val sources = answer.sources.distinct().filter(com.mangalens.core.router.UrlEngineRouter::isSafeWebUrl).take(6)
                dao.insert(OrezMessageEntity(role="OREZ",text=answer.text + if (sources.isEmpty()) "" else "\n\nSources:\n" + sources.joinToString("\n")))
            }catch(t:Throwable){
                if(t is kotlinx.coroutines.CancellationException) throw t
                dao.insert(OrezMessageEntity(role="OREZ",text="I hit a recoverable error: "+(t.message?:"unknown error")+". Please try again."))
            }finally{typing=false; runCatching { dao.trimHistory() }}
        }
    }
    fun ask(query:String,onRoute:(String,OrezRoute)->Unit)=sendMessage(query,onRoute)
    fun downloadLocalModel(){ modelManager.enqueue() }
    fun refreshLocalModel(){ modelManager.refresh() }
    override fun onCleared(){ brain.close(); super.onCleared() }
}

@Composable
fun OrezAiScreen(vm: OrezAiViewModel = viewModel(), library: List<com.mangalens.core.reader.SavedChapter> = emptyList(), chapterText: String = "", onImport: (List<android.net.Uri>) -> Unit = {}, onRoute: (String, OrezRoute) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val messages by vm.messages.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) onImport(it) }
    LaunchedEffect(messages.size) { if (messages.size > 1) list.animateScrollToItem(messages.size + 1) }
    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(horizontal = 16.dp)) {
        com.mangalens.ui.components.BrandHeader("OREZ AI", "YOUR READING COMPANION")
        LazyColumn(state = list, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            item {
                com.mangalens.ui.components.ArtworkHero("Hello, I’m Orez", "Let’s explore your stories together.", com.mangalens.R.drawable.orez_portrait, "Translate a chapter") { picker.launch(com.mangalens.core.reader.DocumentImporter.MIME_TYPES) }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Quick actions", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton({ input = "Find manga: " }, Modifier.weight(1f)) { Text("Find manga") }
                        OutlinedButton({ input = if (chapterText.isBlank()) "How do I import and translate a chapter?" else "Summarize this chapter text. Do not infer missing pages.\n" + chapterText.take(6000) }, Modifier.weight(1f)) { Text("Summarize") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton({ input = "Help me plan my reading list from these saved chapters only: " + library.joinToString { it.title }.take(6000) }, Modifier.weight(1f)) { Text("Reading list") }
                        OutlinedButton({ input = "Explain how on-device OCR translation works" }, Modifier.weight(1f)) { Text("Translation help") }
                    }
                    val modelState by vm.modelState.collectAsState()
                    com.mangalens.ui.components.Panel {
                        val size = com.mangalens.orez.OrezModelManager.MODEL_BYTES / 1_000_000L
                        Text(if (modelState.installed) "Local model ready · $size MB" else if (modelState.downloading) "Downloading model · ${(modelState.progress * 100).toInt()}%" else "Optional offline model · $size MB", style = MaterialTheme.typography.bodySmall)
                        if (modelState.downloading) LinearProgressIndicator(progress = { modelState.progress }, modifier = Modifier.fillMaxWidth())
                        if (!modelState.installed && !modelState.downloading) TextButton({ vm.downloadLocalModel() }) { Text("Download local model →") }
                    }
                }
            }
            items(messages, key = { it.id }) { message ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.role == "YOU") Arrangement.End else Arrangement.Start) {
                    Card(Modifier.widthIn(max = 340.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = if (message.role == "YOU") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text(message.role, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                            Text(message.text.substringBefore("\n\nSources:"))
                            if (message.text.contains("\n\nSources:")) message.text.substringAfter("\n\nSources:").lineSequence().map { it.trim() }.filter(com.mangalens.core.router.UrlEngineRouter::isSafeWebUrl).take(6).forEach { url ->
                                TextButton({ runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) } }) { Text(java.net.URI(url).host ?: "Source", maxLines = 1) }
                            }
                        }
                    }
                }
            }
            item { if (vm.typing) Text("Orez is thinking…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, { input = it }, Modifier.weight(1f), maxLines = 4, placeholder = { Text("Ask Orez anything…") }, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            Button({ val query = input; input = ""; vm.sendMessage(query, onRoute) }, enabled = input.isNotBlank() && !vm.typing, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Send") }
        }
    }
}
