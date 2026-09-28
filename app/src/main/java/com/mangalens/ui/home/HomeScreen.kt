package com.mangalens.ui.home

import android.content.ClipDescription
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mangalens.core.model.ContentType
import com.mangalens.ui.MangaLensUiState

@Composable
fun HomeScreen(state:MangaLensUiState,onUrlChanged:(String)->Unit,onPaste:()->Unit,onModeSelected:(ContentType)->Unit,onIngest:()->Unit,onOpenReader:()->Unit,onOpenVideo:()->Unit,onOpenDownloads:()->Unit,onOpenChapter:(String)->Unit){
    val context=LocalContext.current
    fun safePaste(){
        runCatching{
            val clipboard=context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@runCatching
            val clip=clipboard.primaryClip ?: return@runCatching
            if(clip.itemCount>0 && (clip.description?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)==true || clip.description?.hasMimeType(ClipDescription.MIMETYPE_TEXT_HTML)==true)){
                val text=clip.getItemAt(0).coerceToText(context).toString().trim()
                if(text.isNotBlank()) onUrlChanged(text)
            }
        }
    }
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal=20.dp,vertical=16.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
            Column{Text("MANGALENS",color=MaterialTheme.colorScheme.primary,fontWeight=FontWeight.Black,style=MaterialTheme.typography.headlineSmall);Text("Universal media translation hub",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)}
            AssistChip(onClick={},label={Text("AD-BLOCK ON")})
        }
        OutlinedTextField(value=state.url,onValueChange=onUrlChanged,modifier=Modifier.fillMaxWidth(),singleLine=true,placeholder={Text("Paste a chapter, video or web URL")},trailingIcon={Button(onClick={safePaste();onPaste()}){Text("PASTE")}})
        Text("OPEN AS",fontWeight=FontWeight.Bold)
        LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp)){items(listOf(ContentType.IMAGE_CHAPTER to "Manga",ContentType.VIDEO_STREAM to "Video",ContentType.GENERIC_WEB to "Web")){(mode,label)->FilterChip(selected=state.mode==mode,onClick={onModeSelected(mode)},label={Text(label)})}}
        Button(onClick=onIngest,enabled=state.url.isNotBlank()&&!state.loading,modifier=Modifier.fillMaxWidth()){if(state.loading)CircularProgressIndicator(modifier=Modifier.height(18.dp))else Text("OPEN")}
        OutlinedButton(onClick=onOpenDownloads,modifier=Modifier.fillMaxWidth()){Text("UNIVERSAL DOWNLOADER • 480P → 4K")}
        if(state.chapters.isNotEmpty()){Text("CHAPTERS",fontWeight=FontWeight.Bold);state.chapters.take(20).forEach{chapter->ElevatedCard(onClick={onOpenChapter(chapter.url)},modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){Text(chapter.title.ifBlank{"Chapter"});Text(chapter.url,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=1)}}}}
        Text("RECENT",fontWeight=FontWeight.Bold)
        if(state.pages.isNotEmpty()){ElevatedCard(onClick=onOpenReader,modifier=Modifier.fillMaxWidth()){Column(Modifier.padding(16.dp)){Text("Chapter reader");Text(state.pages.size.toString()+" pages available",color=MaterialTheme.colorScheme.onSurfaceVariant)}}}
        state.videoUrl?.let{Card(onClick=onOpenVideo,modifier=Modifier.fillMaxWidth()){Text("Video stream",modifier=Modifier.padding(16.dp))}}
        state.error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        Spacer(Modifier.height(8.dp))
    }
}