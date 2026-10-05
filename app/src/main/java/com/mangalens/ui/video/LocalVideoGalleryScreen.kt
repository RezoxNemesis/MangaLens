package com.mangalens.ui.video
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.PlayCircle
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
 Box(
  modifier.fillMaxSize().background(
   Brush.verticalGradient(
    listOf(
     MaterialTheme.colorScheme.primary.copy(alpha=.05f),
     MaterialTheme.colorScheme.background,
     MaterialTheme.colorScheme.background
    )
   )
  )
 ){
  LazyVerticalGrid(columns=GridCells.Adaptive(170.dp),modifier=Modifier.fillMaxSize().padding(14.dp),contentPadding=PaddingValues(bottom=96.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
   item(span={GridItemSpan(maxLineSpan)}){
    Column(verticalArrangement=Arrangement.spacedBy(10.dp)){
     com.mangalens.ui.components.BrandHeader("Video Vault","LOCAL MEDIA • MANGALENS 2.0"){
      FilledTonalButton(onClick=onOpenSystem,shape=RoundedCornerShape(14.dp)){Text("Picker")}
     }
     Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
      com.mangalens.ui.components.NeonStatusPill("${videos.size} videos",positive=videos.isNotEmpty())
      com.mangalens.ui.components.NeonStatusPill("Device + SD")
     }
    }
   }
   items(videos,key={it.id}){video->
    Card(
     Modifier.fillMaxWidth().clickable{onOpenPlayer(video.uri)},
     shape=RoundedCornerShape(22.dp),
     colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surface.copy(alpha=.94f)),
     border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outline.copy(alpha=.55f))
    ){
     Column{
      Box(Modifier.fillMaxWidth().height(120.dp)){
       AsyncImage(video.uri,video.name,Modifier.fillMaxSize().clip(RoundedCornerShape(topStart=24.dp,topEnd=24.dp)),contentScale=ContentScale.Crop)
       Text(formatDuration(video.durationMs),Modifier.align(Alignment.BottomStart).padding(8.dp).background(Brush.horizontalGradient(listOf(MaterialTheme.colorScheme.surface.copy(alpha=.9f),MaterialTheme.colorScheme.surface.copy(alpha=.4f))),RoundedCornerShape(10.dp)).padding(7.dp),style=MaterialTheme.typography.labelSmall)
      }
      Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
       Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(7.dp)){
        Icon(Icons.Outlined.PlayCircle,null,tint=MaterialTheme.colorScheme.secondary,modifier=Modifier.size(20.dp))
        Text(video.name,maxLines=2,style=MaterialTheme.typography.titleSmall,modifier=Modifier.weight(1f))
       }
       Row(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically){
        AssistChip(onClick={},enabled=false,label={Text(codecTag(video.mimeType))})
        Text(formatSize(video.sizeBytes),style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
       }
       TextButton(onClick={onOpenExternal(video.uri)}){Text("Open externally")}
      }
     }
    }
   }
  }
  if(videos.isEmpty())com.mangalens.ui.components.Panel(Modifier.align(Alignment.Center).padding(24.dp).widthIn(max=380.dp)){
   Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
    Icon(Icons.Outlined.FolderOpen,null,tint=MaterialTheme.colorScheme.secondary,modifier=Modifier.size(38.dp))
    Column{
     Text("No indexed videos",style=MaterialTheme.typography.titleLarge,fontWeight=androidx.compose.ui.text.font.FontWeight.Bold)
     Text("Open a local file or let the media catalog populate.",color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
   }
   Button(onClick=onOpenSystem,shape=RoundedCornerShape(14.dp)){Text("Choose video")}
  }
 }
}
private fun formatDuration(ms:Long):String{val t=(ms/1000).coerceAtLeast(0);return "%02d:%02d".format(t/60,t%60)}
private fun formatSize(b:Long):String=if(b<1024L*1024L)"${(b/1024.0).roundToInt()} KB" else "${"%.1f".format(b/(1024.0*1024.0))} MB"
private fun codecTag(m:String)=when{m.contains("hevc",true)->"HEVC";m.contains("av01",true)->"AV1";m.contains("vp9",true)->"VP9";m.contains("webm",true)->"WEBM";m.contains("quicktime",true)->"MOV";m.contains("matroska",true)->"MKV";else->m.substringAfter('/').uppercase().take(8).ifBlank{"VIDEO"}}
