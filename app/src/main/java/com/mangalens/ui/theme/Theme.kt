package com.mangalens.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

enum class ThemeMode { SYSTEM, DARK, LIGHT }

object MangaLensDesignTokens {
    val LightBackground = Color(0xFFFFFFFF)
    val LightSurface = Color(0xFFF5F5F7)
    val LightSurfaceBorder = Color(0xFFE0E0E6)
    val LightTextPrimary = Color(0xFF111111)
    val LightTextSecondary = Color(0xFF66666D)
    val DarkBackground = Color(0xFF0B0D14)
    val DarkSurface = Color(0xFF151923)
    val DarkSurfaceBorder = Color(0xFF303646)
    val DarkTextPrimary = Color(0xFFFFFFFF)
    val DarkTextSecondary = Color(0xFFAFB8CB)
    val Primary = Color(0xFFD91936)
    val LogoViolet = Color(0xFF7C4DFF)
    val LogoCyan = Color(0xFF00E5FF)
    val Secondary = Color(0xFFFF667D)
}

private val LightColors = lightColorScheme(
    primary = MangaLensDesignTokens.Primary,
    secondary = MangaLensDesignTokens.Secondary,
    primaryContainer = Color(0xFFFFD9DF),
    onPrimaryContainer = Color(0xFF480A16),
    secondaryContainer = Color(0xFFFFE8ED),
    onSecondaryContainer = Color(0xFF2B111A),
    background = MangaLensDesignTokens.LightBackground,
    surface = MangaLensDesignTokens.LightSurface,
    surfaceVariant = MangaLensDesignTokens.LightSurfaceBorder,
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = MangaLensDesignTokens.LightTextPrimary,
    onSurface = MangaLensDesignTokens.LightTextPrimary,
    onSurfaceVariant = MangaLensDesignTokens.LightTextSecondary,
    outline = MangaLensDesignTokens.LightSurfaceBorder
)

private val DarkColors = darkColorScheme(
    primary = MangaLensDesignTokens.Primary,
    secondary = MangaLensDesignTokens.Secondary,
    primaryContainer = Color(0xFF480A16),
    onPrimaryContainer = Color(0xFFFFD9DF),
    secondaryContainer = Color(0xFF2B111A),
    onSecondaryContainer = Color(0xFFFFD9DF),
    background = MangaLensDesignTokens.DarkBackground,
    surface = MangaLensDesignTokens.DarkSurface,
    surfaceVariant = MangaLensDesignTokens.DarkSurfaceBorder,
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = MangaLensDesignTokens.DarkTextPrimary,
    onSurface = MangaLensDesignTokens.DarkTextPrimary,
    onSurfaceVariant = MangaLensDesignTokens.DarkTextSecondary,
    outline = MangaLensDesignTokens.DarkSurfaceBorder
)

val MangaLensTypography = Typography(
    headlineLarge = androidx.compose.ui.text.TextStyle(fontSize = androidx.compose.ui.unit.TextUnit(30f, androidx.compose.ui.unit.TextUnitType.Sp), fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold),
    headlineMedium = androidx.compose.ui.text.TextStyle(fontSize = androidx.compose.ui.unit.TextUnit(26f, androidx.compose.ui.unit.TextUnitType.Sp), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
    titleLarge = androidx.compose.ui.text.TextStyle(fontSize = androidx.compose.ui.unit.TextUnit(22f, androidx.compose.ui.unit.TextUnitType.Sp), fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
    titleMedium = androidx.compose.ui.text.TextStyle(fontSize = androidx.compose.ui.unit.TextUnit(17f, androidx.compose.ui.unit.TextUnitType.Sp), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
)

@Composable
fun MangaLensTheme(themeMode: ThemeMode = ThemeMode.DARK, content: @Composable () -> Unit) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? android.app.Activity)?.window?.let { window ->
            androidx.core.view.WindowInsetsControllerCompat(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
            window.navigationBarColor = Color.Transparent.toArgb()
            if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        }
    }
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = MangaLensTypography,
        shapes = androidx.compose.material3.Shapes(medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), large = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)),
        content = content
    )
}
