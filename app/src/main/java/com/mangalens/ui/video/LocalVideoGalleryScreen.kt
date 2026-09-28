package com.mangalens.ui.video
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlin.math.roundToInt

@Composable
fun LocalVideoGalleryScreen(onOpenPlayer:(Uri)->Unit,onOpenSystem:()->Unit,onOpenExternal:(Uri)->Unit,modifier:Modifier=Modifier){
 val context=LocalContext.current
 var videos by remember{mutableStateOf(emptyList<LocalVideoItem>())}
 LaunchedEffect(Unit){videos=LocalVideoCatalog(context).scan()}
 Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)){
  LazyVerticalGrid(columns=GridCells.Adaptive(170.dp),modifier=Modifier.fillMaxSize().padding(14.dp),contentPadding=PaddingValues(bottom=96.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   item(span={GridItemSpan(maxLineSpan)}){
    Column{Text("Video Vault",style=MaterialTheme.typography.headlineMedium);Text("${videos.size} local videos • device + SD media",color=MaterialTheme.colorScheme.onSurfaceVariant);Spacer(Modifier.height(10.dp));FilledTonalButton(onClick=onOpenSystem){Text("System picker")}}
   }
   items(videos,key={it.id}){video->
    Card(Modifier.fillMaxWidth().clickable{onOpenPlayer(video.uri)},shape=RoundedCornerShape(24.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.72f))){
     Column{
      Box(Modifier.fillMaxWidth().height(120.dp)){
       AsyncImage(video.uri,video.name,Modifier.fillMaxSize().clip(RoundedCornerShape(topStart=24.dp,topEnd=24.dp)),contentScale=ContentScale.Crop)
       Text(formatDuration(video.durationMs),Modifier.align(Alignment.BottomStart).padding(8.dp).background(Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.surface.copy(alpha=.9f),MaterialTheme.colorScheme.surface.copy(alpha=.4f))),RoundedCornerShape(10.dp)).padding(7.dp),style=MaterialTheme.typography.labelSmall)
      }
      Column(Modifier.padding(12.dp)){Text(video.name,maxLines=2,style=MaterialTheme.typography.titleSmall);Row(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically){AssistChip(onClick={},enabled=false,label={Text(codecTag(video.mimeType))});Text(formatSize(video.sizeBytes),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};TextButton(onClick={onOpenExternal(video.uri)}){Text("Open externally")}}
     }
    }
   }
  }
  if(videos.isEmpty())Card(Modifier.align(Alignment.Center).padding(24.dp),shape=RoundedCornerShape(28.dp)){Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally){Text("No indexed videos",style=MaterialTheme.typography.titleLarge);Text("Use the system picker to open a file.");Spacer(Modifier.height(12.dp));Button(onClick=onOpenSystem){Text("Choose video")}}}
 }
}
private fun formatDuration(ms:Long):String{val t=(ms/1000).coerceAtLeast(0);return "%02d:%02d".format(t/60,t%60)}
private fun formatSize(b:Long):String=if(b<1024L*1024L)"${(b/1024.0).roundToInt()} KB" else "${"%.1f".format(b/(1024.0*1024.0))} MB"
private fun codecTag(m:String)=when{m.contains("hevc",true)->"HEVC";m.contains("av01",true)->"AV1";m.contains("vp9",true)->"VP9";m.contains("webm",true)->"WEBM";m.contains("quicktime",true)->"MOV";m.contains("matroska",true)->"MKV";else->m.substringAfter('/').uppercase().take(8).ifBlank{"VIDEO"}}
