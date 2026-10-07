package com.mangalens.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private data class NavItem(val route: String, val label: String, val accessibilityLabel: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
private val navItems = listOf(
    NavItem("home", "Home", "Home", Icons.Outlined.Home),
    NavItem("library", "Library", "Library", Icons.Outlined.MenuBook),
    NavItem("watch", "Watch", "Watch videos", Icons.Outlined.PlayCircle),
    NavItem("web", "Web", "Web browser", Icons.Outlined.Language),
    NavItem("orez", "Orez", "Orez AI", Icons.Outlined.SmartToy)
)
@Composable fun MangaLensBottomNav(currentRoute: String?, onNavigate: (String) -> Unit) {
    val duration = if (com.mangalens.ui.theme.LocalAppearance.current.reducedMotion) 0 else 220
    Column(Modifier.background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Row(Modifier.fillMaxWidth().height(66.dp)) {
            navItems.forEach { item -> val selected = currentRoute == item.route
                val tint = animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                    tween(duration),
                    label = "navTint"
                ).value
                val tile = animateColorAsState(
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .12f)
                    else androidx.compose.ui.graphics.Color.Transparent,
                    tween(duration),
                    label = "navTile"
                ).value
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .padding(horizontal = 5.dp, vertical = 5.dp)
                        .background(tile, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                        .semantics { contentDescription = item.accessibilityLabel }
                        .clickable { onNavigate(item.route) }
                        .padding(top = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(item.icon, null, Modifier.size(if (selected) 25.dp else 23.dp), tint = tint)
                    Text(item.label, fontSize = 10.sp, color = tint, maxLines = 1)
                    if (selected) Box(Modifier.size(28.dp, 3.dp).background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.RoundedCornerShape(50)))
                }
            }
        }
    }
}
