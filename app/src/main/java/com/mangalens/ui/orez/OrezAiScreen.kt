package com.mangalens.ui.orez

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val prompts = listOf(
    "Explain this chapter", "Summarize my library", "Configure translation",
    "What can MangaLens do?", "How does local OCR work?", "How does video playback work?",
    "How does ad blocking work?", "How do I use auto-scroll?"
)

@Composable
fun OrezAiScreen() {
    var prompt by remember { mutableStateOf("") }
    var response by remember { mutableStateOf("Orez AI is ready. Ask about MangaLens, translation, reading, video, web acquisition or settings.") }
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = .25f), MaterialTheme.colorScheme.background)))) {
        LazyColumn(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("OREZ AI", style = MaterialTheme.typography.displaySmall, fontSize = 34.sp)
                Text("Local feature knowledge matrix", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Card(shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .78f))) {
                    Column(Modifier.padding(18.dp)) {
                        OutlinedTextField(value = prompt, onValueChange = { prompt = it }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Ask Orez…") })
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { response = localResponse(prompt) }, enabled = prompt.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("RUN LOCAL") }
                    }
                }
            }
            item { Card(shape = RoundedCornerShape(28.dp)) { Text(response, Modifier.padding(18.dp)) } }
            item { Text("Feature matrix", style = MaterialTheme.typography.titleLarge) }
            items(prompts) { suggestion -> AssistChip(onClick = { prompt = suggestion; response = localResponse(suggestion) }, label = { Text(suggestion) }) }
        }
    }
}

private fun localResponse(prompt: String): String {
    val p = prompt.lowercase()
    return when {
        "ocr" in p -> "Local OCR uses ML Kit Text Recognition with source-language profiling and reconstructed translated overlays. Models run on-device after download; no paid translation API is required."
        "video" in p -> "Local video uses MediaStore discovery, Media3, FFmpeg extension renderers, decoder fallback, subtitles, audio tracks and MX-style gestures. Online playback adds redirect-aware HTTP and cache support."
        "ad" in p -> "The WebView layer filters known ad/tracker domains, hides common popup elements and records blocked-request statistics. Security verification pages are intentionally left accessible."
        "scroll" in p -> "The continuous reader has auto-scroll with a 1x–5x speed control and a persistent bottom control bar."
        "translation" in p || "configure" in p -> "Manga, Video and Web translation are separate Settings switches. Manga translation performs local OCR and then uses the on-device ML translation pipeline."
        "library" in p -> "The Library is backed by the progressive chapter repository and can reopen acquired pages in the continuous reader."
        "chapter" in p -> "Chapter acquisition combines rendered-browser discovery with DOM image fallbacks for src, data-src, srcset and lazy-loaded images, with user verification when a site requires it."
        "what can" in p || "mangalens" in p -> "MangaLens combines chapter acquisition, OCR translation, continuous reading, local/online video playback, web protection, history and the Orez assistant."
        else -> "I can explain MangaLens acquisition, OCR, translation, reader controls, video playback, ad blocking, verification, history and settings."
    }
}
