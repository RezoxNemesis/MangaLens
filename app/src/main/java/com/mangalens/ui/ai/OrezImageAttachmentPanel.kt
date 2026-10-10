package com.mangalens.ui.ai

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.mangalens.orez.OrezImageCropRequest

@Composable
internal fun OrezImageAttachmentPanel(controller:OrezImageAttachmentController,state:OrezImageAttachmentState,onPick:()->Unit) {
    var left by remember { mutableStateOf("0") };var top by remember { mutableStateOf("0") }
    var right by remember { mutableStateOf("100") };var bottom by remember { mutableStateOf("100") }
    var script by remember { mutableStateOf("AUTO") }
    var showScripts by remember { mutableStateOf(false) };var error by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("Ask about image text",style=MaterialTheme.typography.titleMedium)
            Text("Choose a local image or screenshot. Orez reads a bounded region with OCR; this does not recognize image objects or artwork.",style=MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick=onPick) { Text(if(state.hasSelection) "Choose another image" else "Choose image") }
                if(state.hasSelection) TextButton(onClick=controller::remove) { Text("Remove attachment") }
            }
            if(state.hasSelection) {
                Text("Region in original-image percent",style=MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    fun edit(value:String,set:(String)->Unit) { if(value.length<=3 && value.all(Char::isDigit)) { controller.regionChanged();set(value);error=null } }
                    OutlinedTextField(left,{edit(it){left=it}},label={Text("Left")},singleLine=true,modifier=Modifier.weight(1f))
                    OutlinedTextField(top,{edit(it){top=it}},label={Text("Top")},singleLine=true,modifier=Modifier.weight(1f))
                    OutlinedTextField(right,{edit(it){right=it}},label={Text("Right")},singleLine=true,modifier=Modifier.weight(1f))
                    OutlinedTextField(bottom,{edit(it){bottom=it}},label={Text("Bottom")},singleLine=true,modifier=Modifier.weight(1f))
                }
                Box {
                    TextButton(onClick={showScripts=true}) { Text("OCR script: $script") }
                    DropdownMenu(showScripts,{showScripts=false}) {
                        listOf("AUTO","LATIN","DEVANAGARI","CHINESE","JAPANESE","KOREAN").forEach { option ->
                            DropdownMenuItem(text={Text(option)},onClick={if(option!=script) controller.regionChanged();script=option;showScripts=false})
                        }
                    }
                }
                Row {
                    TextButton(onClick={
                        val crop=runCatching { OrezImageCropRequest(left.toInt(),top.toInt(),right.toInt(),bottom.toInt()).validated() }.getOrNull()
                        if(crop==null) error="Use 0–100 bounds with Right greater than Left and Bottom greater than Top."
                        else { error=null;controller.read(crop,script) }
                    },enabled=!state.busy) { Text(if(state.attachment==null) "Read region locally" else "Retry OCR") }
                    if(state.busy) TextButton(onClick=controller::cancelRead) { Text("Cancel OCR") }
                }
            }
            if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.status?.let { Text(it,style=MaterialTheme.typography.bodySmall) }
            error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            state.preview?.let { ownership -> key(ownership) {
                val preview=remember(ownership) { ownership.claim() }
                DisposableEffect(ownership) { onDispose { ownership.disposeUi() } }
                preview?.let { Image(it.bitmap.asImageBitmap(),"Explicit selected image region preview",Modifier.fillMaxWidth().heightIn(max=180.dp)) }
            } }
            state.attachment?.let { source ->
                if(state.readings.size>1) Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                    state.readings.forEach { reading -> TextButton(onClick={controller.chooseReading(reading)},enabled=reading!=source) { Text(reading.recognizerScript) } }
                }
                val r=source.region
                Text("Original source ${source.imageWidth} × ${source.imageHeight}; region ${r.left},${r.top}–${r.right},${r.bottom}; ${source.recognizerScript}",style=MaterialTheme.typography.labelSmall)
                SelectionContainer { Text("SHA-256: ${source.sourceSha256}",style=MaterialTheme.typography.labelSmall) }
                Text("Original extracted OCR",style=MaterialTheme.typography.labelLarge)
                SelectionContainer { Text(source.originalOcr) }
                if(source.truncated) Text("This is a bounded OCR excerpt, not the complete image text.",style=MaterialTheme.typography.bodySmall)
                Text("Type a question and press Ask about image text. Remove the attachment before sending app actions or research.",style=MaterialTheme.typography.bodySmall)
            }
        }
    }
}
