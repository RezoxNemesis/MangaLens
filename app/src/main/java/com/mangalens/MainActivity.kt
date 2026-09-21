package com.mangalens

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Requirement 1: Dark and Light Theme support
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
    // Requirement 2: Bottom Navigation state (0: History, 1: Translation Language)
    var selectedTab by remember { mutableStateOf(0) }
    
    // Requirement 4: Live translation toggle state
    var isLiveTranslationActive by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MangaLens") },
                // Requirement 5: Settings option on the upper left corner
                navigationIcon = {
                    IconButton(onClick = { /* Handle settings action */ }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                actions = {
                    // Extra (Requirement 6): Theme switch shortcut button on top right
                    TextButton(onClick = onThemeToggle) {
                        Text(if (isDarkTheme) "☀️ Light" else "🌙 Dark")
                    }
                }
            )
        },
        bottomBar = {
            // Requirement 2: 2 tabs on the bottom
            NavigationBar {
                NavigationBarItem(
                    icon = { Icon(Icons.Default.History, contentDescription = "History") },
                    label = { Text("History") },
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Language, contentDescription = "Translation Language") },
                    label = { Text("Language") },
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 }
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
                0 -> HistoryScreen()
                1 -> LanguageScreen()
                else -> TranslationHomeContent(
                    isLiveActive = isLiveTranslationActive,
                    onLiveToggle = { isLiveTranslationActive = it }
                )
            }
        }
    }
}

@Composable
fun TranslationHomeContent(isLiveActive: Boolean, onLiveToggle: (Boolean) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16px_to_dp_fix = 16.dp), // standard spacing
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Requirement 3: Option to paste or upload an image
        Text(text = "Upload or Paste Manga Image", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(onClick = { /* TODO: Open Image Picker */ }) {
                Text("Upload Image")
            }
            OutlinedButton(onClick = { /* TODO: Paste from Clipboard */ }) {
                Text("Paste Image")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Requirement 4: Live translation smooth feature toggle & explanation
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Smooth Live Translation", style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = isLiveActive, onCheckedChange = onLiveToggle)
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isLiveActive) 
                        "Status: Active. Text replacement & font matching enabled on scroll." 
                    else 
                        "Status: Paused. Toggle on for real-time overlay.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun HistoryScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Your Translation History will appear here", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun LanguageScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Select Source and Target Translation Languages", style = MaterialTheme.typography.bodyLarge)
    }
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

