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
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import coil.compose.AsyncImage
import com.mangalens.R
import com.mangalens.core.reader.SavedChapter
import com.mangalens.ui.theme.LocalAppearance
import com.mangalens.ui.reader.rememberMangaThumbnailRequest

@Composable fun BrandHeader(title: String = "MangaLens", subtitle: String? = null, action: @Composable RowScope.() -> Unit = {}) {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val titleStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.ExtraBold)
    val titleWidth = textMeasurer.measure(title, titleStyle, softWrap = false, maxLines = 1).size.width
    SubcomposeLayout(Modifier.fillMaxWidth().padding(vertical = 8.dp)) { constraints ->
        val width = constraints.maxWidth
        val textWidth = (width - with(density) { 54.dp.roundToPx() }).coerceAtLeast(1)
        // Keep the full brand even when accessibility text alone exceeds the available row.
        val scale = (textWidth.toFloat() / (titleWidth + 1).coerceAtLeast(1)).coerceAtMost(1f)
        val childConstraints = Constraints(maxWidth = width)
        val brand = subcompose("brand") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(15.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(painterResource(R.drawable.mangalens_approved_logo), "MangaLens logo", Modifier.size(40.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(title, style = titleStyle.copy(fontSize = titleStyle.fontSize * scale), maxLines = 1, softWrap = false)
                    subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }.single().measure(childConstraints)
        val actions = subcompose("actions") {
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, content = action)
        }.single().measure(childConstraints)
        val stack = brandHeaderStacksActions(width, brand.width, actions.width, with(density) { 12.dp.roundToPx() })
        val gap = if (stack) with(density) { 8.dp.roundToPx() } else 0
        val height = if (stack) brand.height + gap + actions.height else maxOf(brand.height, actions.height)
        layout(width, constraints.constrainHeight(height)) {
            brand.placeRelative(0, if (stack) 0 else (height - brand.height) / 2)
            actions.placeRelative(width - actions.width, if (stack) brand.height + gap else (height - actions.height) / 2)
        }
    }
}

@Composable fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = .7f))) {
        Column(Modifier.padding(LocalAppearance.current.density.padding),
            verticalArrangement = Arrangement.spacedBy(LocalAppearance.current.density.gap), content = content)
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
                        MaterialTheme.colorScheme.outline,
                        com.mangalens.ui.theme.MangaLensDesignTokens.NeonViolet.copy(alpha = .65f)
                    )
                ),
                RoundedCornerShape(24.dp)
            )
    ) {
        Image(painterResource(artwork), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF20A070D), Color(0xC710080B), Color(0x26000000)))))
        Column(Modifier.align(Alignment.BottomStart).padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("READ BEYOND LANGUAGE", color = Color(0xFFB8D1F3), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
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
            AsyncImage(rememberMangaThumbnailRequest(chapter.pages.firstOrNull()?.localPath), chapter.title + " cover", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xB3000000)))))
            if (chapter.bookmarked) Icon(Icons.Outlined.Bookmark, "Bookmarked", Modifier.align(Alignment.TopStart).padding(6.dp).size(19.dp), tint = MaterialTheme.colorScheme.primary)
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
            AsyncImage(rememberMangaThumbnailRequest(chapter.pages.firstOrNull()?.localPath), null, Modifier.size(56.dp, 76.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
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
            Modifier.padding(horizontal = LocalAppearance.current.density.padding, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(13.dp))
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
