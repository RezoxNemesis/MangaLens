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

enum class ThemeMode { SYSTEM, DARK, AMOLED, LIGHT, HIGH_CONTRAST }

object MangaLensDesignTokens {
    val LightBackground = Color(0xFFFFFFFF)
    val LightSurface = Color(0xFFF5F5F7)
    val LightSurfaceBorder = Color(0xFFE0E0E6)
    val LightTextPrimary = Color(0xFF111111)
    val LightTextSecondary = Color(0xFF66666D)
    val DarkBackground = Color(0xFF090C10)
    val DarkSurface = Color(0xFF141A22)
    val DarkSurfaceBorder = Color(0xFF313C49)
    val DarkTextPrimary = Color(0xFFFFFFFF)
    val DarkTextSecondary = Color(0xFFB7C2CE)
    val Primary = Color(0xFF8DBBFA)
    val LogoViolet = Color(0xFF9AAFC9)
    val LogoCyan = Color(0xFF91C5D6)
    val NeonViolet = Color(0xFF9DAAC0)
    val NeonBlue = Color(0xFF8DBBFA)
    val Success = Color(0xFF85C6B3)
    val Glass = Color(0xCC141A22)
    val Secondary = Color(0xFFB4C7DF)
}

private val LightColors = lightColorScheme(
    primary = MangaLensDesignTokens.Primary,
    secondary = MangaLensDesignTokens.Secondary,
    primaryContainer = Color(0xFFD6E5FC),
    onPrimaryContainer = Color(0xFF20344E),
    secondaryContainer = Color(0xFFE5EBF3),
    onSecondaryContainer = Color(0xFF283644),
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
    primaryContainer = Color(0xFF20344E),
    onPrimaryContainer = Color(0xFFD6E5FC),
    secondaryContainer = Color(0xFF283644),
    onSecondaryContainer = Color(0xFFD6E5FC),
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
        ThemeMode.DARK, ThemeMode.AMOLED, ThemeMode.HIGH_CONTRAST -> true
        ThemeMode.LIGHT -> false
    }
    val appearance = rememberAppearance()
    val baseColors = if (darkTheme) DarkColors else LightColors
    val colors = baseColors.copy(
        primary = appearance.accent.color(darkTheme),
        onPrimary = if (darkTheme) Color(0xFF0E1A2B) else Color.White,
        background = if (themeMode == ThemeMode.AMOLED) Color.Black else baseColors.background,
        surface = if (themeMode == ThemeMode.AMOLED) Color(0xFF080B0F) else baseColors.surface,
        onSurfaceVariant = if (themeMode == ThemeMode.HIGH_CONTRAST) Color.White else baseColors.onSurfaceVariant,
        outline = if (themeMode == ThemeMode.HIGH_CONTRAST) Color(0xFF94A3B8) else baseColors.outline
    )
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
    androidx.compose.runtime.CompositionLocalProvider(LocalAppearance provides appearance) {
    MaterialTheme(
        colorScheme = colors,
        typography = MangaLensTypography,
        shapes = androidx.compose.material3.Shapes(medium = androidx.compose.foundation.shape.RoundedCornerShape(14.dp), large = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)),
        content = content
    )
    }
}
