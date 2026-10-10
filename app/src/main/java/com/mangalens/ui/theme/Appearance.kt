package com.mangalens.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

enum class LayoutDensity(val label: String, val padding: androidx.compose.ui.unit.Dp, val gap: androidx.compose.ui.unit.Dp) {
    COMPACT("Compact", 12.dp, 8.dp), BALANCED("Balanced", 16.dp, 12.dp), COMFORTABLE("Comfortable", 20.dp, 16.dp)
}
enum class Accent(val label: String, private val dark: Long, private val light: Long) {
    BLUE("Blue", 0xFF8DBBFA, 0xFF285EAD), CYAN("Cyan", 0xFF86CADA, 0xFF006A80),
    VIOLET("Violet", 0xFFBEAAEC, 0xFF664099), ROSE("Rose", 0xFFE7A8BC, 0xFF9C3658),
    AMBER("Amber", 0xFFE3C087, 0xFF795300), GREEN("Green", 0xFF93C7AE, 0xFF216445),
    MONOCHROME("Silver", 0xFFD8DEE7, 0xFF465569);
    fun color(darkTheme: Boolean) = Color(if (darkTheme) dark else light)
}
enum class CustomDarkTone(val label: String, val background: Color, val surface: Color, val border: Color) {
    BLUE_GRAY("Blue gray", Color(0xFF090C10), Color(0xFF141A22), Color(0xFF313C49)),
    NEUTRAL("Neutral", Color(0xFF0C0C0C), Color(0xFF1B1B1B), Color(0xFF363636)),
    WARM("Warm", Color(0xFF110F0D), Color(0xFF201C17), Color(0xFF3F3830))
}

enum class CustomDarkContrast(val label: String, val surfaceLift: Float) {
    STANDARD("Standard", 0f), RAISED("Raised", .04f)
}

internal object InterfaceTextScale {
    const val MIN = .9f
    const val MAX = 1.3f
    fun bounded(value: Float): Float = if (value.isFinite())
        (value.coerceIn(MIN, MAX) * 20).roundToInt() / 20f else 1f
}

data class Appearance(
    val density: LayoutDensity = LayoutDensity.COMPACT,
    val accent: Accent = Accent.BLUE,
    val reducedMotion: Boolean = false,
    val typographyScale: Float = 1f,
    val haptics: Boolean = false,
    val customDarkTone: CustomDarkTone = CustomDarkTone.BLUE_GRAY,
    val customDarkContrast: CustomDarkContrast = CustomDarkContrast.STANDARD
)
val LocalAppearance = staticCompositionLocalOf { Appearance() }
internal val LocalInterfaceHaptics = staticCompositionLocalOf<HapticFeedback> {
    object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
    }
}

@Composable
fun rememberAppearance(): Appearance {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("mangalens_appearance", Context.MODE_PRIVATE) }
    fun read() = Appearance(
        LayoutDensity.entries.firstOrNull { it.name == prefs.getString("density", "COMPACT") } ?: LayoutDensity.COMPACT,
        Accent.entries.firstOrNull { it.name == prefs.getString("accent", "BLUE") } ?: Accent.BLUE,
        prefs.getBoolean("reduced_motion", false),
        InterfaceTextScale.bounded(runCatching { prefs.getFloat("typography_scale", 1f) }.getOrDefault(1f)),
        runCatching { prefs.getBoolean("haptics", false) }.getOrDefault(false),
        CustomDarkTone.entries.firstOrNull { it.name == runCatching { prefs.getString("custom_dark_tone", "BLUE_GRAY") }.getOrNull() }
            ?: CustomDarkTone.BLUE_GRAY,
        CustomDarkContrast.entries.firstOrNull { it.name == runCatching { prefs.getString("custom_dark_contrast", "STANDARD") }.getOrNull() }
            ?: CustomDarkContrast.STANDARD
    )
    var value by remember(prefs) { mutableStateOf(read()) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> value = read() }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return value
}
