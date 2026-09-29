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
    init{modelManager.refresh();viewModelScope.launch{if(dao.count()==0)dao.insert(OrezMessageEntity(role="OREZ",text="Namaste! Main OREZ hoon. Main local knowledge, reasoning, OCR/translation workflows aur current web information ko coordinate kar sakta hoon."))}}
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
                dao.insert(OrezMessageEntity(role="OREZ",text=answer.text))
            }catch(t:Throwable){
                if(t is kotlinx.coroutines.CancellationException) throw t
                dao.insert(OrezMessageEntity(role="OREZ",text="I hit a recoverable error: "+(t.message?:"unknown error")+". Please try again."))
            }finally{typing=false}
        }
    }
    fun ask(query:String,onRoute:(String,OrezRoute)->Unit)=sendMessage(query,onRoute)
    fun downloadLocalModel(){ modelManager.enqueue() }
    fun refreshLocalModel(){ modelManager.refresh() }
    override fun onCleared(){ brain.close(); super.onCleared() }
}

@Composable
fun OrezAiScreen(vm:OrezAiViewModel=viewModel(),onRoute:(String,OrezRoute)->Unit){
    val messages by vm.messages.collectAsState()
    var input by rememberSaveable{mutableStateOf("")}
    val list=rememberLazyListState()
    LaunchedEffect(messages.size){if(messages.isNotEmpty())list.animateScrollToItem(messages.lastIndex)}
    LaunchedEffect(Unit){ while(true){ vm.refreshLocalModel(); kotlinx.coroutines.delay(5000L) } }
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha=.25f),MaterialTheme.colorScheme.background)))){
        Column(Modifier.fillMaxSize().padding(16.dp)){
            Text("OREZ AI",style=MaterialTheme.typography.headlineLarge)
            Text("LOCAL BRAIN • KNOWLEDGE • REASONING • LIVE SEARCH",color=MaterialTheme.colorScheme.onSurfaceVariant)
            val modelState = vm.modelState.collectAsState().value
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                Text(if(modelState.installed) "LOCAL MODEL READY • 506 MB" else if(modelState.downloading) "DOWNLOADING LOCAL MODEL • " + (modelState.progress*100).toInt() + "%" else "LOCAL MODEL • 506 MB")
                if(!modelState.installed && !modelState.downloading) Button(onClick={vm.downloadLocalModel()}){Text("DOWNLOAD")}
            }
            LazyColumn(state=list,modifier=Modifier.weight(1f).fillMaxWidth().padding(vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                items(messages,key={it.id}){m->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=if(m.role=="YOU")Arrangement.End else Arrangement.Start){
                        Card(Modifier.widthIn(max=340.dp)){Column(Modifier.padding(14.dp)){Text(m.role,style=MaterialTheme.typography.labelSmall);Text(m.text,Modifier.padding(top=5.dp))}}
                    }
                }
                item{if(vm.typing)Text("OREZ is thinking…",color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                OutlinedTextField(value=input,onValueChange={input=it},modifier=Modifier.weight(1f),singleLine=true,placeholder={Text("Ask OREZ…")})
                Spacer(Modifier.width(8.dp))
                Button(onClick={val q=input;input="";vm.sendMessage(q,onRoute)},enabled=input.isNotBlank()&&!vm.typing){Text("SEND")}
            }
        }
    }
}
