package com.mangalens.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp

private data class NavItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

private val navItems = listOf(
    NavItem("home", "Home", Icons.Default.Home),
    NavItem("library", "Library", Icons.Default.MenuBook),
    NavItem("orez", "Orez AI", Icons.Default.SmartToy),
    NavItem("settings", "Settings", Icons.Default.Settings),
    NavItem("downloads", "Downloads", Icons.Default.FileDownload)
)

@Composable
fun MangaLensBottomNav(currentRoute: String?, onNavigate: (String) -> Unit) {
    NavigationBar {
        Row(modifier = Modifier.fillMaxWidth()) {
            navItems.forEach { item ->
                NavigationBarItem(
                    selected = currentRoute == item.route,
                    modifier = Modifier.semantics { contentDescription = item.label },
                    onClick = { onNavigate(item.route) },
                    icon = { Icon(item.icon, contentDescription = null) },
                    label = { Text(item.label, maxLines = 1, softWrap = false, fontSize = 10.sp) }
                )
            }
        }
    }
}
