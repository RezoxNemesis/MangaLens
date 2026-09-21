package com.mangalens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var isDarkTheme by remember { mutableStateOf(false) }
            
            MangaLensTheme(darkTheme = isDarkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        isDarkTheme = isDarkTheme,
                        onThemeToggle = { isDarkTheme = !isDarkTheme }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(isDarkTheme: Boolean, onThemeToggle: () -> Unit) {
    var selectedTab by remember { mutableStateOf(0) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MangaLens Pro") },
                navigationIcon = {
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                actions = {
                    TextButton(onClick = onThemeToggle) {
                        Text(if (isDarkTheme) "☀️ Light" else "🌙 Dark")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Home, contentDescription = "Translate") },
                    label = { Text("Translate") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Language, contentDescription = "Languages") },
                    label = { Text("Languages") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.History, contentDescription = "History") },
                    label = { Text("History") },
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center
        ) {
            when (selectedTab) {
                0 -> TranslationHomeContent()
                1 -> LanguageScreenComponent()
                2 -> HistoryScreenComponent()
            }
        }

        if (showSettingsDialog) {
            SettingsDialog(onDismiss = { showSettingsDialog = false })
        }
    }
}

@Composable
fun TranslationHomeContent() {
    var mangaUrl by remember { mutableStateOf("") }
    var translationStatus by remember { mutableStateOf("Ready for chapter link or image upload") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "Manga Chapter URL Translation", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        
        OutlinedTextField(
            value = mangaUrl,
            onValueChange = { mangaUrl = it },
            label = { Text("Paste Manga Chapter Link Here") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        
        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = {
                if (mangaUrl.isNotBlank()) {
                    translationStatus = "Scraping chapter panels & initializing OCR..."
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Fetch & Translate Chapter")
        }

        Spacer(modifier = Modifier.height(24.dp))
        Divider()
        Spacer(modifier = Modifier.height(24.dp))

        Text(text = "Or Upload Local Manga Archive/Images", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = { translationStatus = "Opening file manager..." },
                modifier = Modifier.weight(1f)
            ) {
                Text("Upload Files")
            }
            OutlinedButton(
                onClick = { translationStatus = "Imported from clipboard." },
                modifier = Modifier.weight(1f)
            ) {
                Text("Paste Image")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(text = "Pipeline Status", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = translationStatus, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun LanguageScreenComponent() {
    val languages = listOf(
        "Japanese (Auto-Detect)", "English", "Korean", "Chinese (Simplified)", 
        "Chinese (Traditional)", "Spanish", "French", "German", "Portuguese", 
        "Vietnamese", "Indonesian", "Russian", "Italian", "Turkish"
    )
    var selectedTarget by remember { mutableStateOf("English") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(text = "Target Translation Language", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = "Currently translating to: $selectedTarget", color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(languages) { lang ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedTarget = lang },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedTarget == lang) 
                            MaterialTheme.colorScheme.primaryContainer 
                        else 
                            MaterialTheme.colorScheme.surface
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = lang, style = MaterialTheme.typography.bodyLarge)
                        if (selectedTarget == lang) {
                            Text(text = "Active", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryScreenComponent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("No translated chapters in history yet.", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    var apiKey by remember { mutableStateOf("") }
    val ocrEngine by remember { mutableStateOf("Google ML Kit (High Accuracy)") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("MangaLens Settings") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("Translation API Key (Optional)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                Text(text = "OCR Engine: $ocrEngine", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { /* Clear Cache Logic */ }, modifier = Modifier.fillMaxWidth()) {
                    Text("Clear Local Image Cache")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Save & Close")
            }
        }
    )
}

@Composable
fun MangaLensTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) {
        darkColorScheme()
    } else {
        lightColorScheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

