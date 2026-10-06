package com.mangalens.ui.components

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mangalens.R
import com.mangalens.core.reader.SavedChapter

@Composable fun BrandHeader(title: String = "MangaLens", subtitle: String? = null, action: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(52.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(Brush.radialGradient(listOf(Color(0x883A0D62), Color(0xFF090B12))))
                .border(1.dp, Color(0xAAFF2D64), RoundedCornerShape(15.dp)),
            contentAlignment = Alignment.Center
        ) {
            Image(painterResource(R.drawable.mangalens_icon_foreground), "MangaLens 2.0 logo", Modifier.size(46.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        action()
    }
}

@Composable fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.surface.copy(alpha = .96f),
                        Color(0xE90D111B),
                        MaterialTheme.colorScheme.primary.copy(alpha = .045f)
                    )
                )
            )
            .border(
                1.dp,
                Brush.linearGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = .65f),
                        MaterialTheme.colorScheme.outline.copy(alpha = .65f),
                        com.mangalens.ui.theme.MangaLensDesignTokens.NeonViolet.copy(alpha = .45f)
                    )
                ),
                RoundedCornerShape(20.dp)
            )
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable fun ArtworkHero(title: String, detail: String, artwork: Int, button: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(236.dp)
            .clip(RoundedCornerShape(24.dp))
            .border(
                1.dp,
                Brush.horizontalGradient(
                    listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = .95f),
                        Color(0x66FF5C7A),
                        com.mangalens.ui.theme.MangaLensDesignTokens.NeonViolet.copy(alpha = .65f)
                    )
                ),
                RoundedCornerShape(24.dp)
            )
    ) {
        Image(painterResource(artwork), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF20A070D), Color(0xC710080B), Color(0x26000000)))))
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("READ BEYOND LANGUAGE", color = Color(0xFFFF8191), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text(title, color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
            Text(detail, color = Color(0xFFE3D8DC), style = MaterialTheme.typography.bodySmall, maxLines = 2)
            Button(
                onClick,
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 11.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) { Text(button, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable fun ChapterCover(chapter: SavedChapter, onClick: () -> Unit, modifier: Modifier = Modifier, menu: (@Composable () -> Unit)? = null) {
    Column(modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(.69f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            AsyncImage(chapter.pages.firstOrNull()?.localPath, chapter.title + " cover", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000)))))
            if (chapter.bookmarked) Icon(Icons.Outlined.Bookmark, "Bookmarked", Modifier.align(Alignment.TopStart).padding(6.dp).size(19.dp), tint = Color(0xFFFF8191))
            menu?.let { Box(Modifier.align(Alignment.TopEnd)) { it() } }
            Text("${(chapter.position + 1).coerceAtMost(chapter.pages.size)} / ${chapter.pages.size}", Modifier.align(Alignment.BottomStart).padding(8.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        Text(chapter.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
        LinearProgressIndicator(progress = { ((chapter.position + 1).toFloat() / chapter.pages.size.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(2.dp))
    }
}

@Composable fun ContinueCard(chapter: SavedChapter, onClick: () -> Unit) {
    Surface(onClick, Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AsyncImage(chapter.pages.firstOrNull()?.localPath, null, Modifier.size(56.dp, 76.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(chapter.title, maxLines = 2, style = MaterialTheme.typography.titleSmall)
                Text("Page ${(chapter.position + 1).coerceAtMost(chapter.pages.size)} of ${chapter.pages.size} · Offline", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.PlayArrow, "Continue reading", tint = MaterialTheme.colorScheme.secondary)
        }
    }
}


@Composable
fun NeonActionTile(
    title: String,
    subtitle: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .88f),
        border = BorderStroke(1.dp, border.copy(alpha = if (selected) .9f else .65f))
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Box(
                Modifier.size(42.dp).clip(RoundedCornerShape(13.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = .14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.secondary)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2) }
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun NeonStatusPill(
    text: String,
    modifier: Modifier = Modifier,
    positive: Boolean = false
) {
    val accent = if (positive) com.mangalens.ui.theme.MangaLensDesignTokens.Success else MaterialTheme.colorScheme.secondary
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = accent.copy(alpha = .09f),
        border = BorderStroke(1.dp, accent.copy(alpha = .55f))
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            color = accent,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}
