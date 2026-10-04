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
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.mangalens_logo), "MangaLens logo", Modifier.size(42.dp))
        Spacer(Modifier.width(9.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        action()
    }
}

@Composable fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .7f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable fun ArtworkHero(title: String, detail: String, artwork: Int, button: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(20.dp)).border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .55f), RoundedCornerShape(20.dp))) {
        Image(painterResource(artwork), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDC10080B), Color(0xFF10080B)))))
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("READ BEYOND LANGUAGE", color = Color(0xFFFF8191), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Text(title, color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Text(detail, color = Color(0xFFE3D8DC), style = MaterialTheme.typography.bodySmall, maxLines = 2)
            Button(onClick, shape = RoundedCornerShape(10.dp), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp)) { Text(button) }
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
