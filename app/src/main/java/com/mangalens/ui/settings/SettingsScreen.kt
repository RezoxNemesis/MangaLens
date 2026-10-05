package com.mangalens.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mangalens.orez.OrezResourcePackManager
import com.mangalens.core.translation.TranslationStyleProfile
import com.mangalens.ui.MangaLensUiState
import com.mangalens.ui.theme.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(state:MangaLensUiState,onThemeModeChanged:(ThemeMode)->Unit,onMangaTranslationChanged:(Boolean)->Unit,onVideoTranslationChanged:(Boolean)->Unit,onWebTranslationChanged:(Boolean)->Unit,onTranslationStyleChanged:(String)->Unit,onCustomTranslationStyleChanged:(String)->Unit,onAdBlockEnabledChanged:(Boolean)->Unit,onResetAdBlockStats:()->Unit){
    val context=LocalContext.current
    val resourceManager=remember{OrezResourcePackManager(context)}
    val resourceState by resourceManager.state.collectAsState()
    var packSelectionError by remember { mutableStateOf<String?>(null) }
    val localPackLauncher=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->
        if(uri!=null){
            packSelectionError=null
            runCatching{context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
            runCatching{resourceManager.enqueueUri(uri)}
                .onFailure{packSelectionError=it.message ?: "Unable to queue this data pack."}
        }
    }
    var advancedImport by rememberSaveable { mutableStateOf(false) }
    var showBlockEvents by remember { mutableStateOf(false) }
    var resourceUrl by remember{mutableStateOf("")}
    LaunchedEffect(resourceManager){
        androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(OrezResourcePackManager.TAG).collect {
            resourceManager.refresh()
        }
    }
    var storage by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var cacheBytes by remember { mutableLongStateOf(0L) }
    var adaptiveMediaBytes by remember { mutableLongStateOf(0L) }
    var clearCache by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val disk = android.os.StatFs(context.filesDir.absolutePath)
            storage = disk.availableBytes to disk.totalBytes
            cacheBytes = context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            adaptiveMediaBytes = java.io.File(context.filesDir, "media_download_cache")
                .takeIf { it.exists() }
                ?.walkTopDown()
                ?.filter { it.isFile }
                ?.sumOf { it.length() } ?: 0L
        }
    }
    if (clearCache) AlertDialog(onDismissRequest = { clearCache = false }, title = { Text("Clear temporary cache?") }, text = { Text("Saved chapters, downloads, models and reading progress are retained. Temporary files can be recreated.") },
        confirmButton = { TextButton({ clearCache = false; scope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // Imported documents can be active; leave their working directory untouched.
                context.cacheDir.listFiles().orEmpty().filterNot { it.name == "chapter_imports" }.forEach { it.deleteRecursively() }
                cacheBytes = context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            }
        } }) { Text("Clear cache") } }, dismissButton = { TextButton({ clearCache = false }) { Text("Cancel") } })
    LazyColumn(Modifier.fillMaxWidth().statusBarsPadding().imePadding().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
        item{
            com.mangalens.ui.components.BrandHeader("MangaLens 2.1", "TOOLS • AI • PROTECTION")
            Text("Appearance, translation modules, OREZ data and network protection.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Text("Translation modules", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Triple("Manga", state.mangaTranslationEnabled, onMangaTranslationChanged), Triple("Video", state.videoTranslationEnabled, onVideoTranslationChanged), Triple("Web", state.webTranslationEnabled, onWebTranslationChanged)).forEach { (name, enabled, change) ->
                    com.mangalens.ui.components.Panel(Modifier.weight(1f)) { Text(name, fontWeight = FontWeight.Bold); Switch(enabled, change) }
                }
            }
        }
        item {
            com.mangalens.ui.components.Panel(Modifier.fillMaxWidth()) {
                Text("Device storage", style = MaterialTheme.typography.titleMedium)
                storage?.let { (available, total) ->
                    Text(formatBytes(available) + " free of " + formatBytes(total))
                    LinearProgressIndicator(progress = { 1f - available.toFloat() / total.coerceAtLeast(1) }, modifier = Modifier.fillMaxWidth())
                }
                Text("Temporary cache: " + formatBytes(cacheBytes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Adaptive offline media: " + formatBytes(adaptiveMediaBytes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton({ clearCache = true }) { Text("Clear cache →") }
            }
        }
        item{Text("Appearance",fontWeight=FontWeight.Bold)}
        item{Card(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){ThemeMode.entries.forEach{mode->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(mode.name.lowercase().replaceFirstChar{it.uppercase()});RadioButton(selected=state.themeMode==mode,onClick={onThemeModeChanged(mode)})}}}}}
        item{
            Text("Protection Center",fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.secondary)
            com.mangalens.ui.components.Panel(Modifier.fillMaxWidth()){
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                    Column(Modifier.weight(1f)){
                        Text("Block ads & trackers",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.ExtraBold)
                        Text("Network, popup, tracker and known ad transport protection. Normal first-party media remains allowed.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                    }
                    Switch(state.adBlockEnabled,onAdBlockEnabledChanged)
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                    Surface(
                        Modifier.weight(1f),
                        shape=RoundedCornerShape(16.dp),
                        color=MaterialTheme.colorScheme.primary.copy(alpha=.08f),
                        border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.primary.copy(alpha=.35f))
                    ){
                        Column(Modifier.padding(14.dp)){
                            Text(state.adBlockStats.blockedRequests.toString(),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black)
                            Text("requests blocked",color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Surface(
                        Modifier.weight(1f),
                        shape=RoundedCornerShape(16.dp),
                        color=MaterialTheme.colorScheme.primary.copy(alpha=.06f),
                        border=androidx.compose.foundation.BorderStroke(1.dp,MaterialTheme.colorScheme.outline.copy(alpha=.55f))
                    ){
                        Column(Modifier.padding(14.dp)){
                            Text((state.adBlockStats.knownBytesSaved/1024).toString()+" KB",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Black)
                            Text("known bytes saved",color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Row {
                    TextButton({ showBlockEvents = !showBlockEvents }) { Text(if (showBlockEvents) "Hide activity" else "Inspect blocked requests") }
                    TextButton(onClick=onResetAdBlockStats){Text("Reset")}
                }
                if (showBlockEvents) {
                    Text("Local log • latest 200 blocked requests • no cookies or URL queries", style = MaterialTheme.typography.bodySmall)
                    state.adBlockStats.events.take(20).forEach { event ->
                        Surface(
                            Modifier.fillMaxWidth(),
                            shape=RoundedCornerShape(12.dp),
                            color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.34f)
                        ){
                            Column(Modifier.padding(horizontal=12.dp,vertical=9.dp)){
                                Text(java.text.SimpleDateFormat("hh:mm:ss a", java.util.Locale.getDefault()).format(java.util.Date(event.timestamp)) + " · " + event.host, style = MaterialTheme.typography.labelLarge,fontWeight=FontWeight.SemiBold)
                                Text("${event.type} · ${event.rule} · ${if (event.thirdParty) "third party" else "same site / unknown"} · ${event.pageHost}", style = MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        item{
            Text("OCR Engine",fontWeight=FontWeight.Bold)
            val ocrPrefs=context.getSharedPreferences("mangalens_ocr",android.content.Context.MODE_PRIVATE)
            var script by remember{mutableStateOf(ocrPrefs.getString("script","AUTO")?:"AUTO")}
            var highAccuracy by remember{mutableStateOf(ocrPrefs.getBoolean("high_accuracy",true))}
            var preserveStyle by remember{mutableStateOf(ocrPrefs.getBoolean("preserve_style",true))}
            Card(Modifier.fillMaxWidth()){
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
                    Text("Google ML Kit script routing",style=MaterialTheme.typography.titleMedium)
                    Text("AUTO can evaluate Latin, Devanagari, Chinese, Japanese and Korean recognizers. High accuracy runs the available recognizers and selects the strongest result.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                        listOf("AUTO","LATIN","DEVANAGARI","CHINESE","JAPANESE","KOREAN").forEach{value->
                            FilterChip(selected=script==value,onClick={script=value;ocrPrefs.edit().putString("script",value).apply()},label={Text(value)})
                        }
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("High accuracy");Switch(highAccuracy,{highAccuracy=it;ocrPrefs.edit().putBoolean("high_accuracy",it).apply()})}
                    var refinement by remember { mutableStateOf(ocrPrefs.getBoolean("local_refinement", false)) }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("Local dialogue refinement")
                            Text("Optional slower editing; each bubble has an 8-second fallback.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(refinement, { refinement = it; ocrPrefs.edit().putBoolean("local_refinement", it).apply() })
                    }
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("Preserve visual style");Switch(preserveStyle,{preserveStyle=it;ocrPrefs.edit().putBoolean("preserve_style",it).apply()})}
                }
            }
        }
        item{
            Text("Translation Style",fontWeight=FontWeight.Bold)
            Card(Modifier.fillMaxWidth()){
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    Text("Choose how OREZ should localize dialogue across the chapter.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                        listOf(
                            TranslationStyleProfile.NATURAL,
                            TranslationStyleProfile.FAITHFUL,
                            TranslationStyleProfile.CASUAL,
                            TranslationStyleProfile.FORMAL,
                            TranslationStyleProfile.WEBTOON
                        ).forEach { style ->
                            FilterChip(
                                selected=state.translationStyle==style.id,
                                onClick={onTranslationStyleChanged(style.id)},
                                label={Text(style.name)}
                            )
                        }
                    }
                    FilterChip(
                        selected=state.translationStyle=="custom",
                        onClick={onTranslationStyleChanged("custom")},
                        label={Text("Custom")}
                    )
                    OutlinedTextField(
                        value=state.customTranslationStyle,
                        onValueChange=onCustomTranslationStyleChanged,
                        modifier=Modifier.fillMaxWidth(),
                        minLines=2,
                        maxLines=4,
                        label={Text("Your translation style instructions")},
                        placeholder={Text("Example: natural Hindi, keep honorifics, preserve sarcasm and character personality")}
                    )
                }
            }
        }
        item { TextButton({ advancedImport = !advancedImport }) { Text(if (advancedImport) "Hide advanced data import" else "Advanced / Data import →") } }
        if (advancedImport) item{
            Text("OREZ Conversation Master Pack",fontWeight=FontWeight.Bold)
            Card(Modifier.fillMaxWidth()){
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                    Text("Import your conversation and reasoning data",style=MaterialTheme.typography.titleLarge)
                    Text("Select a .jsonl, .jsonl.gz file, or a downloaded GitHub artifact ZIP from Files. The app streams the selected file directly (no second full-size copy), reads compressed data, and imports a bounded batch into the private OREZ vault.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(enabled=!resourceState.active,onClick={localPackLauncher.launch(arrayOf("*/*"))}){Text(if(resourceState.active)"IMPORTING…" else "SELECT OREZ DATA PACK FROM FILES")}
                    packSelectionError?.let{Text("Pack selection error: "+it,color=MaterialTheme.colorScheme.error)}
                    OutlinedTextField(value=resourceUrl,onValueChange={resourceUrl=it},modifier=Modifier.fillMaxWidth(),singleLine=true,label={Text("HTTPS conversation pack URL")})
                    Button(enabled=!resourceState.active&&(resourceUrl.startsWith("http://")||resourceUrl.startsWith("https://")),onClick={runCatching{resourceManager.enqueue(resourceUrl);resourceUrl=""}}){Text(if(resourceState.active)"SYNCING…" else "SYNC REMOTE PACK")}
                    if(resourceState.active){LinearProgressIndicator(progress={resourceState.progress},modifier=Modifier.fillMaxWidth());Text(formatBytes(resourceState.bytes)+" / "+if(resourceState.total>0)formatBytes(resourceState.total) else "processing")}
                    resourceState.error?.let{Text("Pack error: "+it,color=MaterialTheme.colorScheme.error)}
                    resourceState.message?.let{Text(it,color=MaterialTheme.colorScheme.primary)}
                    Text("Large archives are streamed in bounded batches. Import continues while device storage is available.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)
                }
            }
        }
        item{Text("OCR and translation use on-device ML where available; OREZ adds normalization, dialogue grouping, confidence scoring and reconstruction logic.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall)}
    }
}
@Composable private fun TranslationSwitch(title:String,description:String,checked:Boolean,onCheckedChange:(Boolean)->Unit){Card(Modifier.fillMaxWidth()){Row(Modifier.fillMaxWidth().padding(16.dp),horizontalArrangement=Arrangement.SpaceBetween){Column(Modifier.weight(1f)){Text(title,fontWeight=FontWeight.SemiBold);Text(description,color=MaterialTheme.colorScheme.onSurfaceVariant)};Switch(checked,onCheckedChange)}}}
private fun formatBytes(n:Long):String=when{n>=1024*1024->"%.1f MB".format(n/1024f/1024f);n>=1024->"%.1f KB".format(n/1024f);else->n.toString()+" B"}
